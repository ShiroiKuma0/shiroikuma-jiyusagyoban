package com.opentasker.core.updates

import android.content.Context
import androidx.core.content.edit

data class UpdateCheckState(
    val enabled: Boolean = false,
    val newerVersion: String? = null,
    val releaseUrl: String? = null,
    val lastCheckedAtMs: Long? = null,
)

/** Device-local preference and result storage for the opt-in release check. */
class UpdateCheckSettings(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): UpdateCheckState {
        val url = preferences.getString(KEY_RELEASE_URL, null)?.takeIf(String::isNotBlank)
        val version = preferences.getString(KEY_NEWER_VERSION, null)?.takeIf(String::isNotBlank)
        return UpdateCheckState(
            enabled = preferences.getBoolean(KEY_ENABLED, false),
            newerVersion = version.takeIf { url != null },
            releaseUrl = url.takeIf { version != null },
            lastCheckedAtMs = preferences.getLong(KEY_LAST_CHECKED_AT, 0L).takeIf { it > 0L },
        )
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_ENABLED, enabled)
            if (!enabled) {
                remove(KEY_NEWER_VERSION)
                remove(KEY_RELEASE_URL)
                remove(KEY_LAST_CHECKED_AT)
            }
        }
    }

    fun record(result: UpdateCheckResult, checkedAtMs: Long) {
        preferences.edit {
            putLong(KEY_LAST_CHECKED_AT, checkedAtMs)
            when (result) {
                UpdateCheckResult.NoUpdate -> {
                    remove(KEY_NEWER_VERSION)
                    remove(KEY_RELEASE_URL)
                }
                is UpdateCheckResult.Available -> {
                    putString(KEY_NEWER_VERSION, result.version)
                    putString(KEY_RELEASE_URL, result.url)
                }
                is UpdateCheckResult.Invalid -> Unit
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "update_checks"
        const val KEY_ENABLED = "enabled"
        const val KEY_NEWER_VERSION = "newer_version"
        const val KEY_RELEASE_URL = "release_url"
        const val KEY_LAST_CHECKED_AT = "last_checked_at"
    }
}
