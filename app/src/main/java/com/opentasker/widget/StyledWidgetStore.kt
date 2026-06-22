package com.opentasker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit

/**
 * Per-app-widget binding for styled widgets: a user-chosen **name** (so a task's `Set Widget` action
 * can target it), the current **layout JSON**, and an optional **tap task**. Backed by SharedPreferences.
 */
object StyledWidgetStore {
    private const val PREFS = "shiroikuma_styled_widgets"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setName(ctx: Context, id: Int, name: String) = prefs(ctx).edit { putString("$id.name", name) }
    fun getName(ctx: Context, id: Int): String = prefs(ctx).getString("$id.name", "") ?: ""

    fun setLayout(ctx: Context, id: Int, json: String) = prefs(ctx).edit { putString("$id.layout", json) }
    fun getLayout(ctx: Context, id: Int): String? = prefs(ctx).getString("$id.layout", null)

    /** Pull-model binding: the [WidgetTemplate] name this slot renders (null = static [getLayout] instead). */
    fun setTemplate(ctx: Context, id: Int, name: String) = prefs(ctx).edit { putString("$id.template", name) }
    fun getTemplate(ctx: Context, id: Int): String? = prefs(ctx).getString("$id.template", null)?.takeIf { it.isNotBlank() }

    fun setTapTask(ctx: Context, id: Int, taskId: Long) = prefs(ctx).edit { putLong("$id.taptask", taskId) }
    fun getTapTask(ctx: Context, id: Int): Long = prefs(ctx).getLong("$id.taptask", -1L)

    // Tap task bound by NAME (resolved at tap time), so it survives bundle re-imports that change ids.
    fun setTapTaskName(ctx: Context, id: Int, name: String) = prefs(ctx).edit { putString("$id.taptaskname", name) }
    fun getTapTaskName(ctx: Context, id: Int): String = prefs(ctx).getString("$id.taptaskname", "") ?: ""

    fun clear(ctx: Context, id: Int) = prefs(ctx).edit {
        remove("$id.name"); remove("$id.layout"); remove("$id.template"); remove("$id.taptask"); remove("$id.taptaskname")
    }

    /** All placed styled-widget ids whose bound name matches [name] (case-insensitive). */
    fun idsForName(ctx: Context, name: String): IntArray {
        val manager = AppWidgetManager.getInstance(ctx)
        val all = manager.getAppWidgetIds(ComponentName(ctx, StyledWidgetProvider::class.java))
        return all.filter { getName(ctx, it).equals(name.trim(), ignoreCase = true) }.toIntArray()
    }

    fun allIds(ctx: Context): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StyledWidgetProvider::class.java))
}
