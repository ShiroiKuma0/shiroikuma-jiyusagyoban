package com.sysadmindoc.ruledeck.ui.screens

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.profileDefaultsDataStore by preferencesDataStore(name = "profile_defaults_prefs")

/**
 * Whether a profile made in the editor starts switched on. Off until the user turns it on in
 * Settings, so a new profile stays review-first like templates and imports.
 */
object ProfileDefaultsPreference {
    private val KEY_NEW_PROFILES_ENABLED = booleanPreferencesKey("new_profiles_enabled")

    fun newProfilesEnabled(context: Context): Flow<Boolean> =
        context.profileDefaultsDataStore.data
            .map { prefs -> prefs[KEY_NEW_PROFILES_ENABLED] ?: false }
            .catch { emit(false) }

    suspend fun setNewProfilesEnabled(context: Context, enabled: Boolean) {
        context.profileDefaultsDataStore.edit { prefs -> prefs[KEY_NEW_PROFILES_ENABLED] = enabled }
    }
}
