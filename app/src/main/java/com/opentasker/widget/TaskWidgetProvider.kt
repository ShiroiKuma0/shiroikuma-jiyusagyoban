package com.opentasker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.opentasker.app.R

class TaskWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (widgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (id in appWidgetIds) {
            editor.remove(keyTaskId(id))
            editor.remove(keyTaskName(id))
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "opentasker_widget_prefs"

        fun keyTaskId(widgetId: Int) = "widget_${widgetId}_task_id"
        fun keyTaskName(widgetId: Int) = "widget_${widgetId}_task_name"

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val taskId = prefs.getLong(keyTaskId(widgetId), -1L)
            val taskName = prefs.getString(keyTaskName(widgetId), "OpenTasker") ?: "OpenTasker"

            val views = RemoteViews(context.packageName, R.layout.widget_task)
            views.setTextViewText(R.id.widget_task_name, taskName)

            if (taskId >= 0) {
                val runIntent = Intent(context, TaskRunActivity::class.java).apply {
                    putExtra(TaskRunActivity.EXTRA_TASK_ID, taskId)
                    putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_WIDGET)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    widgetId,
                    runIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            manager.updateAppWidget(widgetId, views)
        }
    }
}
