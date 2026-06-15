package com.opentasker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import com.opentasker.app.R
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.engine.variables.InMemoryGlobalScope
import com.opentasker.core.engine.variables.PersistentGlobalScope
import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.core.widget.WidgetRenderer
import com.opentasker.ui.theme.ThemeStore

/**
 * A home-screen widget whose content is a [com.opentasker.core.widget.WidgetNode] layout rendered to
 * a Bitmap (so it can carry custom fonts / arbitrary styling). The layout is set when the widget is
 * placed, and updated live from a task via the `Set Widget` action ([updateByName]).
 */
class StyledWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { renderWidget(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        renderWidget(context, manager, id)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { StyledWidgetStore.clear(context, it) }
    }

    companion object {
        private const val MAX_PX = 1440

        fun renderWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val dm = context.resources.displayMetrics
            val opts = manager.getAppWidgetOptions(id)
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(40)
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120).coerceAtLeast(40)
            val wPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wDp.toFloat(), dm).toInt().coerceIn(1, MAX_PX)
            val hPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, hDp.toFloat(), dm).toInt().coerceIn(1, MAX_PX)

            // Pull model: a template-bound slot renders its template, expanded against the current
            // persisted globals (`%DT_*` etc.) right here — no task run needed. Otherwise the static layout.
            val templateName = StyledWidgetStore.getTemplate(context, id)
            val layoutJson = if (templateName != null) {
                TemplateStore.get(templateName)?.let { expandGlobals(it) }
            } else {
                StyledWidgetStore.getLayout(context, id)
            }
            val node = layoutJson?.let { WidgetLayoutCodec.decode(it) } ?: WidgetLayoutCodec.PLACEHOLDER
            val renderer = WidgetRenderer(dm.density) { ThemeStore.typeface(it) }
            val bitmap = runCatching { renderer.render(node, wPx, hPx) }.getOrNull()

            val views = RemoteViews(context.packageName, R.layout.styled_widget)
            if (bitmap != null) views.setImageViewBitmap(R.id.styled_widget_image, bitmap)

            val tapTask = StyledWidgetStore.getTapTask(context, id)
            if (tapTask > 0) {
                val intent = Intent(context, TaskRunActivity::class.java).apply {
                    putExtra(TaskRunActivity.EXTRA_TASK_ID, tapTask)
                    putExtra(TaskRunActivity.EXTRA_SOURCE, TaskRunActivity.SOURCE_WIDGET)
                }
                val pi = PendingIntent.getActivity(
                    context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.styled_widget_image, pi)
            }
            manager.updateAppWidget(id, views)
        }

        /** Set the layout of every placed widget bound to [name] and re-render. Returns how many updated. */
        fun updateByName(context: Context, name: String, layoutJson: String): Int {
            val manager = AppWidgetManager.getInstance(context)
            val ids = StyledWidgetStore.idsForName(context, name)
            ids.forEach {
                StyledWidgetStore.setLayout(context, it, layoutJson)
                renderWidget(context, manager, it)
            }
            return ids.size
        }

        /** Pull model: re-render every placed styled widget (template-bound ones pull fresh `%var` values). */
        fun refreshAll(context: Context): Int {
            val manager = AppWidgetManager.getInstance(context)
            val ids = StyledWidgetStore.allIds(context)
            ids.forEach { renderWidget(context, manager, it) }
            return ids.size
        }

        /** Expand `%vars` in a template against all persisted globals (super + every project), merged. */
        private fun expandGlobals(raw: String): String {
            val scope = InMemoryGlobalScope()
            PersistentGlobalScope.snapshotAll().forEach { (k, v) -> scope.set(SUPER_GLOBAL_PROJECT_ID, k, v) }
            return runCatching { VariableStore(scope, SUPER_GLOBAL_PROJECT_ID).expand(raw) }.getOrDefault(raw)
        }
    }
}
