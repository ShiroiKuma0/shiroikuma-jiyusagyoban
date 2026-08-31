package com.opentasker.ui

import com.opentasker.ui.screens.usesNavigationRail
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

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

    @Test
    fun largeFontUsesRailEarlierToKeepNavigationLabelsDiscoverable() {
        assertFalse(usesNavigationRail(559, fontScale = 1.29f))
        assertTrue(usesNavigationRail(560, fontScale = 1.3f))
        assertTrue(usesNavigationRail(599, fontScale = 2f))
    }


    // Dropped in the 0.2.81 upstream sync: upstream's adaptive navigation shell (rail on medium and
    // expanded widths, saveable resize/fold state) is not adopted — the fork keeps its own app shell.
}
