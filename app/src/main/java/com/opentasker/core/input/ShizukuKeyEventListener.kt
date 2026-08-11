package com.opentasker.core.input

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
import com.opentasker.core.contexts.HardwareKeyContextEvents
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import com.opentasker.core.logging.AppLogger

/**
 * Watches physical hardware keys through Shizuku (uid 2000).
 *
 * Preferred path — **grab mode**: binds [KeyGrabberService] as a Shizuku **UserService**. Shizuku spawns
 * a privileged process that loads the native [libevgrab.so] **straight from the APK** (no
 * `/data/local/tmp` copy, no exec), `EVIOCGRAB`s the volume node(s), and streams classified presses back
 * over binder. Long presses are consumed (no volume change); short presses are re-injected so volume
 * still works. Works screen-off (it reads `/dev/input`, below the framework's input policy).
 *
 * Fallback path — **detect-only**: if the service can't grab (returns <=0) or can't bind, stream
 * `getevent -lq` and classify short/long ourselves. This detects but does NOT consume the keys — i.e. the
 * pre-grab behaviour, so we never end up worse than before. (`getevent` is a system binary; still no tmp.)
 *
 * Config (persisted globals, owned by the 物理鍵 project since the 2026-07-05 demotion): `%Pkey_On`
 * ("1"/"true" enables; absent = off), `%Pkey_Longms` / `%Pkey_Doublems` (threshold ms). The legacy
 * ALL-CAPS super-global names (`%PKEY_ON` …) are still honoured as a fallback.
 */
class ShizukuKeyEventListener {

    private var job: Job? = null
    private var killSwitchJob: Job? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var appScope: CoroutineScope? = null

    // grab mode (UserService) state
    @Volatile private var service: IKeyGrabberService? = null
    @Volatile private var bound = false
    @Volatile private var bindInFlight = false
    @Volatile private var grabUnavailable = false
    /** The stale-grabber sweep is a once-per-process job, not a per-bind one. */
    @Volatile private var reaped = false

    // detect-only fallback state
    @Volatile private var currentProcess: Process? = null
    private val states = ConcurrentHashMap<String, KeyState>()

    // Screen state, pushed to the grabber so it consumes single taps only when the screen is on (screen-off
    // single taps stay re-injected → system volume unchanged). Tracked via SCREEN_ON/OFF broadcasts.
    @Volatile private var screenOn = true
    private var screenReceiver: BroadcastReceiver? = null

    /**
     * Ringing state, pushed to the grabber the same way.
     *
     * A ringing call lights the screen, so the screen-on rule swallowed the single tap exactly when the
     * phone was ringing: the framework never saw the key, the dialer never silenced, and the volume panel
     * popped up over the call screen instead. While this is set the grabber re-injects the tap whatever the
     * screen is doing, and [callback] drops the matching event so no profile shows the panel.
     */
    @Volatile private var ringing = false

    private fun pushScreen(on: Boolean) {
        screenOn = on
        runCatching { service?.setScreenOn(on) }
    }

    private fun pushRinging(on: Boolean) {
        if (ringing == on) return
        ringing = on
        runCatching { service?.setRinging(on) }
        AppLogger.info(TAG, "ringing=$on (volume short press ${if (on) "re-injected, panel suppressed" else "back to the screen rule"})")
    }

    /**
     * Current ringing state, read fresh.
     *
     * [TelephonyManager.getCallState] is the answer when READ_PHONE_STATE is granted. When it is not —
     * the permission is optional here and the grabber must not depend on it — `AudioManager.getMode()`
     * reports `MODE_RINGTONE` while the ringer plays and needs no permission at all. It is the coarser
     * signal (it follows the ringer, not the call), which is precisely what this gate is about.
     */
    private fun readRinging(): Boolean {
        val ctx = appContext ?: return false
        if (hasPhoneStatePermission(ctx)) {
            val state = runCatching {
                ctx.getSystemService(TelephonyManager::class.java)?.callState
            }.getOrNull()
            if (state != null) return state == TelephonyManager.CALL_STATE_RINGING
        }
        return runCatching {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode == AudioManager.MODE_RINGTONE
        }.getOrDefault(false)
    }

    private fun hasPhoneStatePermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private val callback = object : IKeyGrabberCallback.Stub() {
        override fun onKey(evCode: Int, pressType: Int) {
            val mapped = EVCODE_MAP[evCode] ?: return
            val press = when (pressType) {
                3 -> HardwareKeyContextEvents.PRESS_TRIPLE
                2 -> HardwareKeyContextEvents.PRESS_DOUBLE
                1 -> HardwareKeyContextEvents.PRESS_LONG
                else -> HardwareKeyContextEvents.PRESS_SHORT
            }
            // While ringing the grabber re-injects this press for the framework, so publishing it as well
            // would run the profile too — and the volume panel would open over the call screen. Only the
            // short press is affected: long/double/triple are still consumed and still ours.
            if (ringing && press == HardwareKeyContextEvents.PRESS_SHORT && evCode in REINJECTED_CODES) {
                AppLogger.info(TAG, "${mapped.first} short press re-injected for the ringing call (not published)")
                return
            }
            HardwareKeyContextEvents.publish(mapped.first, press, mapped.second)
            AppLogger.info(TAG, "${mapped.first} $press press (grab)")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindInFlight = false
            val svc = IKeyGrabberService.Stub.asInterface(binder)
            if (svc == null || binder?.pingBinder() != true) {
                AppLogger.warn(TAG, "grabber bound but binder dead"); grabUnavailable = true; return
            }
            service = svc
            bound = true
            val devs = runCatching { svc.start(longPressMs(), doublePressMs(), WATCHED_CODES, DOUBLE_CODES, TRIPLE_CODES, callback) }
                .onFailure { AppLogger.warn(TAG, "grabber.start failed: ${it.message}") }
                .getOrDefault(-1)
            if (devs <= 0) {
                AppLogger.warn(TAG, "grab unavailable (start=$devs) — falling back to detect-only")
                grabUnavailable = true
                teardownBind()
            } else {
                // Seed the fresh service with the current gates; a rebind mid-call must not land ringing=false.
                runCatching { svc.setScreenOn(screenOn) }
                ringing = readRinging()
                runCatching { svc.setRinging(ringing) }
                AppLogger.info(TAG, "grab mode active on $devs device(s)")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            bindInFlight = false
            // Snappy restart: when the grabber dies while still enabled (e.g. 設定's kill on 71), rebind
            // immediately rather than waiting for the next poll — so 71 brings the grabber back at once.
            val s = appScope
            if (s != null && s.isActive && enabled() && shizukuReady()) {
                s.launch(Dispatchers.IO) { if (!bound && !bindInFlight) bindGrabber() }
            }
        }
    }

    fun start(context: Context, scope: CoroutineScope) {
        if (job != null) return
        appContext = context.applicationContext
        appScope = scope
        // Track screen on/off and forward to the grabber (gates single-tap consume vs re-inject).
        screenOn = (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
        ringing = readRinging()
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    // A ring lights the screen, so this is also the moment to re-read the call state: it
                    // catches the ring even when READ_PHONE_STATE is denied and no phone-state broadcast
                    // will ever arrive.
                    Intent.ACTION_SCREEN_ON -> { pushScreen(true); pushRinging(readRinging()) }
                    Intent.ACTION_SCREEN_OFF -> pushScreen(false)
                    TelephonyManager.ACTION_PHONE_STATE_CHANGED ->
                        pushRinging(
                            intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                                ?.let { it == TelephonyManager.EXTRA_STATE_RINGING }
                                ?: readRinging(),
                        )
                }
            }
        }.also { recv ->
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            }
            // Explicitly NOT_EXPORTED, like the call-state producer in StateSensorEvents. All three are
            // protected system broadcasts, so the flagless overload is still legal on this targetSdk — but
            // only for as long as every action in the filter stays protected, and the failure mode is a
            // SecurityException swallowed by runCatching that would take screen tracking down with it.
            val ctx = appContext
            if (ctx != null) {
                runCatching { ContextCompat.registerReceiver(ctx, recv, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
                    .onFailure { AppLogger.warn(TAG, "screen/phone-state receiver not registered: ${it.message}") }
            }
        }
        appContext?.let { ctx ->
            if (!hasPhoneStatePermission(ctx)) {
                AppLogger.warn(
                    TAG,
                    "READ_PHONE_STATE not granted — the ringing gate falls back to the audio mode, " +
                        "which is only re-read when the screen turns on",
                )
            }
        }
        job = scope.launch(Dispatchers.IO) { runLoop(scope) }
        killSwitchJob = scope.launch(Dispatchers.IO) {
            // If %PKEY_ON is turned off, release everything promptly (don't wait on the stream).
            while (scope.isActive) {
                delay(2_000)
                if (!enabled()) {
                    teardownBind()
                    runCatching { currentProcess?.destroy() }
                }
            }
        }
        AppLogger.info(TAG, "Hardware-key listener started")
    }

    fun stop() {
        job?.cancel(); job = null
        killSwitchJob?.cancel(); killSwitchJob = null
        screenReceiver?.let { recv -> runCatching { appContext?.unregisterReceiver(recv) } }; screenReceiver = null
        teardownBind()
        runCatching { currentProcess?.destroy() }; currentProcess = null
        states.values.forEach { it.longJob?.cancel() }
        states.clear()
        AppLogger.info(TAG, "Hardware-key listener stopped")
    }

    private suspend fun runLoop(scope: CoroutineScope) {
        while (scope.isActive) {
            when {
                !enabled() -> { teardownBind(); delay(POLL_DISABLED_MS) }
                !shizukuReady() -> { teardownBind(); delay(POLL_NO_SHIZUKU_MS) }
                grabUnavailable -> {
                    // Detect-only fallback: blocks while the stream is alive; respawns after it ends.
                    runCatching { streamGetevent(scope) }
                        .onFailure { AppLogger.warn(TAG, "getevent stream ended: ${it.message}") }
                    if (scope.isActive) delay(RESPAWN_DELAY_MS)
                }
                else -> {
                    if (!bound && !bindInFlight) bindGrabber()
                    delay(BOUND_IDLE_MS)
                }
            }
        }
    }

    private fun shizukuReady(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false) && ShizukuShell.hasPermission()

    // ---- grab mode (Shizuku UserService) ----

    private fun userServiceArgs(ctx: Context, version: Int = BuildConfig.VERSION_CODE): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, KeyGrabberService::class.java.name))
            .daemon(false)
            .processNameSuffix("keygrab")
            .debuggable(BuildConfig.DEBUG)
            .version(version)

    /**
     * Destroy grabber processes left over from earlier builds.
     *
     * Shizuku keys a UserService by (component, **version**), and we pass the app's versionCode so a new
     * build always gets fresh code rather than a stale process holding the old APK's `libevgrab.so`. The
     * cost is that after an update the previous version is a different identity — `unbindUserService`
     * with today's args can no longer name it, so it was orphaned and ran forever as a privileged `shell`
     * process. One per install accumulated (five were alive after a single morning's builds).
     *
     * So: remember every version we have ever bound, and unbind each non-current one by rebuilding ITS
     * args. Only the current version is kept in the record afterwards.
     */
    private fun reapStaleGrabbers(ctx: Context) {
        if (reaped) return
        reaped = true
        val prefs = ctx.getSharedPreferences(GRABBER_PREFS, Context.MODE_PRIVATE)
        val current = BuildConfig.VERSION_CODE
        val recorded = prefs.getString(GRABBER_KEY_VERSIONS, "").orEmpty()
            .split(",").mapNotNull { it.trim().toIntOrNull() }
        // The recorded versions, plus a bounded window of recent build numbers below this one. The window
        // exists because the versions leaked BEFORE this reaper shipped were never recorded anywhere —
        // and it self-heals a cleared record. Unbinding a version that has no live process just fails
        // harmlessly, so over-asking is safe; doing it once per process keeps the IPC cost trivial.
        val window = (current - REAP_WINDOW until current).toList()
        for (stale in (recorded + window).filter { it != current }.distinct().sortedDescending()) {
            // A THROWAWAY connection, never the live one: unbinding deregisters the ServiceConnection it
            // is handed, so reusing ours here would tear down the binding we are about to make.
            val scratch = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            runCatching { Shizuku.unbindUserService(userServiceArgs(ctx, stale), scratch, true) }
                .onFailure { AppLogger.warn(TAG, "Could not reap key-grabber $stale: ${it.message}") }
        }
        prefs.edit().putString(GRABBER_KEY_VERSIONS, current.toString()).apply()
        AppLogger.info(TAG, "Swept stale key-grabbers below version $current")
    }

    private fun bindGrabber() {
        val ctx = appContext ?: return
        reapStaleGrabbers(ctx)
        bindInFlight = true
        val ok = runCatching { Shizuku.bindUserService(userServiceArgs(ctx), connection); true }
            .onFailure { AppLogger.warn(TAG, "bindUserService failed: ${it.message}"); grabUnavailable = true }
            .getOrDefault(false)
        if (!ok) bindInFlight = false
    }

    private fun teardownBind() {
        val ctx = appContext
        if (bound || bindInFlight) {
            runCatching { service?.stop() }
            if (ctx != null) runCatching { Shizuku.unbindUserService(userServiceArgs(ctx), connection, true) }
        }
        service = null
        bound = false
        bindInFlight = false
    }

    // ---- detect-only fallback (getevent) ----

    private fun streamGetevent(scope: CoroutineScope) {
        val process = ShizukuShell.stream("getevent -lq")
        currentProcess = process
        val longMs = longPressMs()
        try {
            val reader = process.inputStream.bufferedReader()
            while (scope.isActive) {
                val line = reader.readLine() ?: break
                handleGeteventLine(scope, line, longMs)
            }
        } finally {
            runCatching { process.destroy() }
            if (currentProcess === process) currentProcess = null
        }
    }

    private fun handleGeteventLine(scope: CoroutineScope, line: String, longMs: Long) {
        val parts = line.trim().split(WHITESPACE)
        val i = parts.indexOf("EV_KEY")
        if (i < 0 || i + 2 >= parts.size) return
        val mapped = KEY_NAME_MAP[parts[i + 1]] ?: return
        when (parts[i + 2]) {
            "DOWN" -> onDown(scope, mapped.first, mapped.second, longMs)
            "UP" -> onUp(mapped.first, mapped.second)
            else -> Unit
        }
    }

    private fun onDown(scope: CoroutineScope, keyName: String, keyCode: Int, longMs: Long) {
        val st = states.getOrPut(keyName) { KeyState() }
        synchronized(st) {
            if (st.down) return
            st.down = true
            st.longFired = false
            st.longJob?.cancel()
            st.longJob = scope.launch {
                delay(longMs)
                synchronized(st) {
                    if (st.down && !st.longFired) {
                        st.longFired = true
                        HardwareKeyContextEvents.publish(keyName, HardwareKeyContextEvents.PRESS_LONG, keyCode)
                    }
                }
            }
        }
    }

    private fun onUp(keyName: String, keyCode: Int) {
        val st = states[keyName] ?: return
        synchronized(st) {
            if (!st.down) return
            st.down = false
            st.longJob?.cancel()
            st.longJob = null
            if (!st.longFired) {
                HardwareKeyContextEvents.publish(keyName, HardwareKeyContextEvents.PRESS_SHORT, keyCode)
            }
        }
    }

    // ---- config ----

    // The listener runs outside any task, so it can't know the 物理鍵 project's id — resolve the
    // MixedCase project-global by name across every bucket (snapshotAll), like widget rendering does.
    // Falls back to the pre-demotion ALL-CAPS super-global so either naming keeps working.
    private fun config(name: String, legacyName: String): String? =
        PersistentGlobalScope.snapshotAll()[name] ?: PersistentGlobalScope.get(0L, legacyName)

    // Default OFF: the grabber only runs when %Pkey_On is explicitly on (set by the 物理鍵 設定/起動 task),
    // so opening the app never starts grabbing on its own.
    private fun enabled(): Boolean {
        val v = config("Pkey_On", "PKEY_ON")?.trim()?.lowercase() ?: return false
        return v == "1" || v == "true" || v == "on" || v == "yes"
    }

    private fun longPressMs(): Long =
        config("Pkey_Longms", "PKEY_LONGMS")?.trim()?.toLongOrNull()?.coerceIn(150L, 5000L)
            ?: DEFAULT_LONG_MS

    // Double-tap window for double-enabled keys. Also the added latency a SINGLE short on those keys waits
    // before firing (must hold to see if a 2nd tap comes). Vol-Up isn't double-enabled, so it stays instant.
    // Floor = the Android system minimum gap for a double-tap (ViewConfiguration.getDoubleTapMinTime() = 40ms);
    // below ~120–150ms a deliberate double usually can't be tapped fast enough to register. Ceiling 10s is a
    // sanity bound only (large values just make a single press wait that long).
    private fun doublePressMs(): Long =
        config("Pkey_Doublems", "PKEY_DOUBLEMS")?.trim()?.toLongOrNull()?.coerceIn(DOUBLE_MIN_MS, 10_000L)
            ?: DEFAULT_DOUBLE_MS

    private class KeyState {
        var down = false
        var longFired = false
        var longJob: Job? = null
    }

    companion object {
        private const val TAG = "OpenTasker"
        private const val DEFAULT_LONG_MS = 500L
        private const val DEFAULT_DOUBLE_MS = 120L
        private const val DOUBLE_MIN_MS = 40L // ViewConfiguration.getDoubleTapMinTime() — the system floor
        private const val POLL_DISABLED_MS = 5_000L
        private const val POLL_NO_SHIZUKU_MS = 4_000L
        private const val RESPAWN_DELAY_MS = 1_500L
        private const val BOUND_IDLE_MS = 3_000L
        private const val GRABBER_PREFS = "keygrab_versions"
        private const val GRABBER_KEY_VERSIONS = "versions"
        /** How many build numbers below the current one the one-off sweep reaches back. */
        private const val REAP_WINDOW = 30
        private val WHITESPACE = Regex("\\s+")

        // Volume keys only (114 vol-down, 115 vol-up). Power is intentionally NOT grabbed: an injected
        // POWER won't toggle the screen on this Huawei, so consuming it would deaden the power button.
        private val WATCHED_CODES = intArrayOf(114, 115)

        // The codes the native side can re-inject (android_keycode in evgrab.c maps exactly these). Only
        // these are dropped by the ringing gate — a key we cannot hand back must never be silently eaten.
        private val REINJECTED_CODES = setOf(114, 115)

        // Multi-tap keys. Both volume keys get double (vol-down→camera, vol-up→media play/pause); vol-down
        // also gets triple (speak time). A single short on a multi-tap key waits PKEY_DOUBLEMS before firing
        // (to disambiguate); a tap fires immediately once the key's max count is reached.
        private val DOUBLE_CODES = intArrayOf(114, 115)
        private val TRIPLE_CODES = intArrayOf(114)

        // evdev code → (our key name, Android keycode), for the grabber callback.
        private val EVCODE_MAP = mapOf(
            114 to (HardwareKeyContextEvents.KEY_VOLUME_DOWN to 25),
            115 to (HardwareKeyContextEvents.KEY_VOLUME_UP to 24),
            116 to (HardwareKeyContextEvents.KEY_POWER to 26),
        )

        // evdev label → (our key name, Android keycode), for the getevent fallback.
        private val KEY_NAME_MAP = mapOf(
            "KEY_VOLUMEUP" to (HardwareKeyContextEvents.KEY_VOLUME_UP to 24),
            "KEY_VOLUMEDOWN" to (HardwareKeyContextEvents.KEY_VOLUME_DOWN to 25),
            "KEY_POWER" to (HardwareKeyContextEvents.KEY_POWER to 26),
        )
    }
}
