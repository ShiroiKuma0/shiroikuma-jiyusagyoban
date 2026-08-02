package com.opentasker.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.Task

object TaskShortcutHelper {

    enum class PublishMode { DYNAMIC, PINNED }

    data class PublishValidation(val error: String? = null) {
        val isValid: Boolean get() = error == null
    }

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
        val runIntent = Intent(context, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, taskId)
            putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_SHORTCUT)
        }
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel("Run: $label")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(runIntent)
            .build()
        return when (mode) {
            PublishMode.DYNAMIC -> ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            PublishMode.PINNED -> canPinShortcut(context) &&
                ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }
    }
}
