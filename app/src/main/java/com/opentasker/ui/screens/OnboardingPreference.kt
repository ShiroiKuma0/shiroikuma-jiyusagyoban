package com.opentasker.ui.screens

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "onboarding_prefs")

object OnboardingPreference {
    private val KEY_COMPLETED = booleanPreferencesKey("onboarding_completed")

    fun hasCompleted(context: Context): Flow<Boolean> =
        context.onboardingDataStore.data.map { prefs ->
            prefs[KEY_COMPLETED] ?: false
        }

    suspend fun markCompleted(context: Context) {
        context.onboardingDataStore.edit { prefs ->
            prefs[KEY_COMPLETED] = true
        }
    }
}
