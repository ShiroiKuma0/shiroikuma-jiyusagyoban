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

internal enum class OnboardingExit {
    Dismissed,
    Skipped,
    InstalledTemplate,
}

/**
 * Dismissing is not a decision. Tapping outside the first-launch dialog, or backing out of it,
 * used to mark onboarding finished forever, which left a new install with no route to Setup and no
 * way to see the flow again. Only an explicit skip or a finished template counts as completing it.
 */
internal fun shouldCompleteOnboarding(exit: OnboardingExit): Boolean = when (exit) {
    OnboardingExit.Dismissed -> false
    OnboardingExit.Skipped,
    OnboardingExit.InstalledTemplate,
    -> true
}

internal fun shouldLaunchOnboarding(completed: Boolean, selectedTemplateId: String?): Boolean =
    !completed && selectedTemplateId == null
