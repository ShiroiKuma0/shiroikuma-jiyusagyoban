package com.opentasker.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import com.opentasker.app.R
import androidx.core.content.pm.ShortcutManagerCompat
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.Task

object TaskShortcutHelper {

    enum class PublishMode { DYNAMIC, PINNED }

    data class PublishValidation(val error: String? = null) {
        val isValid: Boolean get() = error == null
    }

    /**
     * What [reconcile] reads off one shortcut that opens a task. [followsTask] is set for the
     * `task_<id>` shortcuts Pin makes, which carry the task's name, and not for one published with
     * a label of its own.
     */
    internal data class ShortcutState(
        val id: String,
        val taskId: Long,
        val label: String,
        val enabled: Boolean,
        val followsTask: Boolean,
    )

    /** Shortcut ids to disable and enable, and `task_<id>` shortcuts to give their task's name. */
    internal data class ShortcutPlan(
        val disable: List<String>,
        val enable: List<String>,
        val relabel: List<ShortcutState>,
    )

    fun canPinShortcut(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** A run-the-task Intent for [task], used by both pinned shortcuts and the launcher CREATE_SHORTCUT flow. */
    fun runIntent(context: Context, task: Task): Intent =
        Intent(context, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, task.id)
            // Name too, so the shortcut still resolves after a re-import re-ids the task.
            putExtra(TaskRunActivity.EXTRA_TASK_NAME, task.name)
            putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_SHORTCUT)
            // The app the task ultimately opens, so a launcher can point its "app info" / "uninstall"
            // shortcut actions at that app instead of at us. Best-effort snapshot at creation time;
            // GetTaskTargetPackageReceiver answers the live query for shortcuts that predate this extra.
            targetPackage(task)?.let { putExtra(TaskRunActivity.EXTRA_TARGET_PACKAGE, it) }
        }

    /**
     * The app [task] ultimately opens: the first `app.launch` action's literal package, falling back to
     * the first `app.unfreeze`'s (the unfreeze-then-launch tasks name the same app in both). `%var`
     * packages can't be resolved statically and are skipped. Null when the task targets no single app.
     */
    fun targetPackage(task: Task): String? {
        fun firstLiteralPackage(type: String): String? = task.actions.asSequence()
            .filter { it.type == type }
            .mapNotNull { it.args["package"]?.trim() }
            .firstOrNull { it.isNotEmpty() && '%' !in it }
        return firstLiteralPackage("app.launch") ?: firstLiteralPackage("app.unfreeze")
    }

    /** Build a shortcut for [task], baking in its saved icon (or the app icon when none is set). */
    fun buildShortcut(context: Context, task: Task): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "task_${task.id}")
            .setShortLabel(task.name)
            .setLongLabel("Run: ${task.name}")
            .setIcon(TaskIconStore.iconCompatFor(context, task))
            .setIntent(runIntent(context, task))
            .build()

    fun requestPinShortcut(context: Context, task: Task): Boolean {
        if (!canPinShortcut(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(context, buildShortcut(context, task), null)
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
        val shortcut = taskShortcut(context, shortcutId, taskId, label, labelPicked = true)
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
     * disabled with a message instead of failing when tapped. Android removes a disabled shortcut
     * that's only dynamic, so only a pinned one is left to come back. The ones Pin makes carry the
     * task's name, so they follow a rename, and come back when their task does (an undo puts it back
     * under the same id and name). A different task that later takes the id, as after a restore,
     * leaves it off. A label picked through the publish action is left alone.
     */
    fun reconcile(context: Context, taskNames: Map<Long, String>) {
        runCatching {
            val shortcuts = ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
            ).distinctBy { it.id }
            val states = shortcuts.mapNotNull { shortcut ->
                val intent = shortcut.intent
                val taskId = intent.getLongExtra(TaskRunActivity.EXTRA_TASK_ID, -1L)
                if (taskId <= 0L) return@mapNotNull null
                ShortcutState(
                    id = shortcut.id,
                    taskId = taskId,
                    label = shortcut.shortLabel.toString(),
                    enabled = shortcut.isEnabled,
                    followsTask = shortcut.id == defaultId(taskId) && !intent.getBooleanExtra(EXTRA_LABEL_PICKED, false),
                )
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
                    plan.relabel.map { taskShortcut(context, it.id, it.taskId, it.label, labelPicked = false) },
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
            if (!shortcut.enabled) {
                if (shortcut.followsTask && shortcut.label == name) enable += shortcut.id
                continue
            }
            if (shortcut.followsTask && shortcut.label != name) relabel += shortcut.copy(label = name)
        }
        return ShortcutPlan(disable, enable, relabel)
    }

    private fun defaultId(taskId: Long) = "task_$taskId"

    internal fun taskShortcut(
        context: Context,
        shortcutId: String,
        taskId: Long,
        label: String,
        labelPicked: Boolean,
    ): ShortcutInfoCompat {
        val runIntent = Intent(context, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, taskId)
            putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_SHORTCUT)
            if (labelPicked) putExtra(EXTRA_LABEL_PICKED, true)
        }
        return ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel("Run: $label")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(runIntent)
            .build()
    }

    private const val TAG = "TaskShortcuts"

    /** Set on a shortcut published with a label of its own, so a rename leaves that label alone. */
    private const val EXTRA_LABEL_PICKED = "com.opentasker.widget.LABEL_PICKED"
}
