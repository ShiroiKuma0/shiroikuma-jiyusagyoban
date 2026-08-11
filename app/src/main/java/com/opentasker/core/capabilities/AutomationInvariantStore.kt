package com.opentasker.core.capabilities

import android.content.Context
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.AutomationInvariantPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Device-local persistence for the optional diagnostics policy; it is never read by the engine. */
class AutomationInvariantStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(): List<AutomationInvariant> = synchronized(this) {
        preferences.getString(KEY_INVARIANTS, null)
            ?.let { encoded -> runCatching { json.decodeFromString<List<AutomationInvariant>>(encoded) }.getOrNull() }
            ?.let(AutomationInvariantPolicy::normalize)
            .orEmpty()
    }

    fun save(invariants: List<AutomationInvariant>): List<AutomationInvariant> = synchronized(this) {
        val normalized = AutomationInvariantPolicy.normalize(invariants)
        preferences.edit().putString(KEY_INVARIANTS, json.encodeToString(normalized)).apply()
        normalized
    }

    fun merge(invariants: List<AutomationInvariant>): List<AutomationInvariant> = save(load() + invariants)

    private companion object {
        const val PREFERENCES_NAME = "automation_invariants"
        const val KEY_INVARIANTS = "invariants"
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = false
        }
    }
}
