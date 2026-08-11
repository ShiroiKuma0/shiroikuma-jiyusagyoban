package com.opentasker.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationInvariantPolicyTest {
    @Test
    fun normalizationRejectsMalformedEntriesAndAssignsStablePositiveIds() {
        val normalized = AutomationInvariantPolicy.normalize(
            listOf(
                AutomationInvariant(
                    id = 0,
                    name = "  Charging  ",
                    guard = InvariantStatePredicate(key = "charging", value = "true"),
                    forbiddenWriteKey = " brightness ",
                ),
                AutomationInvariant(name = "", guard = InvariantStatePredicate(key = "charging", value = "true"), forbiddenWriteKey = "wifi"),
                AutomationInvariant(
                    id = 1,
                    name = "Wifi",
                    guard = InvariantStatePredicate(key = "wifi", value = "connected"),
                    forbiddenWriteKey = "wifi",
                ),
                AutomationInvariant(
                    id = 1,
                    name = "Wifi duplicate id",
                    guard = InvariantStatePredicate(key = "wifi", value = "connected"),
                    forbiddenWriteKey = "wifi",
                ),
            ),
        )

        assertEquals(3, normalized.size)
        assertTrue(normalized.all { it.id > 0L })
        assertEquals("Charging", normalized.first().name)
        assertEquals("brightness", normalized.first().forbiddenWriteKey)
        assertFalse(normalized.map(AutomationInvariant::id).let { it.size != it.toSet().size })
    }

    @Test
    fun normalizationIsBounded() {
        val entries = (1..(AutomationInvariantPolicy.MAX_INVARIANTS + 10)).map { index ->
            AutomationInvariant(
                name = "Invariant $index",
                guard = InvariantStatePredicate(key = "state_$index", value = "true"),
                forbiddenWriteKey = "wifi",
            )
        }

        assertEquals(AutomationInvariantPolicy.MAX_INVARIANTS, AutomationInvariantPolicy.normalize(entries).size)
    }
}
