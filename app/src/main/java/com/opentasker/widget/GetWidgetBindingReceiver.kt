package com.opentasker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * External **"capture this widget's binding"** bridge — the CAPTURE half of the pull-template backup
 * contract (see [SetWidgetNameReceiver] for the RESTORE half). A sister app (the raikidoban launcher)
 * sends an explicit, **ordered** broadcast for a live `appWidgetId`; we reply, in the ordered-broadcast
 * **result extras**, with the current pull **template** (and optionally the name) so the launcher can
 * mirror that durable binding onto its own item tag and survive id reallocation (crash / host-restart /
 * `.lla` restore).
 *
 * No permission: the broadcast is explicit, and the sister apps may be signed with a different key.
 *
 * [Activity.RESULT_OK] is the **authoritative snapshot** for this id: template present → the launcher
 * stores it; template absent → the widget is static, so the launcher clears its template tag. Anything
 * other than OK (e.g. no such receiver on an old build → initial RESULT_CANCELED) leaves the tag untouched.
 */
class GetWidgetBindingReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.getIntExtra(SetWidgetNameReceiver.EXTRA_PROTOCOL, -1) != SetWidgetNameReceiver.PROTOCOL_VERSION) return

        // Honour the spec's fully-qualified key, falling back to the framework's standard extra.
        var id = intent.getIntExtra(SetWidgetNameReceiver.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // Pure, fast read of the live binding (well under the ordered-broadcast timeout).
        val template = StyledWidgetStore.getTemplate(app, id)   // null = static / not a pull widget
        val name = StyledWidgetStore.getName(app, id)           // optional, informational

        if (isOrderedBroadcast) {
            setResultExtras(
                Bundle().apply {
                    if (template != null) putString(SetWidgetNameReceiver.EXTRA_WIDGET_TEMPLATE, template)
                    if (name.isNotEmpty()) putString(SetWidgetNameReceiver.EXTRA_WIDGET_NAME, name)
                },
            )
            // OK even when both fields are absent → the launcher reads that as "static widget, clear the tag".
            resultCode = Activity.RESULT_OK
        }
    }
}
