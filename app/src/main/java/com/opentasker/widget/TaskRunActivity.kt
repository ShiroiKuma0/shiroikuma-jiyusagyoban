package com.opentasker.widget

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
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
            Toast.makeText(this, "Invalid task", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val db = OpenTaskerApp_NoHilt.db
            val entity = db.taskDao().getById(taskId)
            if (entity == null) {
                runOnUiThread {
                    Toast.makeText(this@TaskRunActivity, "Task not found", Toast.LENGTH_SHORT).show()
                    finish()
                }
                return@launch
            }
            val task = entity.toDomain()
            val result = executeAndLogTask(
                appContext = applicationContext,
                db = db,
                task = task,
                source = source,
            )
            val status = if (result.report.success) "succeeded" else "failed"
            runOnUiThread {
                Toast.makeText(
                    this@TaskRunActivity,
                    "${task.name} $status (${result.report.durationMs}ms)",
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "com.opentasker.widget.TASK_ID"
        const val EXTRA_SOURCE = "com.opentasker.widget.SOURCE"
        const val SOURCE_WIDGET = "Widget"
        const val SOURCE_SHORTCUT = "Shortcut"
    }
}
