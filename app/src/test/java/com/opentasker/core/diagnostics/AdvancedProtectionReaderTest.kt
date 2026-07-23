package com.opentasker.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedProtectionReaderTest {
    @Test
    fun warnsOnlyWhenApmEnabledOnApi36Plus() {
        assertTrue(AdvancedProtectionReader.shouldWarn(sdkInt = 36, apmEnabled = true))
        assertTrue(AdvancedProtectionReader.shouldWarn(sdkInt = 37, apmEnabled = true))
    }

    @Test
    fun doesNotWarnWhenApmDisabled() {
        assertFalse(AdvancedProtectionReader.shouldWarn(sdkInt = 37, apmEnabled = false))
    }

    @Test
    fun isANoOpBelowApi36() {
        assertFalse(AdvancedProtectionReader.shouldWarn(sdkInt = 35, apmEnabled = true))
        assertFalse(AdvancedProtectionReader.shouldWarn(sdkInt = 33, apmEnabled = true))
    }
}
