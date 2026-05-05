package com.opentasker.core.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPolicyDisclosuresTest {
    @Test
    fun backgroundSetupBodyExplainsAndroid11SettingsFlowAndDeclinePath() {
        val body = LocationPolicyDisclosures.backgroundSetupBody(apiLevel = 34)

        assertTrue(body.contains("Android 11+"))
        assertTrue(body.contains("app settings"))
        assertTrue(body.contains("Users can decline"))
    }

    @Test
    fun foregroundSetupBodyExplainsApproximatePrecisionCarryover() {
        val body = LocationPolicyDisclosures.foregroundSetupBody

        assertTrue(body.contains("Approximate access"))
        assertTrue(body.contains("background precision"))
    }

    @Test
    fun sourceSetupDetailMentionsAndroid14LocationForegroundServiceGateWhenReady() {
        val detail = LocationPolicyDisclosures.sourceSetupDetail(
            foreground = true,
            precise = true,
            background = true,
            providerEnabled = true,
            apiLevel = 34,
        )

        assertTrue(detail.contains("Android 14+"))
        assertTrue(detail.contains("foreground-service"))
    }

    @Test
    fun sourceSetupDetailAvoidsAndroid14CopyBeforeApi34() {
        val detail = LocationPolicyDisclosures.sourceSetupDetail(
            foreground = true,
            precise = true,
            background = true,
            providerEnabled = true,
            apiLevel = 33,
        )

        assertFalse(detail.contains("Android 14+"))
    }
}
