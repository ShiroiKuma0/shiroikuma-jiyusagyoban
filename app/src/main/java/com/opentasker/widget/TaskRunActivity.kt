package com.opentasker.widget

import com.sysadmindoc.ruledeck.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sysadmindoc.ruledeck.ui.components.AlertDialog
import com.sysadmindoc.ruledeck.ui.components.Button
import com.sysadmindoc.ruledeck.ui.components.OutlinedButton
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.widget.Toast
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.actions.FlashOverlay
import com.sysadmindoc.ruledeck.core.engine.EngineShutdown
import com.sysadmindoc.ruledeck.core.engine.ExecutionAdmissionRegistry
import com.sysadmindoc.ruledeck.core.engine.executeAndLogTask
import com.sysadmindoc.ruledeck.core.engine.logSkippedRun
import com.sysadmindoc.ruledeck.core.storage.recoveryMessage
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.sysadmindoc.ruledeck.core.logging.AppLogger

class TaskRunActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        val taskName = intent.getStringExtra(EXTRA_TASK_NAME)?.trim().orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SHORTCUT
        if (taskId < 0 && taskName.isEmpty()) {
            finishWithMessage(tr("Invalid task"))
            return
        }
        // A widget or launcher-shortcut tap must not resurrect an app the user has exited — say so
        // instead of running the task, which would drag the whole engine back up with it.
        if (EngineShutdown.refuse(this, "widget / shortcut tap")) {
            finishWithMessage(tr("白い熊 自由作業盤 は停止中 — open the app to start it"))
            return
        }

        // Two quick taps on a widget used to start two concurrent runs with real side effects.
        val runKey = taskName.ifEmpty { taskId.toString() }
        if (!RunningWidgetTasks.begin(runKey)) {
            finishWithMessage(getString(R.string.widget_task_already_running))
            return
        }

        // Immediate haptic confirmation that the tap registered (widget taps).
        if (source == SOURCE_WIDGET) vibrateTap()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val db = RuleDeckApp_NoHilt.db
                // Resolve by name first (survives re-imports), else by id.
                val entity = if (taskName.isNotEmpty()) db.taskDao().getByName(taskName) else db.taskDao().getById(taskId)
                if (entity == null) {
                    runOnUiThread { finishWithMessage(tr("Task not found")) }
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
                        finishWithMessage(tr("%1\$s is corrupt; restore a database backup", decoded.value.name))
                    }
                    return@launch
                }
                val task = decoded.value
                // The gate (ShortcutGate): this screen is open to every app, so a task runs from here
                // only if 白い熊 allowed it for shortcuts — or the tap came from one of our own widgets.
                // Anything else gets a question on the screen instead of a run.
                if (!ShortcutGate.isSealed(applicationContext, intent) && !ShortcutGate.allowed(applicationContext, task.name)) {
                    AppLogger.error(TAG, "not allowed for shortcuts, asking: ${task.name}")
                    runOnUiThread { askToAllow(task.name) }
                    return@launch
                }
                val result = executeAndLogTask(
                    appContext = applicationContext,
                    db = db,
                    task = task,
                    source = source,
                    // This IS a visible Activity — the tap opened it. Saying so is what lets a task run
                    // from a widget or shortcut play audio, dispatch a media key or change volume on
                    // Android 17+, which restricts those to a visible app or an eligible service.
                    //
                    // Upstream 0.2.86 finishes this activity before the run instead, so a long widget
                    // task cannot leave the launcher under an invisible, touch-consuming window. That
                    // is deliberately NOT taken here: this fork's widget tasks play audio (話す時計)
                    // and move the volume, and finishing first would make the claim above false.
                    visibleActivity = true,
                    // Without the shared controller this path gets a private in-memory one that
                    // admits even while the profile is saturated or its circuit breaker is open, so
                    // widget and shortcut taps ignored the limits the in-app Run button respects.
                    admissionController = ExecutionAdmissionRegistry.current(applicationContext),
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
                runOnUiThread { finishWithMessage(tr("Task run failed")) }
            } finally {
                RunningWidgetTasks.finish(runKey)
            }
        }
    }

    /**
     * The single exit. Every outcome — bad intent, stopped app, missing task, thrown task — leaves
     * through here, so none can toast without finishing (a transparent Activity left on screen) or
     * finish without saying why. The success path uses the themed flash instead.
     */
    /**
     * The one-time question for a task that is not allowed for shortcuts yet. "Allow" remembers it
     * and runs the request again; anything else closes without running. An app that fires this
     * screen for a task of its choosing gets no further than this dialog, which names the task.
     */
    private fun askToAllow(taskName: String) {
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            RuleDeckTheme(prefs = themePrefs) {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(tr("Start this task from a shortcut?")) },
                    text = {
                        Text(tr("“%1\$s” was asked to start from a shortcut or from another app, and is not allowed to yet. Allow it only if you tapped a shortcut of yours just now.", taskName))
                    },
                    confirmButton = {
                        Button(onClick = {
                            ShortcutGate.setAllowed(applicationContext, taskName, true)
                            recreate()
                        }) { Text(tr("Allow")) }
                    },
                    dismissButton = { OutlinedButton(onClick = { finish() }) { Text(tr("Cancel")) } },
                )
            }
        }
    }

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

    /** Two quick taps on a widget used to start two concurrent runs with real side effects. */
    private object RunningWidgetTasks {
        private val running = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        fun begin(key: String): Boolean = running.add(key)

        fun finish(key: String) {
            running.remove(key)
        }
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
