// evgrab — JNI evdev grabber for the Shizuku UserService (KeyGrabberService).
//
// Loaded with System.load() INSIDE the privileged process — no /data/local/tmp binary, nothing exec'd.
// nativeSetup() opens + EVIOCGRABs the simple volume node(s); nativeRun() runs the poll/classify loop
// and calls KeyGrabberService.onNativeKey(code, type) for each gesture — type 0=short, 1=long, 2=double,
// 3=triple. nativeStop() breaks the loop.
//
// Multi-tap: each key's max tap count is set by which list it's in — codes in [tripleCodes] count up to 3,
// codes in [doubleCodes] up to 2, others fire a single short immediately. A tap fires as soon as the key's
// max is reached (snappy), otherwise after the double window with no further tap. Single short presses are
// re-injected (so volume still works); double/triple are consumed.
//
// Creates NO uinput device. Only SIMPLE volume nodes are grabbed (keyset ⊆ {mute,vol-down,vol-up});
// touch/pointer/rich/keyboard/power devices are skipped.
#define _GNU_SOURCE
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/eventfd.h>
#include <linux/input.h>
#include <android/log.h>

#define LOG_TAG "OpenTaskerKeyGrab"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define MAX_DEV 24
#define MAX_CODE (KEY_MAX + 1)
#define LONG_BITS (8 * sizeof(unsigned long))
#define NLONGS(n) (((n) + LONG_BITS - 1) / LONG_BITS)
#define TEST_BIT(b, a) (((a)[(b) / LONG_BITS] >> ((b) % LONG_BITS)) & 1UL)

// Gesture types reported to Java (must match the listener's mapping).
#define TYPE_SHORT  0
#define TYPE_LONG   1
#define TYPE_DOUBLE 2
#define TYPE_TRIPLE 3

static volatile int g_stop = 0;
// Screen state, pushed from the app (setScreenOn). 1 = screen on → the SINGLE tap is consumed (handed to
// our profiles, e.g. the volume panel); 0 = screen off → the single tap is re-injected so system volume
// still works exactly as before. Long/double/triple are always consumed regardless. Default on.
static volatile int g_screen_on = 1;
static int g_wakefd = -1;
static int g_evfd[MAX_DEV];
static int g_ndev = 0;
static int g_codes[KEY_MAX + 1];
static int g_ncodes = 0;
static int g_dblcodes[KEY_MAX + 1];
static int g_ndblcodes = 0;
static int g_tripcodes[KEY_MAX + 1];
static int g_ntripcodes = 0;

static int in_set(int code, const int *set, int n) {
    for (int i = 0; i < n; i++) if (set[i] == code) return 1;
    return 0;
}
static int is_watched(int code) { return in_set(code, g_codes, g_ncodes); }
static int max_taps(int code) {
    if (in_set(code, g_tripcodes, g_ntripcodes)) return 3;
    if (in_set(code, g_dblcodes, g_ndblcodes)) return 2;
    return 1;
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int android_keycode(int evcode) {
    switch (evcode) {
        case KEY_VOLUMEDOWN: return 25;  // KEYCODE_VOLUME_DOWN
        case KEY_VOLUMEUP:   return 24;  // KEYCODE_VOLUME_UP
        case KEY_MUTE:       return 164; // KEYCODE_VOLUME_MUTE
        default:             return 0;
    }
}

static void reinject_short(int evcode) {
    int kc = android_keycode(evcode);
    if (kc == 0) return;
    char cmd[64];
    snprintf(cmd, sizeof(cmd), "input keyevent %d &", kc);  // backgrounded so the loop never stalls
    int rc = system(cmd);
    (void)rc;
}

// Report a completed tap run: count 1 = short, 2 = double, 3+ = triple. Always report to Java. A short tap
// is re-injected ONLY when the screen is off (system volume unchanged); screen-on shorts are consumed so
// our profiles can repurpose them (volume panel / per-app keycodes). Double/triple are always consumed.
static void fire_tap(JNIEnv *env, jobject thiz, jmethodID onKey, int code, int count) {
    int type = (count <= 1) ? TYPE_SHORT : (count == 2) ? TYPE_DOUBLE : TYPE_TRIPLE;
    (*env)->CallVoidMethod(env, thiz, onKey, (jint)code, (jint)type);
    if (type == TYPE_SHORT && !g_screen_on) reinject_short(code);
}

static void release_grabs(void) {
    for (int i = 0; i < g_ndev; i++) {
        ioctl(g_evfd[i], EVIOCGRAB, 0);
        close(g_evfd[i]);
    }
    g_ndev = 0;
}

static int parse_codes(JNIEnv *env, jintArray arr, int *out) {
    jsize n = (*env)->GetArrayLength(env, arr);
    jint *vals = (*env)->GetIntArrayElements(env, arr, NULL);
    int count = 0;
    for (jsize i = 0; i < n && count < KEY_MAX; i++) out[count++] = vals[i];
    (*env)->ReleaseIntArrayElements(env, arr, vals, JNI_ABORT);
    return count;
}

JNIEXPORT jint JNICALL
Java_com_opentasker_core_input_KeyGrabberService_nativeSetup(JNIEnv *env, jobject thiz,
                                                             jintArray codesArr, jintArray dblArr, jintArray tripArr) {
    (void)thiz;
    release_grabs();
    g_ncodes = parse_codes(env, codesArr, g_codes);
    g_ndblcodes = parse_codes(env, dblArr, g_dblcodes);
    g_ntripcodes = parse_codes(env, tripArr, g_tripcodes);

    DIR *d = opendir("/dev/input");
    if (!d) { LOGW("opendir /dev/input: %s", strerror(errno)); return -1; }
    struct dirent *de;
    while ((de = readdir(d)) && g_ndev < MAX_DEV) {
        if (strncmp(de->d_name, "event", 5) != 0) continue;
        char path[300];
        snprintf(path, sizeof(path), "/dev/input/%s", de->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        unsigned long keybits[NLONGS(KEY_MAX + 1)];
        memset(keybits, 0, sizeof(keybits));
        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) { close(fd); continue; }
        int hit = 0;
        for (int i = 0; i < g_ncodes; i++) if (TEST_BIT(g_codes[i], keybits)) { hit = 1; break; }
        if (!hit) { close(fd); continue; }

        // Skip touch/pointer — grabbing the touchscreen would deaden touch.
        unsigned long evbits[NLONGS(EV_MAX + 1)];
        memset(evbits, 0, sizeof(evbits));
        ioctl(fd, EVIOCGBIT(0, sizeof(evbits)), evbits);
        unsigned long props[NLONGS(INPUT_PROP_MAX + 1)];
        memset(props, 0, sizeof(props));
        ioctl(fd, EVIOCGPROP(sizeof(props)), props);
        if (TEST_BIT(EV_ABS, evbits) || TEST_BIT(BTN_TOUCH, keybits) ||
            TEST_BIT(INPUT_PROP_DIRECT, props) || TEST_BIT(INPUT_PROP_POINTER, props)) {
            close(fd);
            continue;
        }
        // Only SIMPLE volume nodes: skip anything carrying extra keys (media/nav/alpha/power).
        int rich = 0;
        for (int c = 0; c <= KEY_MAX; c++) {
            if (!TEST_BIT(c, keybits)) continue;
            if (c != KEY_MUTE && c != KEY_VOLUMEDOWN && c != KEY_VOLUMEUP) { rich = 1; break; }
        }
        if (rich) { close(fd); continue; }

        if (ioctl(fd, EVIOCGRAB, 1) != 0) { close(fd); continue; }
        g_evfd[g_ndev++] = fd;
        LOGI("grabbed %s", path);
    }
    closedir(d);
    if (g_ndev == 0) LOGW("no target devices");
    return g_ndev;
}

JNIEXPORT void JNICALL
Java_com_opentasker_core_input_KeyGrabberService_nativeRun(JNIEnv *env, jobject thiz,
                                                           jlong longMs, jlong dblMs) {
    long long_ms = (long)longMs;  if (long_ms < 100) long_ms = 100;
    long dbl_ms  = (long)dblMs;   if (dbl_ms  < 0)   dbl_ms  = 0;
    g_stop = 0;

    jclass cls = (*env)->GetObjectClass(env, thiz);
    jmethodID onKey = (*env)->GetMethodID(env, cls, "onNativeKey", "(II)V");
    if (!onKey) { LOGW("onNativeKey not found"); release_grabs(); return; }

    g_wakefd = eventfd(0, EFD_NONBLOCK);
    struct pollfd pfds[MAX_DEV + 1];
    for (int i = 0; i < g_ndev; i++) { pfds[i].fd = g_evfd[i]; pfds[i].events = POLLIN; }
    pfds[g_ndev].fd = g_wakefd;
    pfds[g_ndev].events = POLLIN;

    long down_ms[MAX_CODE];   // press-down time, 0 = up
    int  longf[MAX_CODE];     // long already fired this hold
    int  tapc[MAX_CODE];      // taps counted in the current run
    long pend_ms[MAX_CODE];   // time of the last tap, awaiting another or the window expiry; 0 = none
    memset(down_ms, 0, sizeof(down_ms));
    memset(longf, 0, sizeof(longf));
    memset(tapc, 0, sizeof(tapc));
    memset(pend_ms, 0, sizeof(pend_ms));

    while (!g_stop) {
        long now = now_ms(), timeout = -1;
        for (int c = 0; c < MAX_CODE; c++) {
            if (down_ms[c] && !longf[c]) {                 // hold pending long
                long rem = long_ms - (now - down_ms[c]);
                if (rem < 0) rem = 0;
                if (timeout < 0 || rem < timeout) timeout = rem;
            }
            if (pend_ms[c]) {                              // tap run pending window expiry
                long rem = dbl_ms - (now - pend_ms[c]);
                if (rem < 0) rem = 0;
                if (timeout < 0 || rem < timeout) timeout = rem;
            }
        }
        int pn = poll(pfds, g_ndev + 1, (int)timeout);
        if (pn < 0) { if (errno == EINTR) continue; break; }
        if (g_stop) break;

        if (pfds[g_ndev].revents & POLLIN) {  // wake — drain
            uint64_t b;
            ssize_t r = read(g_wakefd, &b, sizeof(b));
            (void)r;
        }

        if (pn > 0) {
            for (int i = 0; i < g_ndev; i++) {
                if (!(pfds[i].revents & POLLIN)) continue;
                struct input_event ev;
                while (read(g_evfd[i], &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
                    if (ev.type != EV_KEY || !is_watched(ev.code)) continue;
                    if (ev.value == 1) {                      // DOWN
                        down_ms[ev.code] = now_ms();
                        longf[ev.code] = 0;
                    } else if (ev.value == 0) {               // UP
                        if (!longf[ev.code]) {                // a short tap
                            tapc[ev.code]++;
                            if (tapc[ev.code] >= max_taps(ev.code)) {   // reached this key's max → fire now
                                fire_tap(env, thiz, onKey, ev.code, tapc[ev.code]);
                                tapc[ev.code] = 0;
                                pend_ms[ev.code] = 0;
                            } else {                          // wait for another tap or the window
                                pend_ms[ev.code] = now_ms();
                            }
                        }
                        down_ms[ev.code] = 0;
                        longf[ev.code] = 0;
                    }
                    // value==2 (autorepeat): swallow
                }
            }
        }

        now = now_ms();
        for (int c = 0; c < MAX_CODE; c++) {
            if (down_ms[c] && !longf[c] && (now - down_ms[c]) >= long_ms) {
                longf[c] = 1;                                 // long → report, swallow; cancels any tap run
                tapc[c] = 0;
                pend_ms[c] = 0;
                (*env)->CallVoidMethod(env, thiz, onKey, (jint)c, TYPE_LONG);
            }
            if (pend_ms[c] && (now - pend_ms[c]) > dbl_ms) {  // no further tap → fire the run
                int count = tapc[c];
                tapc[c] = 0;
                pend_ms[c] = 0;
                fire_tap(env, thiz, onKey, c, count);
            }
        }
    }

    release_grabs();
    if (g_wakefd >= 0) { close(g_wakefd); g_wakefd = -1; }
    LOGI("grab loop ended");
}

JNIEXPORT void JNICALL
Java_com_opentasker_core_input_KeyGrabberService_nativeSetScreenOn(JNIEnv *env, jobject thiz, jboolean on) {
    (void)env;
    (void)thiz;
    g_screen_on = on ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_opentasker_core_input_KeyGrabberService_nativeStop(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    g_stop = 1;
    if (g_wakefd >= 0) {
        uint64_t one = 1;
        ssize_t w = write(g_wakefd, &one, sizeof(one));
        (void)w;
    }
}
