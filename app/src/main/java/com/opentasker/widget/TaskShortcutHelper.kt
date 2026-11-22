package com.opentasker.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Task

object TaskShortcutHelper {

    enum class PublishMode { DYNAMIC, PINNED }

    data class PublishValidation(val error: String? = null) {
        val isValid: Boolean get() = error == null
    }

    /** What [reconcile] reads off one shortcut that opens a task. */
    internal data class ShortcutState(val id: String, val taskId: Long, val label: String, val enabled: Boolean)

    /** Shortcut ids to disable and enable, and `task_<id>` shortcuts to give their task's name. */
    internal data class ShortcutPlan(
        val disable: List<String>,
        val enable: List<String>,
        val relabel: List<ShortcutState>,
    )

    fun canPinShortcut(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun requestPinShortcut(context: Context, task: Task): Boolean {
        if (!canPinShortcut(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, taskShortcut(context, defaultId(task.id), task.id, task.name), null)
    }

    fun validatePublish(shortcutId: String, taskId: Long, label: String, mode: String): PublishValidation {
        if (shortcutId.isBlank() || shortcutId.length > 80) return PublishValidation("shortcut id must be 1..80 characters")
        if (shortcutId.any { it.isWhitespace() || it.isISOControl() }) return PublishValidation("shortcut id contains invalid characters")
        if (taskId <= 0L) return PublishValidation("task id must be positive")
        if (label.isBlank() || label.length > 80) return PublishValidation("label must be 1..80 characters")
        if (mode !in PublishMode.entries.map { it.name.lowercase() }) return PublishValidation("mode must be dynamic or pinned")
        return PublishValidation()
    }

    fun publishShortcut(
        context: Context,
        shortcutId: String,
        taskId: Long,
        label: String,
        mode: PublishMode,
    ): Boolean {
        val validation = validatePublish(shortcutId, taskId, label, mode.name.lowercase())
        if (!validation.isValid) return false
        val shortcut = taskShortcut(context, shortcutId, taskId, label)
        return when (mode) {
            PublishMode.DYNAMIC -> ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            PublishMode.PINNED -> canPinShortcut(context) &&
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }
    }

    /**
     * Brings the app's task shortcuts in line with [taskNames], every task's id and name.
     *
     * A shortcut keeps the task id and label it was made with. One for a task that's gone is
     * disabled with a message instead of failing when tapped, and enabled again if the task comes
     * back (an undo). The `task_<id>` shortcuts Add to home screen makes are labeled with the
     * task's name, so they follow a rename; a label picked through the publish action is left alone.
     */
    fun reconcile(context: Context, taskNames: Map<Long, String>) {
        runCatching {
            val shortcuts = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
            ).distinctBy { it.id }
            val states = shortcuts.mapNotNull { shortcut ->
                val taskId = shortcut.intent.getLongExtra(TaskRunActivity.EXTRA_TASK_ID, -1L)
                if (taskId <= 0L) return@mapNotNull null
                ShortcutState(shortcut.id, taskId, shortcut.shortLabel.toString(), shortcut.isEnabled)
            }
            val plan = plan(states, taskNames)
            if (plan.disable.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, plan.disable, context.getString(R.string.shortcut_task_deleted))
            }
            if (plan.enable.isNotEmpty()) {
                ShortcutManagerCompat.enableShortcuts(context, shortcuts.filter { it.id in plan.enable })
            }
            if (plan.relabel.isNotEmpty()) {
                val updated = ShortcutManagerCompat.updateShortcuts(
                    context,
                    plan.relabel.map { taskShortcut(context, it.id, it.taskId, it.label) },
                )
                if (!updated) AppLogger.warn(TAG, "Android held back renaming task shortcuts; the next change tries again")
            }
        }.onFailure { AppLogger.error(TAG, "Could not update task shortcuts", it) }
    }

    internal fun plan(shortcuts: List<ShortcutState>, taskNames: Map<Long, String>): ShortcutPlan {
        val disable = mutableListOf<String>()
        val enable = mutableListOf<String>()
        val relabel = mutableListOf<ShortcutState>()
        for (shortcut in shortcuts) {
            val name = taskNames[shortcut.taskId]
            if (name == null) {
                if (shortcut.enabled) disable += shortcut.id
                continue
            }
            if (!shortcut.enabled) enable += shortcut.id
            if (shortcut.id == defaultId(shortcut.taskId) && shortcut.label != name) relabel += shortcut.copy(label = name)
        }
        return ShortcutPlan(disable, enable, relabel)
    }

    private fun defaultId(taskId: Long) = "task_$taskId"

    private fun taskShortcut(context: Context, shortcutId: String, taskId: Long, label: String): ShortcutInfoCompat {
        val runIntent = Intent(context, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, taskId)
            putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_SHORTCUT)
        }
        return ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel("Run: $label")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(runIntent)
            .build()
    }

    private const val TAG = "TaskShortcuts"
}
