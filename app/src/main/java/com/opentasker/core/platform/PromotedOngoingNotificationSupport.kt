package com.opentasker.core.platform

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

/** Shared eligibility and compatibility handling for Android 16 promoted ongoing notifications. */
object PromotedOngoingNotificationSupport {
    const val MIN_PLATFORM_API = 36
    const val ENGINE_CHANNEL_ID = "opentasker.engine.live"
    val ENGINE_CHANNEL_IMPORTANCE: Int = NotificationManager.IMPORTANCE_LOW

    data class Eligibility(
        val requestPromotion: Boolean,
        val reason: String,
    )

    fun isPlatformSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= MIN_PLATFORM_API

    fun canPostPromotedNotifications(
        manager: NotificationManager,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (!isPlatformSupported(sdkInt)) return false
        return if (Build.VERSION.SDK_INT >= MIN_PLATFORM_API) {
            runCatching { manager.canPostPromotedNotifications() }.getOrDefault(false)
        } else {
            false
        }
    }

    fun eligibility(
        sdkInt: Int,
        canPostPromoted: Boolean,
        channelImportance: Int,
        title: CharSequence?,
        ongoing: Boolean,
    ): Eligibility = when {
        !isPlatformSupported(sdkInt) -> Eligibility(false, "Promoted ongoing notifications require Android 16 or newer.")
        !canPostPromoted -> Eligibility(false, "Promoted notifications are disabled in system settings.")
        !ongoing -> Eligibility(false, "Only ongoing notifications can be promoted.")
        title.isNullOrBlank() -> Eligibility(false, "A promoted notification must have a title.")
        channelImportance == NotificationManager.IMPORTANCE_MIN -> Eligibility(false, "The notification channel is too quiet for promotion.")
        channelImportance == NotificationManager.IMPORTANCE_NONE -> Eligibility(false, "The notification channel is blocked.")
        else -> Eligibility(true, "Eligible to request promoted ongoing treatment.")
    }

    /** Builds a standard AndroidX notification, requesting promotion only when it is eligible. */
    fun build(
        builder: NotificationCompat.Builder,
        manager: NotificationManager,
        channelImportance: Int,
        title: CharSequence?,
        ongoing: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Notification {
        val decision = eligibility(
            sdkInt = sdkInt,
            canPostPromoted = canPostPromotedNotifications(manager, sdkInt),
            channelImportance = channelImportance,
            title = title,
            ongoing = ongoing,
        )
        if (!decision.requestPromotion) return builder.build()

        builder.setRequestPromotedOngoing(true)
        val requested = builder.build()
        return if (sdkInt >= MIN_PLATFORM_API && !hasPromotableCharacteristics(requested)) {
            builder.setRequestPromotedOngoing(false).build()
        } else {
            requested
        }
    }

    /** Same request path for framework ProgressStyle, whose builder predates AndroidX support. */
    fun build(
        builder: Notification.Builder,
        manager: NotificationManager,
        channelImportance: Int,
        title: CharSequence?,
        ongoing: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Notification {
        val decision = eligibility(
            sdkInt = sdkInt,
            canPostPromoted = canPostPromotedNotifications(manager, sdkInt),
            channelImportance = channelImportance,
            title = title,
            ongoing = ongoing,
        )
        if (!decision.requestPromotion) return builder.build()

        // AndroidX uses this same platform extra and avoids directly linking the 36.1 method.
        builder.extras.putBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING, true)
        val requested = builder.build()
        return if (sdkInt >= MIN_PLATFORM_API && !hasPromotableCharacteristics(requested)) {
            builder.extras.putBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING, false)
            builder.build()
        } else {
            requested
        }
    }

    private fun hasPromotableCharacteristics(notification: Notification): Boolean =
        if (Build.VERSION.SDK_INT >= MIN_PLATFORM_API) notification.hasPromotableCharacteristics() else false
}
