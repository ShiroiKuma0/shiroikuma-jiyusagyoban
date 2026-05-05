package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginRequestQueryEventsTest {
    @Test
    fun buildsSanitizedRequestQueryEvent() {
        val event = LocalePluginRequestQueryEvents.buildEvent(
            activityClassName = " com.example.plugin.ConditionEditor ",
            bundleValues = mapOf("zeta" to "last", "alpha" to "first"),
        )

        requireNotNull(event)
        assertEquals("event", event.type)
        assertTrue(event.matched)
        assertEquals("locale_request_query", event.metadata["event"])
        assertEquals("com.example.plugin.ConditionEditor", event.metadata["activityClass"])
        assertEquals("""{"alpha":"first","zeta":"last"}""", event.metadata["bundleJson"])
    }

    @Test
    fun rejectsBlankOrInvalidActivityClassNames() {
        assertNull(LocalePluginRequestQueryEvents.buildEvent(""))
        assertNull(LocalePluginRequestQueryEvents.buildEvent("not a class"))
    }

    @Test
    fun normalizesInnerClassNames() {
        val normalized = LocalePluginRequestQueryEvents.normalizeActivityClassName(
            "com.example.plugin.ConditionEditor\$Nested",
        )

        assertEquals("com.example.plugin.ConditionEditor\$Nested", normalized)
    }
}
