package com.opentasker.core.actions

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOTIFICATION_BUTTON) return
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME) ?: return
        val buttonLabel = intent.getStringExtra(EXTRA_BUTTON_LABEL) ?: taskName

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = OpenTaskerApp_NoHilt.db
                val entity = db.taskDao().getByName(taskName)
                if (entity == null) {
                    AppLogger.warn(TAG, "Notification button '$buttonLabel' task '$taskName' not found")
                    return@launch
                }
                val task = entity.toDomain()
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
        const val EXTRA_TASK_NAME = "com.opentasker.extra.TASK_NAME"
        const val EXTRA_BUTTON_LABEL = "com.opentasker.extra.BUTTON_LABEL"
        const val SOURCE = "Notification action"
        private const val TAG = "OpenTasker"
    }
}
