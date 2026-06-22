package com.opentasker.widget

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.actions.FlashOverlay
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TaskRunActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME)?.trim().orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SHORTCUT
        if (taskId < 0 && taskName.isEmpty()) {
            Toast.makeText(this, "Invalid task", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Immediate haptic confirmation that the tap registered (widget taps).
        if (source == SOURCE_WIDGET) vibrateTap()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val db = OpenTaskerApp_NoHilt.db
                // Resolve by name first (survives re-imports), else by id.
                val entity = if (taskName.isNotEmpty()) db.taskDao().getByName(taskName) else db.taskDao().getById(taskId)
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
                runOnUiThread {
                    // Themed (black/yellow) confirmation overlay — a system Toast can't be recoloured on
                    // a modern targetSdk, so reuse the Flash overlay styling.
                    showThemedFlash(if (result.report.success) task.name else "${task.name} ✕")
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Task run failed", e)
                runOnUiThread {
                    Toast.makeText(this@TaskRunActivity, "Task run failed", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun vibrateTap() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VibratorManager::class.java))?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun showThemedFlash(text: String) {
        val prefs = ThemeStore.state.value
        FlashOverlay.show(
            context = applicationContext,
            text = text,
            backgroundColor = prefs.flashBackground,
            textColor = prefs.flashText,
            borderColor = prefs.flashBorder,
            borderWidthDp = prefs.flashBorderWidthDp,
            cornerRadiusDp = prefs.flashCornerRadiusDp,
            textSizeSp = prefs.flashTextSizeSp,
            fontWeight = prefs.flashFontWeight,
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            xDp = 0,
            yDp = 64,
            longDuration = false,
        )
    }

    companion object {
        const val EXTRA_TASK_ID = "com.opentasker.widget.TASK_ID"
        const val EXTRA_TASK_NAME = "com.opentasker.widget.TASK_NAME"
        const val EXTRA_SOURCE = "com.opentasker.widget.SOURCE"
        const val SOURCE_WIDGET = "Widget"
        const val SOURCE_SHORTCUT = "Shortcut"
        private const val TAG = "TaskRunActivity"
    }
}
