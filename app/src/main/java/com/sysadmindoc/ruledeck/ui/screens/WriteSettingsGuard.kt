package com.sysadmindoc.ruledeck.ui.screens

import android.content.Context
import android.provider.Settings
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.capabilities.WriteSettingsAdmission
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.storage.AppDatabase

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
            throw UiRejection(R.string.ui_error_write_settings_required)
        }
    }
}
