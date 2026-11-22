package com.opentasker.core.contexts

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserManager

/**
 * The `work_profile` state key: whether this user's work profile is running, paused, or not there.
 * Private Space can't be read here: Android shows it only to the home screen app, which needs the
 * launcher role and ACCESS_HIDDEN_PROFILES.
 */
internal object WorkProfileState {
    const val KEY = "work_profile"
    const val AVAILABLE = "available"
    const val PAUSED = "paused"
    const val NONE = "none"

    /** What Android sends the parent user when its work profile comes, goes, pauses or resumes. */
    val ACTIONS = listOf(
        Intent.ACTION_MANAGED_PROFILE_ADDED,
        Intent.ACTION_MANAGED_PROFILE_REMOVED,
        Intent.ACTION_MANAGED_PROFILE_AVAILABLE,
        Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE,
        Intent.ACTION_MANAGED_PROFILE_UNLOCKED,
    )

    /**
     * Another profile of this user. [managed] is null when Android doesn't say what kind of
     * profile it is, which is everything before Android 15.
     */
    data class OtherProfile(val managed: Boolean?, val quietMode: Boolean)

    /** A profile known not to be a work profile (a clone, say) doesn't count; one of unknown kind does. */
    fun stateOf(profiles: List<OtherProfile>): String {
        val work = profiles.filter { it.managed != false }
        return when {
            work.isEmpty() -> NONE
            work.any { !it.quietMode } -> AVAILABLE
            else -> PAUSED
        }
    }

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_MANAGED_USERS)

    fun read(context: Context): String {
        if (!supported(context)) return NONE
        val users = context.getSystemService(UserManager::class.java) ?: return NONE
        // Installed inside the work profile, this app is that profile, and it runs only while the
        // profile does.
        if (Build.VERSION.SDK_INT >= 30 && runCatching { users.isManagedProfile }.getOrDefault(false)) return AVAILABLE
        val launcherApps = if (Build.VERSION.SDK_INT >= 35) context.getSystemService(LauncherApps::class.java) else null
        val me = Process.myUserHandle()
        val profiles = runCatching { users.userProfiles }.getOrDefault(emptyList())
            .filter { it != me }
            .map { handle ->
                OtherProfile(
                    managed = launcherApps?.let { apps ->
                        runCatching { apps.getLauncherUserInfo(handle)?.userType }.getOrNull()
                            ?.let { it == UserManager.USER_TYPE_PROFILE_MANAGED }
                    },
                    quietMode = runCatching { users.isQuietModeEnabled(handle) }.getOrDefault(false),
                )
            }
        return stateOf(profiles)
    }
}
