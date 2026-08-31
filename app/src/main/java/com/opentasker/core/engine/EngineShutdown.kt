package com.opentasker.core.engine

import android.content.Context
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.RunLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The "the user asked the app to stop" flag, and the gate every way back in has to pass.
 *
 * Stopping the engine service is not enough on its own to keep this app down: the per-minute exact
 * alarm resurrects it ([com.opentasker.automation.receiver.TimeEventReceiver]), and the accessibility
 * and notification-listener services are bound by the system, so the process is re-created within
 * seconds no matter how it is killed. "Exit app fully" therefore sets a **persisted** flag, and every
 * external entry point ([refuse]) declines while it is set instead of quietly waking everything up.
 *
 * Cleared by opening the app (MainActivity), and by a reboot when "Start engine on boot" is on
 * ([com.opentasker.core.storage.BootStartSettings]). Each refusal is written to the run log, so what
 * tried to wake the app while it was stopped is still readable afterwards.
 */
object EngineShutdown {
    private const val PREFS = "engine_shutdown"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_AT = "stopped_at"
    private const val TAG = "EngineShutdown"
    private const val REFUSAL_CAP = 30

    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _stopped = MutableStateFlow(false)

    /** True while the user has exited the app and nothing has re-opened it. Drives the Monitor banner. */
    val stopped: StateFlow<Boolean> = _stopped

    /** One refused wake-up: what tried to start the app while it was stopped, and when. */
    data class Refusal(val what: String, val atMs: Long)

    private val refusalRing = ArrayList<Refusal>()

    /** The most recent refused wake-ups, newest first (this process only — the run log keeps them all). */
    val refusals: List<Refusal> get() = synchronized(refusalRing) { refusalRing.toList() }

    /**
     * The flag, cached in memory after the first read.
     *
     * This is consulted on hot paths — most sharply [com.opentasker.core.accessibility
     * .ShiroiKumaAccessibilityService.onAccessibilityEvent], which the framework calls on the MAIN
     * thread for every window-state change on the device. Going to SharedPreferences there is disk I/O
     * in an accessibility callback, and a slow accessibility service is one the system is entitled to
     * tear down. Read the file once; every later answer comes from [cached].
     */
    fun isStopped(context: Context): Boolean {
        cached?.let { return it }
        return prefs(context).getBoolean(KEY_STOPPED, false).also { cached = it }
    }

    @Volatile private var cached: Boolean? = null

    /** When the app was stopped, or 0 if it is not stopped. */
    fun stoppedAtMs(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    fun load(context: Context) { _stopped.value = isStopped(context) }

    /** Mark the app stopped. Called at the very end of the shutdown sequence, after the teardown. */
    fun markStopped(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_STOPPED, true)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
        cached = true
        _stopped.value = true
        synchronized(refusalRing) { refusalRing.clear() }
        AppLogger.info(TAG, "App stopped by the user — external entry points will be refused")
    }

    /** Lift the stop. Called when the app is opened by hand, and on boot when boot-start is on. */
    fun clear(context: Context) {
        if (!isStopped(context)) return
        prefs(context).edit().putBoolean(KEY_STOPPED, false).putLong(KEY_AT, 0L).apply()
        cached = false
        _stopped.value = false
        AppLogger.info(TAG, "Stop lifted — the engine may start again")
    }

    /**
     * The gate. Returns **true when the caller must give up**: the user has exited the app and this
     * entry point may not bring it back. Records the attempt in the ring and in the run log so a
     * refused trigger is debuggable after the fact rather than silently missing.
     */
    fun refuse(context: Context, what: String): Boolean {
        if (!isStopped(context)) return false
        val now = System.currentTimeMillis()
        synchronized(refusalRing) {
            refusalRing.add(0, Refusal(what, now))
            while (refusalRing.size > REFUSAL_CAP) refusalRing.removeAt(refusalRing.lastIndex)
        }
        AppLogger.info(TAG, "Refused “$what” — the app is stopped")
        io.launch {
            runCatching {
                insertRunLog(
                    OpenTaskerApp_NoHilt.db,
                    RunLogEntry(
                        taskId = 0,
                        taskName = "停止中 — refused “$what”",
                        timestamp = now,
                        durationMs = 0,
                        success = false,
                        message = "The app was shut down from the overflow menu, so “$what” was declined " +
                            "instead of restarting the engine. Open 白い熊 自由作業盤 to start it again.",
                        source = "system",
                        sourceLabel = "Stopped",
                    ),
                )
            }.onFailure { AppLogger.warn(TAG, "Could not log the refusal of “$what”: ${it.message}") }
        }
        return true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
