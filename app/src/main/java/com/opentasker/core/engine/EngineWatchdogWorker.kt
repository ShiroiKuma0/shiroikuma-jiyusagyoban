package com.opentasker.core.engine

import com.sysadmindoc.ruledeck.core.engine.*
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.sysadmindoc.ruledeck.automation.scheduler.TimeEventScheduler
import com.sysadmindoc.ruledeck.core.diagnostics.EngineHealthReader
import com.sysadmindoc.ruledeck.core.storage.ScheduledWorkerId
import com.sysadmindoc.ruledeck.core.storage.recordingOutcome
import com.sysadmindoc.ruledeck.core.logging.AppLogger
import com.sysadmindoc.ruledeck.core.model.RunLogEntry
import com.sysadmindoc.ruledeck.core.scheduling.ExpectedTriggerLedger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Periodic backstop for a killed/timed-out engine and a dropped self-rescheduling time alarm. */
class EngineWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        recordingOutcome(ScheduledWorkerId.ENGINE_WATCHDOG) { runWork() }

    private suspend fun runWork(): Result {
        val now = System.currentTimeMillis()
        val scheduler = TimeEventScheduler(applicationContext)
        val heartbeat = EngineHeartbeatStore(applicationContext).read()
        return runCatching {
            val expectedTriggers = ExpectedTriggerLedger(applicationContext)
            expectedTriggers.consumeMissed(now)?.let { missed ->
                val consequence = EngineHealthReader.standbyConsequence(applicationContext)
                if (!recordMissedTrigger(missed, consequence)) {
                    expectedTriggers.requeue(missed)
                }
            }
            if (heartbeat.needsRecovery(now)) {
                scheduler.scheduleRecovery(now)
                AppLogger.warn(TAG, "Engine heartbeat stale; scheduled an alarm-backed restart")
            } else {
                scheduler.scheduleNextMinute(now)
                AppLogger.debug(TAG, "Engine heartbeat healthy; verified time alarm")
            }
            Result.success()
        }.getOrElse { error ->
            AppLogger.error(TAG, "Engine watchdog could not re-arm time delivery", error)
            Result.retry()
        }
    }

    private suspend fun recordMissedTrigger(
        missed: com.sysadmindoc.ruledeck.core.scheduling.MissedTrigger,
        standbyConsequence: String,
    ): Boolean {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        val entry = RunLogEntry(
            taskId = 0L,
            taskName = "Scheduled trigger",
            timestamp = missed.detectedAtMillis,
            durationMs = missed.delayMillis,
            success = false,
            message = buildString {
                appendLine("Source: Scheduled trigger")
                appendLine("Decision: Skipped")
                appendLine("Reason: Missed scheduled trigger")
                appendLine("Terminal reason: ${ExecutionTerminalReason(ExecutionTerminalReasonCode.MISSED_TRIGGER).render()}")
                appendLine("Expected fire: ${dateFormat.format(Date(missed.expectedAtMillis))}")
                appendLine("Trigger kind: ${missed.kind.wireValue}")
                appendLine("Delay: ${EngineHealthReader.ageLabel(missed.delayMillis)}")
                appendLine("Standby consequence: $standbyConsequence")
                append("Remediation: Open Setup, exempt RuleDeck from battery optimization, and allow exact alarms.")
            },
            source = RunLogSource.SCHEDULER,
            sourceLabel = RunLogSource.MISSED_TRIGGER,
        )
        return insertRunLog(RuleDeckApp_NoHilt.db, entry)
    }

    companion object {
        private const val TAG = "EngineWatchdogWorker"
        internal const val WORK_NAME = "engine_watchdog"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<EngineWatchdogWorker>(
                15,
                TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
