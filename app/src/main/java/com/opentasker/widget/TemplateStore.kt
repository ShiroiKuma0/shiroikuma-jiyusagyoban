package com.opentasker.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A named, reusable widget layout (JSON, with `%vars` left raw — expanded at Set-Widget time). */
data class WidgetTemplate(val name: String, val layout: String)

/**
 * Library of named widget-layout templates, designed in the visual editor (the "Widgets" tab) and
 * referenced by the Set Widget action — edit a design once, every widget using it updates next run.
 * SharedPreferences-backed with a [state] StateFlow for the UI; mirrors ThemeStore. Call [init] in
 * Application.onCreate before any task/UI reads it.
 */
object TemplateStore {
    private const val PREFS = "shiroikuma_widget_templates"
    private const val K_TEMPLATES = "templates_json"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    private val _state = MutableStateFlow<List<WidgetTemplate>>(emptyList())
    val state: StateFlow<List<WidgetTemplate>> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = load()
    }

    private fun load(): List<WidgetTemplate> {
        val raw = prefs.getString(K_TEMPLATES, null) ?: return emptyList()
        val map = runCatching { json.decodeFromString(mapSerializer, raw) }.getOrDefault(emptyMap())
        return map.map { WidgetTemplate(it.key, it.value) }.sortedBy { it.name.lowercase() }
    }

    private fun persist(list: List<WidgetTemplate>) {
        val map = list.associate { it.name to it.layout }
        prefs.edit { putString(K_TEMPLATES, json.encodeToString(mapSerializer, map)) }
    }

    /** Raw layout JSON for [name] (with `%vars` unexpanded), or null — used by the Set Widget action. */
    fun get(name: String): String? = _state.value.firstOrNull { it.name == name }?.layout

    fun names(): List<String> = _state.value.map { it.name }

    /** Create or replace a template. A blank name is ignored. */
    fun put(name: String, layout: String) {
        val trimmed = name.trim().ifBlank { return }
        val next = (_state.value.filterNot { it.name == trimmed } + WidgetTemplate(trimmed, layout))
            .sortedBy { it.name.lowercase() }
        persist(next)
        _state.value = next
    }

    fun delete(name: String) {
        val next = _state.value.filterNot { it.name == name }
        persist(next)
        _state.value = next
    }
}
