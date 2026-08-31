package com.opentasker.ui.screens

import android.content.Context
import android.provider.Settings
import com.opentasker.app.R
import com.opentasker.core.capabilities.WriteSettingsAdmission
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.AppDatabase

/**
 * Admission for the actions that cannot work without Modify system settings.
 *
 * Enabling a profile, restoring one from edit history and running a task by hand all have to make
 * the same call, so the check lives here rather than in whichever screen happens to own the write.
 */
internal class WriteSettingsGuard(
    private val db: AppDatabase,
    private val appContext: Context,
) {
    private suspend fun profileActions(profile: Profile): List<ActionSpec> {
        val ids = listOfNotNull(profile.enterTaskId, profile.exitTaskId, profile.fallbackTaskId).distinct()
        return ids.flatMap { id -> db.taskDao().getById(id)?.toDomain()?.actions.orEmpty() }
    }

    suspend fun requireWriteSettingsIfEnabled(profile: Profile) {
        if (!profile.enabled) return
        requireWriteSettingsReady(profileActions(profile))
    }

    fun requireWriteSettingsReady(actions: List<ActionSpec>) {
        if (WriteSettingsAdmission.blocked(actions, Settings.System.canWrite(appContext))) {
            // Fork: upstream throws its localized UiRejection here; the fork carries plain
            // message strings, and the ViewModel surfaces `message` straight to the snackbar.
            throw IllegalStateException(appContext.getString(R.string.ui_error_write_settings_required))
        }
    }
}
