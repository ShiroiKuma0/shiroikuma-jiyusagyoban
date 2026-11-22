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

    /** How a shortcut that opens a task treats the task's name. */
    internal enum class ShortcutKind {
        /** Made by Pin, so its label is the task's name and follows a rename. */
        FOLLOWS,

        /** Published with a label of its own, which a rename leaves alone. */
        PICKED,

        /** Made by a version from before shortcuts said which kind they are. */
        LEGACY,
    }

    /**
     * What [reconcile] reads off one shortcut that opens a task. [madeFor] is the task name it was
     * made or last updated for, so a task that comes back under the same id and name can be told
     * from a different one. Shortcuts from older versions don't carry it.
     */
    internal data class ShortcutState(
        val id: String,
        val taskId: Long,
        val label: String,
        val enabled: Boolean,
        val kind: ShortcutKind,
        val madeFor: String? = null,
    )

    /** Shortcut ids to disable and enable, and shortcuts to write again with a new label or task name. */
    internal data class ShortcutPlan(
        val disable: List<String>,
        val enable: List<String>,
        val update: List<ShortcutState>,
    )

    fun canPinShortcut(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun requestPinShortcut(context: Context, task: Task): Boolean {
        if (!canPinShortcut(context)) return false
        return ShortcutManagerCompat.requestPinShortcut(
            context,
            taskShortcut(context, defaultId(task.id), task.id, task.name, ShortcutKind.FOLLOWS, madeFor = task.name),
            null,
        )
    }

    fun validatePublish(shortcutId: String, taskId: Long, label: String, mode: String): PublishValidation {
        if (shortcutId.isBlank() || shortcutId.length > 80) return PublishValidation("shortcut id must be 1..80 characters")
        if (shortcutId.any { it.isWhitespace() || it.isISOControl() }) return PublishValidation("shortcut id contains invalid characters")
        if (taskId <= 0L) return PublishValidation("task id must be positive")
        if (label.isBlank() || label.length > 80) return PublishValidation("label must be 1..80 characters")
        if (mode !in PublishMode.entries.map { it.name.lowercase() }) return PublishValidation("mode must be dynamic or pinned")
        return PublishValidation()
    }

    /** [taskName] is the task's name now, when known, so the shortcut can come back with it after an undo. */
    fun publishShortcut(
        context: Context,
        shortcutId: String,
        taskId: Long,
        label: String,
        mode: PublishMode,
        taskName: String? = null,
    ): Boolean {
        val validation = validatePublish(shortcutId, taskId, label, mode.name.lowercase())
        if (!validation.isValid) return false
        val shortcut = taskShortcut(context, shortcutId, taskId, label, ShortcutKind.PICKED, madeFor = taskName)
        return when (mode) {
            PublishMode.DYNAMIC -> ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            PublishMode.PINNED -> canPinShortcut(context) &&
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }
    }

    /**
     * Brings the app's task shortcuts in line with [taskNames], every task's id and name.
     * [previousNames] is the list as this process last saw it, or null on the first call, and
     * [afterRestore] says a restore replaced the database at this start. See [plan].
     */
    fun reconcile(
        context: Context,
        taskNames: Map<Long, String>,
        previousNames: Map<Long, String>? = null,
        afterRestore: Boolean = false,
    ) {
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
                    kind = ShortcutKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) }
                        ?: ShortcutKind.LEGACY,
                    madeFor = intent.getStringExtra(EXTRA_MADE_FOR),
                )
            }
            val plan = plan(states, taskNames, previousNames, afterRestore)
            if (plan.disable.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, plan.disable, context.getString(R.string.shortcut_task_deleted))
            }
            if (plan.enable.isNotEmpty()) {
                ShortcutManagerCompat.enableShortcuts(context, shortcuts.filter { it.id in plan.enable })
            }
            if (plan.update.isNotEmpty()) {
                val updated = ShortcutManagerCompat.updateShortcuts(
                    context,
                    plan.update.map { taskShortcut(context, it.id, it.taskId, it.label, it.kind, it.madeFor) },
                )
                if (!updated) AppLogger.warn(TAG, "Android held back renaming task shortcuts; the next change tries again")
            }
        }.onFailure { AppLogger.error(TAG, "Could not update task shortcuts", it) }
    }

    /**
     * A shortcut keeps the task id and label it was made with. One for a task that's gone is
     * disabled with a message instead of failing when tapped, and comes back when a task with that
     * id and the name it was made for returns, as an undo does. Android removes a disabled shortcut
     * that's only dynamic, so only a pinned one is left to come back.
     *
     * A rename gives the ones Pin made the task's new name, and a label picked through the publish
     * action stays. A shortcut from an older version doesn't say which it is, so a `task_<id>` one
     * follows a rename only when its label was the task's old name in [previousNames]. A restore
     * swaps every task at once, so after one ([afterRestore]) a new name under the same id may be
     * another task, and the shortcut is turned off instead.
     */
    internal fun plan(
        shortcuts: List<ShortcutState>,
        taskNames: Map<Long, String>,
        previousNames: Map<Long, String>? = null,
        afterRestore: Boolean = false,
    ): ShortcutPlan {
        val disable = mutableListOf<String>()
        val enable = mutableListOf<String>()
        val update = mutableListOf<ShortcutState>()
        for (shortcut in shortcuts) {
            val name = taskNames[shortcut.taskId]
            if (name == null) {
                if (shortcut.enabled) disable += shortcut.id
                continue
            }
            // An older shortcut doesn't say what it was made for; unless it's a picked label, its label is the best guess.
            val madeFor = shortcut.madeFor ?: shortcut.label.takeIf { shortcut.kind != ShortcutKind.PICKED }
            if (madeFor == name) {
                if (!shortcut.enabled) enable += shortcut.id
                continue
            }
            if (!shortcut.enabled) continue
            if (afterRestore) {
                disable += shortcut.id
                continue
            }
            when (shortcut.kind) {
                ShortcutKind.FOLLOWS -> update += shortcut.copy(label = name, madeFor = name)
                ShortcutKind.PICKED -> update += shortcut.copy(madeFor = name)
                ShortcutKind.LEGACY -> if (
                    shortcut.id == defaultId(shortcut.taskId) && previousNames?.get(shortcut.taskId) == shortcut.label
                ) {
                    update += shortcut.copy(label = name, kind = ShortcutKind.FOLLOWS, madeFor = name)
                }
            }
        }
        return ShortcutPlan(disable, enable, update)
    }

    private fun defaultId(taskId: Long) = "task_$taskId"

    internal fun taskShortcut(
        context: Context,
        shortcutId: String,
        taskId: Long,
        label: String,
        kind: ShortcutKind,
        madeFor: String?,
    ): ShortcutInfoCompat {
        val runIntent = Intent(context, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, taskId)
            putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_SHORTCUT)
            if (kind != ShortcutKind.LEGACY) putExtra(EXTRA_KIND, kind.name)
            if (madeFor != null) putExtra(EXTRA_MADE_FOR, madeFor)
        }
        return ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel("Run: $label")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(runIntent)
            .build()
    }

    private const val TAG = "TaskShortcuts"

    /** A [ShortcutKind] name. Shortcuts from before this was written carry none. */
    private const val EXTRA_KIND = "com.opentasker.widget.SHORTCUT_KIND"

    /** The task name a shortcut was made or last updated for. */
    private const val EXTRA_MADE_FOR = "com.opentasker.widget.MADE_FOR"
}
