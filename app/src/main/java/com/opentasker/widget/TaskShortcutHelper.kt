package com.opentasker.widget

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.opentasker.app.R
import com.opentasker.core.model.Task

object TaskShortcutHelper {

    enum class PublishMode { DYNAMIC, PINNED }

    data class PublishValidation(val error: String? = null) {
        val isValid: Boolean get() = error == null
    }

    fun canPinShortcut(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun requestPinShortcut(context: Context, task: Task): Boolean {
        if (!canPinShortcut(context)) return false

        val runIntent = Intent(context, TaskRunActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(TaskRunActivity.EXTRA_TASK_ID, task.id)
            putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_SHORTCUT)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, "task_${task.id}")
            .setShortLabel(task.name)
            .setLongLabel("Run: ${task.name}")
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(runIntent)
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
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
