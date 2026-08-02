package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRecordingContextEventsTest {
    @Test
    fun visibleStateMapsToRecordingVisibleEvent() {
        val event = ScreenRecordingContextEvents.buildEvent(isVisible = true)

        assertEquals("screen_recording", event.metadata["event"])
        assertEquals("visible", event.metadata["state"])
        assertEquals("true", event.metadata["recording"])
        assertTrue(event.matched)
    }

    @Test
    fun notVisibleStateMapsToRecordingNotVisibleEvent() {
        val event = ScreenRecordingContextEvents.buildEvent(isVisible = false)

        assertEquals("not_visible", event.metadata["state"])
        assertEquals("false", event.metadata["recording"])
    }
}
