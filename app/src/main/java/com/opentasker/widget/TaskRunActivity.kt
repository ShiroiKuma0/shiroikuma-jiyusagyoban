package com.opentasker.widget

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskRunActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SHORTCUT
        if (taskId < 0) {
            finishWithMessage("Invalid task")
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val message = try {
                val db = OpenTaskerApp_NoHilt.db
                val entity = db.taskDao().getById(taskId)
                if (entity == null) {
                    "Task not found"
                } else {
                    val task = entity.toDomain()
                    val result = executeAndLogTask(
                        appContext = applicationContext,
                        db = db,
                        task = task,
                        source = source,
                    )
                    val status = if (result.report.success) "succeeded" else "failed"
                    "${task.name} $status (${result.report.durationMs}ms)"
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Task run failed", e)
                "Task run failed"
            }
            runOnUiThread {
                finishWithMessage(message)
            }
        }
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_TASK_ID = "com.opentasker.widget.TASK_ID"
        const val EXTRA_SOURCE = "com.opentasker.widget.SOURCE"
        const val SOURCE_WIDGET = "Widget"
        const val SOURCE_SHORTCUT = "Shortcut"
        private const val TAG = "TaskRunActivity"
    }
}
