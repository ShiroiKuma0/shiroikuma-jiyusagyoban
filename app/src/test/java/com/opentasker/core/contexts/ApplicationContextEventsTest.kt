package com.opentasker.core.contexts

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationContextEventsTest {
    @Test
    fun blankForegroundPackageIsIgnored() {
        assertFalse(ApplicationContextEvents.publishForeground(" "))
    }

    @Test
    fun foregroundPackagePublishesAppContextEvent() = runBlocking {
        assertTrue(ApplicationContextEvents.publishForeground(" com.example.app "))

        val event = ApplicationContextEvents.events.drop(1).first()

        assertEquals("app", event.type)
        assertTrue(event.matched)
        assertEquals("com.example.app", event.metadata["foreground"])
    }
}
