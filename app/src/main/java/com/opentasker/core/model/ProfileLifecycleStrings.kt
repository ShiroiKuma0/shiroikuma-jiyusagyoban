package com.opentasker.core.model

import android.content.res.Resources
import com.opentasker.app.R

/** Localizable copy emitted by profile lifecycle policy when it is rendered to a user. */
interface ProfileLifecycleStrings {
    fun oneShotConsumed(): String

    fun missingExpiry(): String

    fun expired(date: String): String

    fun suppressedByPriority(profileName: String): String

    companion object {
        fun from(resources: Resources): ProfileLifecycleStrings = ResourceProfileLifecycleStrings(resources)

        val English: ProfileLifecycleStrings = EnglishProfileLifecycleStrings
    }
}

private class ResourceProfileLifecycleStrings(
    private val resources: Resources,
) : ProfileLifecycleStrings {
    override fun oneShotConsumed(): String = resources.getString(R.string.profile_lifecycle_one_shot_consumed)

    override fun missingExpiry(): String = resources.getString(R.string.profile_lifecycle_missing_expiry)

    override fun expired(date: String): String = resources.getString(R.string.profile_lifecycle_expired, date)

    override fun suppressedByPriority(profileName: String): String = resources.getString(
        R.string.profile_lifecycle_suppressed_by_priority,
        profileName,
    )
}

private object EnglishProfileLifecycleStrings : ProfileLifecycleStrings {
    override fun oneShotConsumed(): String = "This one-shot profile has already run."

    override fun missingExpiry(): String = "This profile has no valid expiry date."

    override fun expired(date: String): String = "This profile expired on $date."

    override fun suppressedByPriority(profileName: String): String =
        "Suppressed by higher-priority profile '$profileName'."
}
