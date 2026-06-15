package com.opentasker.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    System,
    Dark,
    Light;

    companion object {
        fun fromString(value: String?): ThemeMode = when (value) {
            "dark" -> Dark
            "light" -> Light
            else -> System
        }
    }

    fun toStorageString(): String = when (this) {
        System -> "system"
        Dark -> "dark"
        Light -> "light"
    }
}

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

object ThemePreference {
    private val KEY = stringPreferencesKey("theme_mode")

    fun observe(context: Context): Flow<ThemeMode> =
        context.themeDataStore.data.map { prefs ->
            ThemeMode.fromString(prefs[KEY])
        }

    suspend fun set(context: Context, mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[KEY] = mode.toStorageString()
        }
    }
}
