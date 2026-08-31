package com.opentasker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

/**
 * External **"name this widget"** bridge. A sister app (e.g. the raikidoban launcher) binds one of our
 * AppWidgets, then sends an **explicit, ordered** broadcast giving the live `appWidgetId` a name so
 * tasks / templates can target that placement by name.
 *
 * No permission: the broadcast is explicit (the sender targets our package/component), and the sister
 * apps may be signed with a different key — so a signature permission would wrongly lock them out.
 *
 * Ordered-broadcast result: [Activity.RESULT_OK] once the name is persisted and the widget re-rendered,
 * or [Activity.RESULT_FIRST_USER] meaning "not ready / not persisted" so the caller can retry.
 */
class SetWidgetNameReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        val protocol = intent.getIntExtra(EXTRA_PROTOCOL, -1)
        // Honour the spec's fully-qualified key, falling back to the framework's standard extra.
        var appWidgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        val name = intent.getStringExtra(EXTRA_WIDGET_NAME)?.trim().orEmpty()
        // null = template field not sent (leave the binding as-is); "" = clear (back to static); else = bind.
        val template = intent.getStringExtra(EXTRA_WIDGET_TEMPLATE)
        val provider = intent.getStringExtra(EXTRA_PROVIDER)?.let(ComponentName::unflattenFromString)

        val handled = protocol == PROTOCOL_VERSION &&
            appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            (name.isNotEmpty() || template != null) &&   // name OR template — a pull widget has no name
            runCatching { persistAndRender(app, appWidgetId, name, provider, template) }.getOrDefault(false)

        // Only ordered broadcasts carry a result; setting it for a non-ordered one is a no-op/warning.
        if (isOrderedBroadcast) {
            resultCode = if (handled) Activity.RESULT_OK else Activity.RESULT_FIRST_USER
        }
    }

    /**
     * Persist `appWidgetId → name` and/or its pull `template` for the addressed provider and re-render it.
     * [template] null = leave the binding untouched; blank = clear it (back to static); else = bind to it.
     * Returns false if unhandled.
     */
    private fun persistAndRender(app: Context, appWidgetId: Int, name: String, provider: ComponentName?, template: String?): Boolean {
        val manager = AppWidgetManager.getInstance(app) ?: return false
        val isTask = provider?.className?.endsWith("TaskWidgetProvider") == true
        val isStyled = provider == null || provider.className.endsWith("StyledWidgetProvider")
        return when {
            isTask -> {
                // Task widgets have only a name, no pull template.
                if (name.isNotEmpty()) {
                    app.getSharedPreferences(TaskWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit { putString(TaskWidgetProvider.keyTaskName(appWidgetId), name) }
                }
                TaskWidgetProvider.updateWidget(app, manager, appWidgetId)
                true
            }
            isStyled -> {
                if (name.isNotEmpty()) StyledWidgetStore.setName(app, appWidgetId, name)
                if (template != null) {
                    if (template.isBlank()) StyledWidgetStore.clearTemplate(app, appWidgetId)
                    else StyledWidgetStore.setTemplate(app, appWidgetId, template)
                }
                StyledWidgetProvider.renderWidget(app, manager, appWidgetId)
                true
            }
            else -> false // an unrecognised provider component — not one of ours
        }
    }

    companion object {
        const val ACTION_SET_WIDGET_NAME = "shiroikuma.jiyusagyoban.action.SET_WIDGET_NAME"
        const val ACTION_GET_WIDGET_BINDING = "shiroikuma.jiyusagyoban.action.GET_WIDGET_BINDING"
        const val EXTRA_APPWIDGET_ID = "android.appwidget.extra.APPWIDGET_ID"
        const val EXTRA_WIDGET_NAME = "shiroikuma.jiyusagyoban.extra.WIDGET_NAME"
        const val EXTRA_WIDGET_TEMPLATE = "shiroikuma.jiyusagyoban.extra.WIDGET_TEMPLATE"
        const val EXTRA_PROVIDER = "shiroikuma.jiyusagyoban.extra.PROVIDER"
        const val EXTRA_PROTOCOL = "shiroikuma.jiyusagyoban.extra.PROTOCOL"
        const val PROTOCOL_VERSION = 1
    }
}
