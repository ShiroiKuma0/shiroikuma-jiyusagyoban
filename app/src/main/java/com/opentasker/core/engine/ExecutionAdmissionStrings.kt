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

    override fun globalActive(limit: String): String = "Global execution limit reached ($limit active)."

    override fun profileActive(limit: String): String = "Profile execution limit reached ($limit active)."

    override fun globalBurst(): String = "Burst limit exceeded (the global window)."

    override fun profileBurst(): String = "Burst limit exceeded (the per-profile window)."

    override fun globalAndProfileBurst(): String =
        "Burst limit exceeded (global and per-profile windows)."

    override fun counts(
        activeGlobal: Int,
        globalActiveLimit: String,
        activeProfile: Int,
        profileActiveLimit: String,
        globalBurst: Int,
        globalBurstLimit: String,
        profileBurst: Int,
        profileBurstLimit: String,
    ): String = "Counts: active global=$activeGlobal/$globalActiveLimit, " +
        "profile=$activeProfile/$profileActiveLimit; " +
        "burst global=$globalBurst/$globalBurstLimit, " +
        "profile=$profileBurst/$profileBurstLimit."

    override fun previewAvailable(): String = "Admission budget is available (preview only)."
}
