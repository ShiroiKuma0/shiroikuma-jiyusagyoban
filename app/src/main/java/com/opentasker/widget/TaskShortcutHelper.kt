package com.opentasker.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.opentasker.app.R
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.model.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

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
    ) {
        /** The task name each shortcut in [update] is being written for. */
        val updatedNames: Map<Long, String>
            get() = update.mapNotNull { shortcut -> shortcut.madeFor?.let { shortcut.taskId to it } }.toMap()
    }

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
     * Brings the app's task shortcuts in line with [taskNames], every task's id and name, using
     * what the app carried from the last pass in [state]. See [plan] and [Reconciled] for how far
     * it got.
     */
    fun reconcile(
        context: Context,
        taskNames: Map<Long, String>,
        state: WatchState = WatchState(previous = null, afterRestore = false),
    ): Reconciled {
        var outcome = ReconcileOutcome.FAILED
        var heldBack = emptyMap<Long, String>()
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
            val plan = plan(states, taskNames, state.previous, state.afterRestore, state.heldBack)
            if (plan.disable.isNotEmpty()) {
                ShortcutManagerCompat.disableShortcuts(context, plan.disable, context.getString(R.string.shortcut_task_deleted))
            }
            if (plan.enable.isNotEmpty()) {
                ShortcutManagerCompat.enableShortcuts(context, shortcuts.filter { it.id in plan.enable })
            }
            outcome = ReconcileOutcome.CHECKED
            val updated = plan.update.isEmpty() || ShortcutManagerCompat.updateShortcuts(
                context,
                plan.update.map { taskShortcut(context, it.id, it.taskId, it.label, it.kind, it.madeFor) },
            )
            if (updated) {
                outcome = ReconcileOutcome.APPLIED
            } else {
                heldBack = plan.updatedNames
                AppLogger.warn(TAG, "Android held back renaming task shortcuts; the next change tries again")
            }
        }.onFailure { AppLogger.error(TAG, "Could not update task shortcuts", it) }
        return Reconciled(outcome, heldBack)
    }

    /** How far a [reconcile] got. */
    enum class ReconcileOutcome {
        /** The shortcuts couldn't be read, or turning some off or on failed. */
        FAILED,

        /** Shortcuts for missing tasks are off and returning ones are on, but Android held back the renames. */
        CHECKED,
        APPLIED,
    }

    /** How far a [reconcile] got, and the name of each task whose shortcuts Android didn't let it rename. */
    data class Reconciled(val outcome: ReconcileOutcome, val heldBack: Map<Long, String> = emptyMap())

    /**
     * What the app carries from one [reconcile] to the next. A restore's caution lasts until a
     * pass has turned off what it had to. [previous] is the task names the shortcuts last matched,
     * or the first ones seen until then, kept while renames are held back so an older shortcut can
     * still follow from its label. [heldBack] is the name each task had when Android held back
     * renaming its shortcuts, so one turned off before the rename got through still comes back
     * with its task.
     */
    data class WatchState(
        val previous: Map<Long, String>?,
        val afterRestore: Boolean,
        val heldBack: Map<Long, String> = emptyMap(),
    ) {
        fun after(outcome: ReconcileOutcome, names: Map<Long, String>, heldBack: Map<Long, String> = emptyMap()) = WatchState(
            previous = if (outcome == ReconcileOutcome.APPLIED) names else previous ?: names,
            afterRestore = afterRestore && outcome == ReconcileOutcome.FAILED,
            heldBack = if (outcome == ReconcileOutcome.FAILED) this.heldBack else this.heldBack - names.keys + heldBack,
        )
    }

    /**
     * Runs [pass] on every new task list, carrying [state] from one to the next for the life of
     * the process. A pass that failed outright is tried again every [retryDelayMs] until it gets
     * through or the list changes, so a restore's check doesn't wait for the next edit.
     */
    class Watcher(
        afterRestore: Boolean,
        private val retryDelayMs: Long = 60_000L,
        private val pass: (Map<Long, String>, WatchState) -> Reconciled,
    ) {
        var state = WatchState(previous = null, afterRestore = afterRestore)
            private set

        suspend fun watch(names: Flow<Map<Long, String>>) = names.collectLatest { current ->
            while (true) {
                val result = pass(current, state)
                state = state.after(result.outcome, current, result.heldBack)
                if (result.outcome != ReconcileOutcome.FAILED) break
                delay(retryDelayMs)
            }
        }
    }

    /**
     * A shortcut keeps the task id and label it was made with. One for a task that's gone is
     * disabled with a message instead of failing when tapped, and comes back when a task with that
     * id and the name it was made for returns, as an undo does, or with the name Android held back
     * renaming it to ([heldBack]). Android removes a disabled shortcut that's only dynamic, so only
     * a pinned one is left to come back.
     *
     * A rename gives the ones Pin made the task's new name, and a label picked through the publish
     * action stays. A restore swaps every task at once, so after one ([afterRestore]) a new name
     * under the same id may be another task, and the shortcut is turned off instead. The name the
     * task had when the restored tasks were first seen ([previousNames]) is the one that counts,
     * since a rename after that doesn't make it a different task.
     *
     * A shortcut from an older version doesn't say which kind it is or what it was made for, so
     * the first pass that finds its task records both: a `task_<id>` one whose label is the task's
     * name, or was its name in [previousNames], follows it from then on, and any other keeps its
     * label. Until then its label is the best guess at the name it was made for.
     */
    internal fun plan(
        shortcuts: List<ShortcutState>,
        taskNames: Map<Long, String>,
        previousNames: Map<Long, String>? = null,
        afterRestore: Boolean = false,
        heldBack: Map<Long, String> = emptyMap(),
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
            val madeFor = if (shortcut.kind == ShortcutKind.LEGACY) shortcut.label else shortcut.madeFor
            val sameTask = madeFor == (if (afterRestore) previousNames?.get(shortcut.taskId) ?: name else name)
            if (!shortcut.enabled) {
                if (!sameTask && heldBack[shortcut.taskId] != name) continue
                enable += shortcut.id
            } else if (afterRestore && !sameTask) {
                disable += shortcut.id
                continue
            }
            when (shortcut.kind) {
                ShortcutKind.FOLLOWS -> if (madeFor != name) update += shortcut.copy(label = name, madeFor = name)
                ShortcutKind.PICKED -> if (madeFor != name) update += shortcut.copy(madeFor = name)
                ShortcutKind.LEGACY -> update += if (
                    shortcut.id == defaultId(shortcut.taskId) &&
                    (shortcut.label == name || previousNames?.get(shortcut.taskId) == shortcut.label)
                ) {
                    shortcut.copy(label = name, kind = ShortcutKind.FOLLOWS, madeFor = name)
                } else {
                    shortcut.copy(kind = ShortcutKind.PICKED, madeFor = name)
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
