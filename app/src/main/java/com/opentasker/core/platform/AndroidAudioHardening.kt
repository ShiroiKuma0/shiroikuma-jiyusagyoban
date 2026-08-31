package com.opentasker.core.platform

import android.os.Build
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.scheduling.ExactAlarmSupport

enum class AudioForegroundServiceEligibility {
    NONE,
    BACKGROUND_STARTED,
    WHILE_IN_USE,
}

enum class AudioUsageEligibility {
    GENERAL,
    ALARM,
}

data class AudioRuntimeEligibility(
    val appVisible: Boolean = false,
    val foregroundService: AudioForegroundServiceEligibility = AudioForegroundServiceEligibility.NONE,
    val exactAlarmPermission: Boolean = false,
)

data class AudioHardeningDecision(
    val allowed: Boolean,
    val reason: String,
)

/** Runtime model for https://developer.android.com/about/versions/17/changes/bg-audio. */
internal object AndroidAudioHardening {
    const val ANDROID_17_API: Int = 37

    @Volatile
    internal var sdkIntOverrideForTests: Int? = null

    fun isRestricted(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= ANDROID_17_API

    fun evaluate(
        eligibility: AudioRuntimeEligibility,
        usage: AudioUsageEligibility = AudioUsageEligibility.GENERAL,
        sdkInt: Int = sdkIntOverrideForTests ?: Build.VERSION.SDK_INT,
        targetSdkInt: Int = ANDROID_17_API,
    ): AudioHardeningDecision {
        if (!isRestricted(sdkInt)) {
            return AudioHardeningDecision(allowed = true, reason = "Android audio hardening does not apply")
        }
        if (eligibility.appVisible) {
            return AudioHardeningDecision(allowed = true, reason = "OpenTasker has a visible activity")
        }
        if (targetSdkInt < ANDROID_17_API && eligibility.foregroundService != AudioForegroundServiceEligibility.NONE) {
            return AudioHardeningDecision(allowed = true, reason = "A foreground service is running")
        }
        if (eligibility.foregroundService == AudioForegroundServiceEligibility.WHILE_IN_USE) {
            return AudioHardeningDecision(
                allowed = true,
                reason = "The automation foreground service has while-in-use eligibility",
            )
        }
        if (usage == AudioUsageEligibility.ALARM && eligibility.exactAlarmPermission) {
            return AudioHardeningDecision(
                allowed = true,
                reason = "Exact-alarm permission permits alarm-usage audio",
            )
        }

        val recovery = when (eligibility.foregroundService) {
            AudioForegroundServiceEligibility.BACKGROUND_STARTED ->
                "The automation service was started in the background and lacks while-in-use eligibility. " +
                    "Open OpenTasker, then retry the task from the visible app."
            AudioForegroundServiceEligibility.NONE ->
                "No eligible foreground service or visible activity is active. " +
                    "Open OpenTasker, then retry the task from the visible app."
            AudioForegroundServiceEligibility.WHILE_IN_USE -> error("handled above")
        }
        val alarmHint = if (usage == AudioUsageEligibility.ALARM && !eligibility.exactAlarmPermission) {
            " Grant exact-alarm access in Setup to permit alarm-stream changes while backgrounded."
        } else {
            ""
        }
        return AudioHardeningDecision(allowed = false, reason = recovery + alarmHint)
    }

    fun failureIfIneligible(
        ctx: ActionContext,
        operation: String,
        usage: AudioUsageEligibility = AudioUsageEligibility.GENERAL,
    ): ActionResult.Failure? {
        val targetSdkInt = runCatching { ctx.app.applicationInfo.targetSdkVersion }
            .getOrDefault(ANDROID_17_API)
        val currentEligibility = ctx.audioEligibility.copy(
            appVisible = ctx.audioEligibility.appVisible || AppVisibilityTracker.isAppVisible,
            exactAlarmPermission = ctx.audioEligibility.exactAlarmPermission ||
                (sdkIntOverrideForTests == null && runCatching {
                    ExactAlarmSupport.canScheduleExactAlarms(ctx.app)
                }.getOrDefault(false)),
        )
        val decision = evaluate(
            eligibility = currentEligibility,
            usage = usage,
            targetSdkInt = targetSdkInt,
        )
        return if (decision.allowed) {
            null
        } else {
            ActionResult.Failure("Android 17+ blocked $operation before any audio side effect. ${decision.reason}")
        }
    }

    fun outputCapabilityReason(reason: String): String =
        "Android 17+ allows this only while 白い熊 自由作業盤 is visible, from a while-in-use eligible foreground service, " +
            "or for alarm-usage audio with exact-alarm access. $reason"

    fun mediaKeyCapabilityReason(reason: String): String =
        "Android 17+ allows media-key dispatch only while 白い熊 自由作業盤 is visible or from a while-in-use eligible " +
            "foreground service. $reason"

    fun volumeCapabilityReason(reason: String): String =
        "Android 17+ allows volume changes only while 白い熊 自由作業盤 is visible, from a while-in-use eligible foreground " +
            "service, or on the alarm stream with exact-alarm access. $reason"
}
