package com.opentasker.core.input

import android.content.Context
import android.util.Log

/**
 * Shizuku **UserService** — runs in a privileged process (uid 2000) that Shizuku spawns; the app reaches
 * it over binder via `Shizuku.bindUserService`. It loads the native grabber [libevgrab.so] **from the
 * APK** (no `/data/local/tmp` copy, no exec of a standalone binary) and reports presses back through
 * [IKeyGrabberCallback].
 *
 * Shizuku instantiates this class by name (via [Shizuku.UserServiceArgs] component) using the
 * single-[Context] constructor when present, else the no-arg one — so both are provided.
 */
class KeyGrabberService : IKeyGrabberService.Stub {

    /** Absolute path to the packaged .so, captured from the app's nativeLibraryDir when available. */
    private val nativeLibPath: String

    @Volatile private var callback: IKeyGrabberCallback? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var libLoaded = false

    @Suppress("unused")
    constructor() : super() {
        nativeLibPath = ""
    }

    @Suppress("unused")
    constructor(context: Context) : super() {
        nativeLibPath = runCatching { context.applicationInfo.nativeLibraryDir + "/" + LIB_FILE }
            .getOrDefault("")
    }

    override fun start(longPressMs: Long, doublePressMs: Long, evCodes: IntArray, doubleCodes: IntArray, tripleCodes: IntArray, cb: IKeyGrabberCallback): Int {
        stopInternal()
        return try {
            ensureLib()
            callback = cb
            val devs = nativeSetup(evCodes, doubleCodes, tripleCodes)
            if (devs <= 0) {
                callback = null
                Log.w(TAG, "no grabbable devices (setup=$devs)")
                return devs
            }
            val t = Thread {
                runCatching { nativeRun(longPressMs, doublePressMs) }
                    .onFailure { Log.e(TAG, "nativeRun failed", it) }
            }.apply { isDaemon = true; name = "keygrab" }
            worker = t
            t.start()
            Log.i(TAG, "grab started on $devs device(s)")
            devs
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            callback = null
            -1
        }
    }

    override fun stop() {
        stopInternal()
    }

    override fun setScreenOn(on: Boolean) {
        // Just flips a native flag; safe to call any time the lib is loaded (no effect if the loop isn't running).
        runCatching { if (libLoaded) nativeSetScreenOn(on) }
    }

    override fun destroy() {
        stopInternal()
        // daemon(false) → Shizuku kills this process after unbind; nothing else to clean up.
    }

    private fun stopInternal() {
        runCatching { if (libLoaded) nativeStop() }
        worker?.let { runCatching { it.join(1500) } }
        worker = null
        callback = null
    }

    /** Invoked from JNI on each gesture (type 0=short,1=long,2=double). Forwards over binder (oneway). */
    @Suppress("unused")
    fun onNativeKey(evCode: Int, pressType: Int) {
        runCatching { callback?.onKey(evCode, pressType) }
    }

    private fun ensureLib() {
        if (libLoaded) return
        if (nativeLibPath.isNotEmpty()) {
            runCatching { System.load(nativeLibPath) }
                .onFailure { runCatching { System.loadLibrary(LIB_NAME) }.getOrElse { throw it } }
        } else {
            System.loadLibrary(LIB_NAME)
        }
        libLoaded = true
    }

    /** Open + EVIOCGRAB the matching devices. Returns #grabbed (>0) or <=0 on failure. */
    private external fun nativeSetup(evCodes: IntArray, doubleCodes: IntArray, tripleCodes: IntArray): Int

    /** Blocking poll/classify loop; calls [onNativeKey]. Returns when [nativeStop] is called. */
    private external fun nativeRun(longPressMs: Long, doublePressMs: Long)

    /** Break the loop and release grabs. */
    private external fun nativeStop()

    /** Set the screen-on flag (gates single-tap re-injection). */
    private external fun nativeSetScreenOn(on: Boolean)

    companion object {
        private const val TAG = "OpenTaskerKeyGrab"
        private const val LIB_NAME = "evgrab"
        private const val LIB_FILE = "libevgrab.so"
    }
}
