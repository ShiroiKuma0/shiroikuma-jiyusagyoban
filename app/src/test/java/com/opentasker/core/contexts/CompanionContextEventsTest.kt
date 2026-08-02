package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionContextEventsTest {
    @Test
    fun buildEventCarriesPresenceAndAssociationIdentity() {
        val event = CompanionContextEvents.buildEvent(
            state = CompanionContextEvents.STATE_PRESENT,
            associationId = "42",
            label = "Watch",
        )

        assertEquals("companion_presence", event.metadata["event"])
        assertEquals("present", event.metadata["state"])
        assertEquals("42", event.metadata["associationId"])
        assertEquals("Watch", event.metadata["label"])
        assertTrue(event.metadata["observedAtEpochMs"].orEmpty().isNotBlank())
    }

    @Test
    fun blankIdentityFailsClosedToUnknown() {
        val event = CompanionContextEvents.buildEvent(
            state = CompanionContextEvents.STATE_ABSENT,
            associationId = "",
        )

        assertEquals(CompanionContextEvents.UNKNOWN_ASSOCIATION, event.metadata["associationId"])
        assertEquals("absent", event.metadata["state"])
    }
}
