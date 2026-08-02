package com.opentasker.ui

import com.opentasker.ui.screens.usesNavigationRail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveNavigationTest {
    @Test
    fun compactWidthsKeepBottomNavigation() {
        assertFalse(usesNavigationRail(599))
    }

    @Test
    fun mediumAndExpandedWidthsUseRailWithAllDestinationsAvailable() {
        assertTrue(usesNavigationRail(600))
        assertTrue(usesNavigationRail(840))
    }
}
