package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.opentasker.automation.scheduler.TimeEventScheduler
import com.opentasker.core.scheduling.ExpectedTriggerLedger
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.scheduling.ExactAlarmSupport

internal enum class TimeEventAction {
    TIME_TICK,
    EXACT_ALARM_PERMISSION_CHANGED,
    IGNORE,
}

internal fun classifyTimeEventAction(action: String?): TimeEventAction = when (action) {
    TimeEventScheduler.ACTION_TIME_TICK,
    Intent.ACTION_TIME_TICK -> TimeEventAction.TIME_TICK
    ExactAlarmSupport.PERMISSION_STATE_CHANGED_ACTION -> TimeEventAction.EXACT_ALARM_PERMISSION_CHANGED
    else -> TimeEventAction.IGNORE
}

/**
 * Receives app-owned time ticks and exact-alarm permission changes.
 */
class TimeEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (classifyTimeEventAction(intent.action)) {
            TimeEventAction.TIME_TICK -> {
                ExpectedTriggerLedger(context).markDelivered(System.currentTimeMillis())
                val scheduler = TimeEventScheduler(context)
                val rearmed = runCatching { scheduler.scheduleNextMinute() }
                    .onFailure { AppLogger.error(TAG, "Could not re-arm the next time tick", it) }
                    .isSuccess
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, AutomationService::class.java)
                            .setAction(AutomationService.ACTION_TIME_TICK_TRIGGER),
                    )
                }.onSuccess {
                    AppLogger.debug(TAG, "Delivered alarm-backed time tick to the engine")
                }.onFailure { error ->
                    // Only skip the recovery when the ordinary tick was re-armed. If that failed
                    // too, the recovery alarm is the one thing left that can restart the chain,
                    // and skipping it here would leave time triggers dead until a reboot.
                    if (rearmed && isBackgroundStartRefusal(error)) {
                        // Android refused a foreground-service start from the background, and it
                        // will refuse the retry for the same reason. The recovery alarm is five
                        // seconds out on the same PendingIntent, so scheduling one here produced a
                        // wakeup every five seconds for as long as the condition lasted, burning
                        // the allow-while-idle quota that the ordinary minute tick depends on. The
                        // next scheduled tick is the recovery.
                        AppLogger.warn(
                            TAG,
                            "Android refused a background service start; waiting for the next tick",
                            error,
                        )
                    } else {
                        AppLogger.error(TAG, "Could not deliver alarm-backed time tick", error)
                        runCatching { scheduler.scheduleRecovery() }
                            .onFailure { AppLogger.error(TAG, "Could not schedule time-tick recovery", it) }
                    }
                }
            }
            TimeEventAction.EXACT_ALARM_PERMISSION_CHANGED -> {
                try {
                    AppLogger.debug(TAG, "Exact alarm permission changed")
                    TimeEventScheduler(context).scheduleNextMinute()
                } catch (e: Exception) {
                    AppLogger.error(TAG, "Error rescheduling time tick after exact alarm permission change", e)
                }
            }
            TimeEventAction.IGNORE -> Unit
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
