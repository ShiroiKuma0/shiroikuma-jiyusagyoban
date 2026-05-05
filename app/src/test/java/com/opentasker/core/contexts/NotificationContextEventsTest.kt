package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationContextEventsTest {
    @Test
    fun buildEventSanitizesNotificationTextAndMarksNotificationEvent() {
        val event = NotificationContextEvents.buildEvent(
            packageName = " com.chat.example ",
            title = " Build\nfinished ",
            body = " Debug\tAPK   ready ",
        )

        assertEquals("event", event.type)
        assertTrue(event.matched)
        assertEquals("notification", event.metadata["event"])
        assertEquals("com.chat.example", event.metadata["package"])
        assertEquals("Build finished", event.metadata["title"])
        assertEquals("Debug APK ready", event.metadata["body"])
    }

    @Test
    fun notificationTextIsTruncatedForInspectorAndLogs() {
        val event = NotificationContextEvents.buildEvent(
            packageName = "com.chat.example",
            title = "x".repeat(400),
            body = "y".repeat(400),
        )

        assertEquals(240, event.metadata.getValue("title").length)
        assertEquals(240, event.metadata.getValue("body").length)
        assertFalse(event.metadata.getValue("title").contains("\n"))
    }
}
