package com.opentasker.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.Task

object TaskShortcutHelper {

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
}
