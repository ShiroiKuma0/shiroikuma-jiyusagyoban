package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class QuickSettingsTileTest {
    @Test
    fun slotsMapToStableComponentsAndRejectOutOfRangeValues() {
        assertEquals(1, QuickSettingsTileSlots.normalize(1))
        assertEquals(4, QuickSettingsTileSlots.normalize(QuickSettingsTileSlots.COUNT))
        assertNull(QuickSettingsTileSlots.normalize(0))
        assertEquals(3, QuickSettingsTileSlots.slotForComponent(QuickSettingsTileServiceSlot3::class.java.name))
        assertEquals(QuickSettingsTileServiceSlot4::class.java, QuickSettingsTileSlots.componentClass(4))
    }

    @Test
    fun stateUpdatesAreBoundedAndRejectUnknownIcons() {
        val current = QuickSettingsTileConfig(slot = 2, taskId = 7, taskName = "Focus")
        val updated = updateQuickSettingsTileConfig(
            current = current,
            active = true,
            label = " Focus\u0000 mode ",
            subtitle = "x".repeat(100),
            iconKey = "unknown",
        )

        assertTrue(updated.active)
        assertEquals("Focus mode", updated.label)
        assertEquals(80, updated.subtitle.length)
        assertEquals(QuickSettingsTileIcons.DEFAULT, updated.iconKey)
    }

    @Test
    fun tileEventsCarrySlotAndState() {
        val event = QuickSettingsTileContextEvents.buildEvent(active = true, slot = 3, nowMs = 42L)

        assertEquals("tile_clicked", event.metadata["event"])
        assertEquals("3", event.metadata["tileSlot"])
        assertEquals("true", event.metadata["tileActive"])
        assertEquals("42", event.metadata["observedAtEpochMs"])
    }
}
