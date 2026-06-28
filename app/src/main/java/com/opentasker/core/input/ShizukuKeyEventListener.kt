package com.opentasker.core.input

import android.util.Log
import com.opentasker.core.contexts.HardwareKeyContextEvents
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches physical hardware keys by streaming `getevent -lq` through Shizuku (uid 2000), which reads
 * `/dev/input/event*` directly. Because that sits BELOW the framework's input policy, it sees presses
 * even while the screen is off and including the power button — what the accessibility `onKeyEvent`
 * path cannot do on EMUI. Detect-only: it does NOT consume the key (the volume still changes); blocking
 * would require a uinput-grab engine (deferred).
 *
 * DOWN/UP transitions are classified into [HardwareKeyContextEvents.PRESS_SHORT] /
 * [HardwareKeyContextEvents.PRESS_LONG] and published as `hardware_key` events. A long press fires as
 * soon as the key is held past the threshold (so feedback like a vibrate happens while still holding);
 * a short press fires on release if no long press fired. Double-press is reserved for a later phase.
 *
 * Config (super-globals set by the project's 01 task, all optional):
 *   - `%PKEY_ON`     "0"/"false" disables watching (default on)
 *   - `%PKEY_LONGMS` long-press threshold in ms (default [DEFAULT_LONG_MS])
 */
class ShizukuKeyEventListener {

    private var job: Job? = null
    @Volatile private var currentProcess: Process? = null
    private val states = ConcurrentHashMap<String, KeyState>()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { runLoop(scope) }
        Log.i(TAG, "Hardware-key listener started")
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { currentProcess?.destroy() }
        currentProcess = null
        states.values.forEach { it.longJob?.cancel() }
        states.clear()
        Log.i(TAG, "Hardware-key listener stopped")
    }

    private suspend fun runLoop(scope: CoroutineScope) {
        while (scope.isActive) {
            when {
                !enabled() -> delay(POLL_DISABLED_MS)
                !ShizukuShell.available() -> delay(POLL_NO_SHIZUKU_MS)
                else -> {
                    runCatching { streamOnce(scope) }
                        .onFailure { Log.w(TAG, "getevent stream ended: ${it.message}") }
                    if (scope.isActive) delay(RESPAWN_DELAY_MS)
                }
            }
        }
    }

    private fun streamOnce(scope: CoroutineScope) {
        val process = ShizukuShell.stream("getevent -lq")
        currentProcess = process
        val longMs = longPressMs()
        try {
            val reader = process.inputStream.bufferedReader()
            while (scope.isActive) {
                val line = reader.readLine() ?: break
                handleLine(scope, line, longMs)
            }
        } finally {
            runCatching { process.destroy() }
            if (currentProcess === process) currentProcess = null
        }
    }

    private fun handleLine(scope: CoroutineScope, line: String, longMs: Long) {
        // Runtime form (after `-q` skips the device dump): "/dev/input/event3: EV_KEY KEY_VOLUMEUP DOWN"
        val parts = line.trim().split(WHITESPACE)
        val i = parts.indexOf("EV_KEY")
        if (i < 0 || i + 2 >= parts.size) return
        val mapped = KEY_MAP[parts[i + 1]] ?: return
        when (parts[i + 2]) {
            "DOWN" -> onDown(scope, mapped.first, mapped.second, longMs)
            "UP" -> onUp(mapped.first, mapped.second)
            else -> Unit // autorepeat / other values: ignore
        }
    }

    private fun onDown(scope: CoroutineScope, keyName: String, keyCode: Int, longMs: Long) {
        val st = states.getOrPut(keyName) { KeyState() }
        synchronized(st) {
            if (st.down) return // dedupe repeats / multi-node DOWNs
            st.down = true
            st.longFired = false
            st.longJob?.cancel()
            st.longJob = scope.launch {
                delay(longMs)
                synchronized(st) {
                    if (st.down && !st.longFired) {
                        st.longFired = true
                        HardwareKeyContextEvents.publish(keyName, HardwareKeyContextEvents.PRESS_LONG, keyCode)
                        Log.i(TAG, "$keyName long press")
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
                Log.i(TAG, "$keyName short press")
            }
        }
    }

    private fun enabled(): Boolean {
        val v = PersistentGlobalScope.get(0L, "PKEY_ON")?.trim()?.lowercase() ?: return true
        return v != "0" && v != "false" && v != "off" && v != "no"
    }

    private fun longPressMs(): Long =
        PersistentGlobalScope.get(0L, "PKEY_LONGMS")?.trim()?.toLongOrNull()?.coerceIn(150L, 5000L)
            ?: DEFAULT_LONG_MS

    private class KeyState {
        var down = false
        var longFired = false
        var longJob: Job? = null
    }

    companion object {
        private const val TAG = "OpenTasker"
        private const val DEFAULT_LONG_MS = 500L
        private const val POLL_DISABLED_MS = 5_000L
        private const val POLL_NO_SHIZUKU_MS = 4_000L
        private const val RESPAWN_DELAY_MS = 1_500L
        private val WHITESPACE = Regex("\\s+")

        // evdev key label → (our key name, Android keycode)
        private val KEY_MAP = mapOf(
            "KEY_VOLUMEUP" to (HardwareKeyContextEvents.KEY_VOLUME_UP to 24),
            "KEY_VOLUMEDOWN" to (HardwareKeyContextEvents.KEY_VOLUME_DOWN to 25),
            "KEY_POWER" to (HardwareKeyContextEvents.KEY_POWER to 26),
        )
    }
}
