package com.opentasker.core.engine

import android.content.res.Resources
import com.opentasker.app.R

/** Localizable copy for admission decisions rendered in logs, diagnostics, and previews. */
interface ExecutionAdmissionStrings {
    fun circuitOpen(remainingSeconds: Long): String

    fun tripReason(reason: String): String

    fun globalActive(limit: String): String

    fun profileActive(limit: String): String

    fun globalBurst(): String

    fun profileBurst(): String

    fun globalAndProfileBurst(): String

    fun counts(
        activeGlobal: Int,
        globalActiveLimit: String,
        activeProfile: Int,
        profileActiveLimit: String,
        globalBurst: Int,
        globalBurstLimit: String,
        profileBurst: Int,
        profileBurstLimit: String,
    ): String

    fun previewAvailable(): String

    companion object {
        fun from(resources: Resources): ExecutionAdmissionStrings = ResourceExecutionAdmissionStrings(resources)

        val English: ExecutionAdmissionStrings = EnglishExecutionAdmissionStrings
    }
}

private class ResourceExecutionAdmissionStrings(
    private val resources: Resources,
) : ExecutionAdmissionStrings {
    override fun circuitOpen(remainingSeconds: Long): String = resources.getString(
        R.string.admission_reason_circuit_open,
        remainingSeconds,
    )

    override fun tripReason(reason: String): String = resources.getString(
        R.string.admission_reason_trip_reason,
        reason,
    )

    override fun globalActive(limit: String): String = resources.getString(
        R.string.admission_reason_global_active,
        limit,
    )

    override fun profileActive(limit: String): String = resources.getString(
        R.string.admission_reason_profile_active,
        limit,
    )

    override fun globalBurst(): String = resources.getString(R.string.admission_reason_global_burst)

    override fun profileBurst(): String = resources.getString(R.string.admission_reason_profile_burst)

    override fun globalAndProfileBurst(): String = resources.getString(
        R.string.admission_reason_global_and_profile_burst,
    )

    override fun counts(
        activeGlobal: Int,
        globalActiveLimit: String,
        activeProfile: Int,
        profileActiveLimit: String,
        globalBurst: Int,
        globalBurstLimit: String,
        profileBurst: Int,
        profileBurstLimit: String,
    ): String = resources.getString(
        R.string.admission_reason_counts,
        activeGlobal,
        globalActiveLimit,
        activeProfile,
        profileActiveLimit,
        globalBurst,
        globalBurstLimit,
        profileBurst,
        profileBurstLimit,
    )

    override fun previewAvailable(): String = resources.getString(R.string.admission_reason_preview_available)
}

private object EnglishExecutionAdmissionStrings : ExecutionAdmissionStrings {
    override fun circuitOpen(remainingSeconds: Long): String =
        "Execution circuit is open for $remainingSeconds more seconds."

    override fun tripReason(reason: String): String = "Trip reason: $reason"

    // Kept in step with the admission_reason_* resources. These are the values the run log
    // interpolates into "Held because: ...", so when the label was rewritten in plain language
    // and these were not, the user read a plain sentence ending in engine vocabulary.

    override fun globalActive(limit: String): String =
        "The app-wide limit on runs at once is full ($limit running)."

    override fun profileActive(limit: String): String =
        "This profile is already running as many times as it may ($limit)."

    override fun globalBurst(): String = "Too many runs started at once, app-wide."

    override fun profileBurst(): String = "Too many runs of this profile started at once."

    override fun globalAndProfileBurst(): String =
        "Too many runs started at once, both app-wide and for this profile."

    override fun counts(
        activeGlobal: Int,
        globalActiveLimit: String,
        activeProfile: Int,
        profileActiveLimit: String,
        globalBurst: Int,
        globalBurstLimit: String,
        profileBurst: Int,
        profileBurstLimit: String,
    ): String = "Running now: $activeGlobal of $globalActiveLimit app-wide, " +
        "$activeProfile of $profileActiveLimit for this profile. " +
        "Started at once: $globalBurst of $globalBurstLimit app-wide, " +
        "$profileBurst of $profileBurstLimit for this profile."

    override fun previewAvailable(): String = "There is room to run right now (preview only)."
}
