package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.engine.EngineHeartbeat
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.ExactAlarmSupport
import com.opentasker.core.scheduling.ExpectedTriggerLedger

/**
 * Receives app-owned time ticks and exact-alarm permission changes.
 */
class TimeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            TimeEventScheduler.ACTION_TIME_TICK,
            Intent.ACTION_TIME_TICK -> {
                // The single most important gate: this alarm is what resurrects a killed engine, so
                // without it an "Exit app fully" would last less than a minute. Break the chain here —
                // do not resurrect, and do not schedule the next tick.
                if (EngineShutdown.refuse(context, "per-minute tick")) {
                    runCatching { TimeEventScheduler(context).cancel() }
                    return
                }
                // Upstream's missed-trigger ledger: the tick that actually arrived has to be booked
                // as delivered here, or the watchdog re-files every healthy minute as overdue.
                runCatching { ExpectedTriggerLedger(context).markDelivered(System.currentTimeMillis()) }
                    .onFailure { AppLogger.error(TAG, "Could not record the delivered time tick", it) }
                try {
                    AppLogger.debug(TAG, "Time tick event")
                    // This exact alarm fires through Doze. If EMUI reaped the process, resurrect the service;
                    // if the process lives but the engine's tick went stale (its coroutines died), re-arm it.
                    when {
                        !AutomationService.isRunning -> {
                            EngineHeartbeat.markResurrect()
                            AutomationService.start(context)
                        }
                        EngineHeartbeat.isStale() -> AutomationService.rearm(context)
                    }
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Error processing time event", e)
                } finally {
                    TimeEventScheduler(context).scheduleNextMinute()
                }
            }
            ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION -> {
                try {
                    AppLogger.debug(TAG, "Exact alarm permission changed")
                    TimeEventScheduler(context).scheduleNextMinute()
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Error rescheduling time tick after exact alarm permission change", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "TimeEventReceiver"
    }
}

/**
 * Whether the platform refused a foreground-service start because the app is in the background.
 *
 * Matched by class name rather than `is`, so nothing has to load a class that does not exist below
 * API 31 and no version guard is needed. The whole cause chain is checked because the refusal
 * arrives wrapped when the start goes through a helper.
 */
internal fun isBackgroundStartRefusal(error: Throwable): Boolean =
    namesBackgroundStartRefusal(causeChainClassNames(error)) ||
        generateSequence(error, Throwable::cause)
            .take(MAX_CAUSE_DEPTH)
            .any { readsAsBackgroundStartRefusal(it.message) }

/**
 * The same refusal below API 31, where the dedicated exception class does not exist yet.
 *
 * `startForegroundService` from the background throws a plain `IllegalStateException` there, so a
 * class-name check alone left the retry storm live on Android 8 to 11. Matching a message is
 * fuzzy, and deliberately safe to get wrong in this direction: a false positive only skips a
 * five-second retry, and the ordinary minute tick has already been re-armed by then.
 */
internal fun readsAsBackgroundStartRefusal(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    return "not allowed to start service" in text ||
        ("background" in text && "start" in text && "service" in text)
}

/** The class names of a throwable and its causes, outermost first. */
internal fun causeChainClassNames(error: Throwable): List<String> =
    generateSequence(error, Throwable::cause)
        .take(MAX_CAUSE_DEPTH)
        .map { it.javaClass.name }
        .toList()

/**
 * Split from the walk so both halves are testable: the platform class is stubbed on the JVM test
 * classpath and cannot be instantiated, so the name comparison has to be reachable on its own.
 */
internal fun namesBackgroundStartRefusal(classNames: List<String>): Boolean =
    classNames.any { it == FOREGROUND_START_REFUSAL }

private const val FOREGROUND_START_REFUSAL = "android.app.ForegroundServiceStartNotAllowedException"

/** A cause chain is normally two or three deep; this only stops a cyclic one from hanging. */
private const val MAX_CAUSE_DEPTH = 8
