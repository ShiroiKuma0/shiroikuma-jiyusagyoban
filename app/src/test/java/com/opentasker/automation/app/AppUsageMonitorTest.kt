package com.opentasker.automation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUsageMonitorTest {
    @Test
    fun selectLatestForegroundPackageReturnsNewestNonBlankPackage() {
        val selected = AppUsageMonitor.selectLatestForegroundPackage(
            listOf(
                ForegroundUsageEvent(packageName = "com.old", timestamp = 100L),
                ForegroundUsageEvent(packageName = " ", timestamp = 300L),
                ForegroundUsageEvent(packageName = "com.new", timestamp = 200L),
            ),
        )

        assertEquals("com.new", selected)
    }

    @Test
    fun selectLatestForegroundPackageReturnsNullForNoUsableEvents() {
        val selected = AppUsageMonitor.selectLatestForegroundPackage(
            listOf(ForegroundUsageEvent(packageName = "", timestamp = 100L)),
        )

        assertNull(selected)
    }
}
