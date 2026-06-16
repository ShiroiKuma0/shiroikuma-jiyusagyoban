package com.opentasker.core.platform

import android.os.Build
import com.opentasker.core.engine.ActionResult

internal object AndroidAudioHardening {
    const val ANDROID_17_API: Int = 37

    fun isRestricted(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= ANDROID_17_API

    fun soundPlaybackFailure(): ActionResult.Failure =
        ActionResult.Failure(
            "Android 17+ restricts background audio playback; " +
                "sound.play may produce no output from a background service without a media foreground-service type",
        )

    fun ttsFailure(): ActionResult.Failure =
        ActionResult.Failure(
            "Android 17+ restricts background audio output; " +
                "TTS may produce no speech from a background service without a media foreground-service type",
        )

    fun volumeFailure(operation: String): ActionResult.Failure =
        ActionResult.Failure(
            "Android 17+ restricts background volume changes; " +
                "$operation may not work from a background service",
        )

    fun mediaKeyFailure(): ActionResult.Failure =
        ActionResult.Failure(
            "Android 17+ restricts background audio control; " +
                "media key dispatch may not reach the active player from a background service",
        )

    fun outputCapabilityReason(reason: String): String =
        "Android 17+ restricts background audio output without a media foreground-service type. $reason"

    fun mediaKeyCapabilityReason(reason: String): String =
        "Android 17+ restricts background media key dispatch. $reason"

    fun volumeCapabilityReason(reason: String): String =
        "Android 17+ restricts background volume changes. $reason"
}
