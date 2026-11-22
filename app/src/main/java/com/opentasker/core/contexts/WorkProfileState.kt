package com.opentasker.core.contexts

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.RequiresApi

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

    /**
     * A profile known not to be a work profile (a clone, say) doesn't count, and one of unknown kind
     * does. Only a work profile can be paused, so a paused one wins over another that runs.
     */
    fun stateOf(profiles: List<OtherProfile>): String {
        val work = profiles.filter { it.managed != false }
        return when {
            work.isEmpty() -> NONE
            work.any { it.quietMode } -> PAUSED
            else -> AVAILABLE
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
                    managed = if (Build.VERSION.SDK_INT >= 35 && launcherApps != null) isWorkProfile(launcherApps, handle) else null,
                    quietMode = runCatching { users.isQuietModeEnabled(handle) }.getOrDefault(false),
                )
            }
        return stateOf(profiles)
    }

    @RequiresApi(35)
    private fun isWorkProfile(apps: LauncherApps, handle: UserHandle): Boolean? = runCatching {
        val described = apps.getLauncherUserInfo(handle)?.let { it.userType == UserManager.USER_TYPE_PROFILE_MANAGED }
        kindOf(described, listed = handle in apps.profiles)
    }.getOrNull()

    /**
     * Android 15 says what kind each profile it lets this app see is, unless the build leaves out
     * Private Space, when it says nothing, so a profile [LauncherApps] lists but doesn't describe
     * stays unknown. One hidden from that list is Private Space, never the work profile.
     */
    fun kindOf(described: Boolean?, listed: Boolean): Boolean? = described ?: if (listed) null else false
}
