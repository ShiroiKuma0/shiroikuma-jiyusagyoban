package com.opentasker.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The clipboard hand-over from the launcher-task generator to 白い熊 雷起動盤's 「貼り付け」 add item.
 *
 * A plain-text clip whose first line is exactly [HEADER] and whose every further line is one task NAME.
 * The launcher recognises the header, then asks [CreateTaskShortcutActivity] for each name in turn
 * (`EXTRA_TASK_NAME`) and files the shortcuts it gets back. Names, not ids: a re-import re-ids a task
 * but keeps its name, and the name is what our shortcuts resolve by anyway. Bump the `/1` only if the
 * line format itself changes; the launcher refuses a header it does not know.
 */
object TaskShortcutClip {
    const val HEADER = "shiroikuma.jiyusagyoban/task-shortcut/1"
    const val LABEL = "白い熊 自由作業盤 task shortcut"

    fun copy(context: Context, taskNames: List<String>) {
        if (taskNames.isEmpty()) return
        val text = (listOf(HEADER) + taskNames).joinToString("\n")
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(LABEL, text))
    }
}
