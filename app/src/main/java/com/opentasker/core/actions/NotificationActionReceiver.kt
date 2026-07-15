package com.opentasker.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.logSkippedRun
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.storage.recoveryMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFICATION_BUTTON) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 }
        val legacyTaskName = intent.getStringExtra(EXTRA_TASK_NAME)
        val reference = taskId?.let { NotificationTaskReference.Id(it) }
            ?: legacyTaskName?.let { NotificationTaskReference.LegacyName(it) }
            ?: return
        val buttonLabel = intent.getStringExtra(EXTRA_BUTTON_LABEL)
            ?: legacyTaskName
            ?: "Task ${taskId ?: "unknown"}"

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = OpenTaskerApp_NoHilt.db
                val entities = if (taskId != null) {
                    listOfNotNull(db.taskDao().getById(taskId))
                } else {
                    db.taskDao().getAll()
                }
                val resolution = NotificationTaskBindings.resolve(
                    reference = reference,
                    candidates = entities.map { NotificationTaskCandidate(it.id, it.name) },
                )
                if (resolution !is NotificationTaskResolution.Bound) {
                    AppLogger.warn(
                        TAG,
                        "Notification button '$buttonLabel' did not run: ${NotificationTaskBindings.failureMessage(resolution)}",
                    )
                    return@launch
                }
                val entity = entities.single { it.id == resolution.task.id }
                val decoded = entity.toDomainDecodeResult()
                val issue = decoded.issue
                if (issue != null) {
                    val reason = issue.recoveryMessage()
                    AppLogger.error(TAG, "Notification button '$buttonLabel' blocked: $reason")
                    logSkippedRun(
                        db = db,
                        task = decoded.value,
                        source = SOURCE,
                        reason = reason,
                        metadata = listOf("button=$buttonLabel"),
                    )
                    return@launch
                }
                val task = decoded.value
                val result = executeAndLogTask(
                    appContext = context.applicationContext,
                    db = db,
                    task = task,
                    source = SOURCE,
                    metadata = listOf("button=$buttonLabel"),
                )
                val status = if (result.report.success) "succeeded" else "failed"
                AppLogger.info(TAG, "Notification button '$buttonLabel' -> ${task.name} $status (${result.report.durationMs}ms)")
            } catch (e: Exception) {
                AppLogger.error(TAG, "Notification button '$buttonLabel' failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NOTIFICATION_BUTTON = "com.opentasker.action.NOTIFICATION_BUTTON"
        const val EXTRA_TASK_ID = "com.opentasker.extra.TASK_ID"
        /** Compatibility only for PendingIntents created before immutable ID bindings shipped. */
        const val EXTRA_TASK_NAME = "com.opentasker.extra.TASK_NAME"
        const val EXTRA_BUTTON_LABEL = "com.opentasker.extra.BUTTON_LABEL"
        const val SOURCE = "Notification action"
        private const val TAG = "OpenTasker"
    }
}
