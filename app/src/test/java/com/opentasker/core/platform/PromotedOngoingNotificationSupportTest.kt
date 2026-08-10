package com.opentasker.core.platform

import android.app.NotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotedOngoingNotificationSupportTest {
    @Test
    fun preAndroid16KeepsTheStandardNotificationPath() {
        val decision = PromotedOngoingNotificationSupport.eligibility(
            sdkInt = 35,
            canPostPromoted = true,
            channelImportance = NotificationManager.IMPORTANCE_LOW,
            title = "Task running",
            ongoing = true,
        )

        assertFalse(decision.requestPromotion)
        assertTrue(decision.reason.contains("Android 16"))
    }

    @Test
    fun promotionRequiresUserAccessAndPromotableChannelCharacteristics() {
        val disabled = PromotedOngoingNotificationSupport.eligibility(
            sdkInt = 36,
            canPostPromoted = false,
            channelImportance = NotificationManager.IMPORTANCE_LOW,
            title = "Task running",
            ongoing = true,
        )
        val quiet = PromotedOngoingNotificationSupport.eligibility(
            sdkInt = 36,
            canPostPromoted = true,
            channelImportance = NotificationManager.IMPORTANCE_MIN,
            title = "Task running",
            ongoing = true,
        )
        val untitled = PromotedOngoingNotificationSupport.eligibility(
            sdkInt = 36,
            canPostPromoted = true,
            channelImportance = NotificationManager.IMPORTANCE_LOW,
            title = "",
            ongoing = true,
        )

        assertFalse(disabled.requestPromotion)
        assertFalse(quiet.requestPromotion)
        assertFalse(untitled.requestPromotion)
    }

    @Test
    fun eligibleActiveNotificationRequestsPromotion() {
        val decision = PromotedOngoingNotificationSupport.eligibility(
            sdkInt = 36,
            canPostPromoted = true,
            channelImportance = NotificationManager.IMPORTANCE_LOW,
            title = "Task running",
            ongoing = true,
        )

        assertTrue(decision.requestPromotion)
        assertTrue(decision.reason.contains("Eligible"))
    }
}
