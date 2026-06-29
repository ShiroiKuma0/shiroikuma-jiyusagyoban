package com.opentasker.core.input

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
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
 * Config (super-globals): `%PKEY_ON` ("1"/"true" enables; absent = off), `%PKEY_LONGMS` (threshold ms).
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

    // detect-only fallback state
    @Volatile private var currentProcess: Process? = null
    private val states = ConcurrentHashMap<String, KeyState>()

    // Screen state, pushed to the grabber so it consumes single taps only when the screen is on (screen-off
    // single taps stay re-injected → system volume unchanged). Tracked via SCREEN_ON/OFF broadcasts.
    @Volatile private var screenOn = true
    private var screenReceiver: BroadcastReceiver? = null

    private fun pushScreen(on: Boolean) {
        screenOn = on
        runCatching { service?.setScreenOn(on) }
    }

    private val callback = object : IKeyGrabberCallback.Stub() {
        override fun onKey(evCode: Int, pressType: Int) {
            val mapped = EVCODE_MAP[evCode] ?: return
            val press = when (pressType) {
                3 -> HardwareKeyContextEvents.PRESS_TRIPLE
                2 -> HardwareKeyContextEvents.PRESS_DOUBLE
                1 -> HardwareKeyContextEvents.PRESS_LONG
                else -> HardwareKeyContextEvents.PRESS_SHORT
            }
            HardwareKeyContextEvents.publish(mapped.first, press, mapped.second)
            Log.i(TAG, "${mapped.first} $press press (grab)")
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bindInFlight = false
            val svc = IKeyGrabberService.Stub.asInterface(binder)
            if (svc == null || binder?.pingBinder() != true) {
                Log.w(TAG, "grabber bound but binder dead"); grabUnavailable = true; return
            }
            service = svc
            bound = true
            val devs = runCatching { svc.start(longPressMs(), doublePressMs(), WATCHED_CODES, DOUBLE_CODES, TRIPLE_CODES, callback) }
                .onFailure { Log.w(TAG, "grabber.start failed: ${it.message}") }
                .getOrDefault(-1)
            if (devs <= 0) {
                Log.w(TAG, "grab unavailable (start=$devs) — falling back to detect-only")
                grabUnavailable = true
                teardownBind()
            } else {
                runCatching { svc.setScreenOn(screenOn) }  // seed the fresh service with the current screen state
                Log.i(TAG, "grab mode active on $devs device(s)")
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
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> pushScreen(true)
                    Intent.ACTION_SCREEN_OFF -> pushScreen(false)
                }
            }
        }.also { recv ->
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching { appContext?.registerReceiver(recv, filter) }
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
        Log.i(TAG, "Hardware-key listener started")
    }

    fun stop() {
        job?.cancel(); job = null
        killSwitchJob?.cancel(); killSwitchJob = null
        screenReceiver?.let { recv -> runCatching { appContext?.unregisterReceiver(recv) } }; screenReceiver = null
        teardownBind()
        runCatching { currentProcess?.destroy() }; currentProcess = null
        states.values.forEach { it.longJob?.cancel() }
        states.clear()
        Log.i(TAG, "Hardware-key listener stopped")
    }

    private suspend fun runLoop(scope: CoroutineScope) {
        while (scope.isActive) {
            when {
                !enabled() -> { teardownBind(); delay(POLL_DISABLED_MS) }
                !shizukuReady() -> { teardownBind(); delay(POLL_NO_SHIZUKU_MS) }
                grabUnavailable -> {
                    // Detect-only fallback: blocks while the stream is alive; respawns after it ends.
                    runCatching { streamGetevent(scope) }
                        .onFailure { Log.w(TAG, "getevent stream ended: ${it.message}") }
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

    private fun userServiceArgs(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, KeyGrabberService::class.java.name))
            .daemon(false)
            .processNameSuffix("keygrab")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private fun bindGrabber() {
        val ctx = appContext ?: return
        bindInFlight = true
        val ok = runCatching { Shizuku.bindUserService(userServiceArgs(ctx), connection); true }
            .onFailure { Log.w(TAG, "bindUserService failed: ${it.message}"); grabUnavailable = true }
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

    // Default OFF: the grabber only runs when %PKEY_ON is explicitly on (set by the 物理鍵 設定/起動 task),
    // so opening the app never starts grabbing on its own.
    private fun enabled(): Boolean {
        val v = PersistentGlobalScope.get(0L, "PKEY_ON")?.trim()?.lowercase() ?: return false
        return v == "1" || v == "true" || v == "on" || v == "yes"
    }

    private fun longPressMs(): Long =
        PersistentGlobalScope.get(0L, "PKEY_LONGMS")?.trim()?.toLongOrNull()?.coerceIn(150L, 5000L)
            ?: DEFAULT_LONG_MS

    // Double-tap window for double-enabled keys. Also the added latency a SINGLE short on those keys waits
    // before firing (must hold to see if a 2nd tap comes). Vol-Up isn't double-enabled, so it stays instant.
    // Floor = the Android system minimum gap for a double-tap (ViewConfiguration.getDoubleTapMinTime() = 40ms);
    // below ~120–150ms a deliberate double usually can't be tapped fast enough to register. Ceiling 10s is a
    // sanity bound only (large values just make a single press wait that long).
    private fun doublePressMs(): Long =
        PersistentGlobalScope.get(0L, "PKEY_DOUBLEMS")?.trim()?.toLongOrNull()?.coerceIn(DOUBLE_MIN_MS, 10_000L)
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
        private val WHITESPACE = Regex("\\s+")

        // Volume keys only (114 vol-down, 115 vol-up). Power is intentionally NOT grabbed: an injected
        // POWER won't toggle the screen on this Huawei, so consuming it would deaden the power button.
        private val WATCHED_CODES = intArrayOf(114, 115)

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
