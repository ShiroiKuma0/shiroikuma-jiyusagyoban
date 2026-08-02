package com.opentasker.widget

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.Toast
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.actions.FlashOverlay
import com.opentasker.core.engine.EngineShutdown
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.logSkippedRun
import com.opentasker.core.storage.recoveryMessage
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.opentasker.core.logging.AppLogger

class TaskRunActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME)?.trim().orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SHORTCUT
        if (taskId < 0 && taskName.isEmpty()) {
            finishWithMessage("Invalid task")
            return
        }
        // A widget or launcher-shortcut tap must not resurrect an app the user has exited — say so
        // instead of running the task, which would drag the whole engine back up with it.
        if (EngineShutdown.refuse(this, "widget / shortcut tap")) {
            finishWithMessage("白い熊 自由作業盤 は停止中 — open the app to start it")
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
                    runOnUiThread { finishWithMessage("Task not found") }
                    return@launch
                }
                // Upstream's corrupt-row guard: a task whose stored JSON no longer decodes is logged
                // as skipped and named, instead of throwing on the way into the runner.
                val decoded = entity.toDomainDecodeResult()
                decoded.issue?.let { issue ->
                    val reason = issue.recoveryMessage()
                    AppLogger.error(TAG, reason)
                    logSkippedRun(db, decoded.value, source, reason)
                    runOnUiThread {
                        finishWithMessage("${decoded.value.name} is corrupt; restore a database backup")
                    }
                    return@launch
                }
                val task = decoded.value
                val result = executeAndLogTask(
                    appContext = applicationContext,
                    db = db,
                    task = task,
                    source = source,
                    // This IS a visible Activity — the tap opened it. Saying so is what lets a task run
                    // from a widget or shortcut play audio, dispatch a media key or change volume on
                    // Android 17+, which restricts those to a visible app or an eligible service.
                    visibleActivity = true,
                )
                runOnUiThread {
                    // Themed (black/yellow) confirmation overlay — a system Toast can't be recoloured on
                    // a modern targetSdk, so reuse the Flash overlay styling. A run the collision or
                    // admission gate turned away is marked ⏭, not ✕ — it never started.
                    showThemedFlash(
                        when {
                            result.skippedReason != null -> "${task.name} ⏭"
                            result.report.success -> task.name
                            else -> "${task.name} ✕"
                        },
                    )
                    finish()
                }
            } catch (e: Exception) {
                AppLogger.error(TAG, "Task run failed", e)
                runOnUiThread { finishWithMessage("Task run failed") }
            }
        }
    }

    /**
     * The single exit. Every outcome — bad intent, stopped app, missing task, thrown task — leaves
     * through here, so none can toast without finishing (a transparent Activity left on screen) or
     * finish without saying why. The success path uses the themed flash instead.
     */
    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
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
        /** Informational: the app the task ultimately opens (see [TaskShortcutHelper.targetPackage]). */
        const val EXTRA_TARGET_PACKAGE = "com.opentasker.widget.TARGET_PACKAGE"
        const val SOURCE_WIDGET = "Widget"
        const val SOURCE_SHORTCUT = "Shortcut"
        private const val TAG = "TaskRunActivity"
    }
}
