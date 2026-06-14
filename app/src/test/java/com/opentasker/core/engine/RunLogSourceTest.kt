package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunLogSourceTest {

    @Test
    fun classifiesProfileSourceWithLabel() {
        val c = RunLogSource.classify("Profile: Night Mode")
        assertEquals(RunLogSource.PROFILE, c.key)
        assertEquals("Night Mode", c.label)
    }

    @Test
    fun classifiesQuickSettingsTileWithLabel() {
        val c = RunLogSource.classify("Quick Settings Tile: Focus")
        assertEquals(RunLogSource.QUICK_SETTINGS_TILE, c.key)
        assertEquals("Focus", c.label)
    }

    @Test
    fun classifiesLabellessSources() {
        assertEquals(RunLogSource.EXTERNAL_INTENT, RunLogSource.classify("External intent").key)
        assertEquals(RunLogSource.MANUAL_RUN, RunLogSource.classify("Manual run").key)
        assertEquals(RunLogSource.NOTIFICATION_ACTION, RunLogSource.classify("Notification action").key)
        assertEquals(RunLogSource.WIDGET, RunLogSource.classify("Widget").key)
        assertEquals(RunLogSource.SHORTCUT, RunLogSource.classify("Shortcut").key)
        assertNull(RunLogSource.classify("External intent").label)
    }

    @Test
    fun unknownSourceFallsBackToOtherWithRawLabel() {
        val c = RunLogSource.classify("Some new surface")
        assertEquals(RunLogSource.OTHER, c.key)
        assertEquals("Some new surface", c.label)
    }

    @Test
    fun blankSourceIsOtherWithoutLabel() {
        val c = RunLogSource.classify("   ")
        assertEquals(RunLogSource.OTHER, c.key)
        assertNull(c.label)
    }

    @Test
    fun profileWithoutNameHasNullLabel() {
        val c = RunLogSource.classify("Profile:")
        assertEquals(RunLogSource.PROFILE, c.key)
        assertNull(c.label)
    }

    @Test
    fun displayNamesAreStable() {
        assertEquals("Profile", RunLogSource.displayName(RunLogSource.PROFILE))
        assertEquals("Quick Settings tile", RunLogSource.displayName(RunLogSource.QUICK_SETTINGS_TILE))
        assertEquals("Unknown", RunLogSource.displayName(null))
    }
}
