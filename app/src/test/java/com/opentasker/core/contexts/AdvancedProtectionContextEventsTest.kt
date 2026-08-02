package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvancedProtectionContextEventsTest {
    @Test
    fun enabledAndDisabledTransitionsUseStableEventMetadata() {
        val enabled = AdvancedProtectionContextEvents.buildEvent(true)
        val disabled = AdvancedProtectionContextEvents.buildEvent(false)

        assertEquals("advanced_protection", enabled.metadata["event"])
        assertEquals("enabled", enabled.metadata["state"])
        assertEquals("true", enabled.metadata["enabled"])
        assertEquals("disabled", disabled.metadata["state"])
        assertEquals("false", disabled.metadata["enabled"])
    }
}
