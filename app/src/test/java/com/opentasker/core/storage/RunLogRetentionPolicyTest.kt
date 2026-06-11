package com.opentasker.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class RunLogRetentionPolicyTest {
    @Test
    fun normalizedClampsUnsafeValues() {
        val policy = RunLogRetentionPolicy(maxEntries = 1, maxAgeDays = 0).normalized()

        assertEquals(50, policy.maxEntries)
        assertEquals(1, policy.maxAgeDays)
    }

    @Test
    fun minimumTimestampUsesConfiguredAgeWindow() {
        val now = TimeUnit.DAYS.toMillis(40)
        val policy = RunLogRetentionPolicy(maxEntries = 1_000, maxAgeDays = 30)

        assertEquals(TimeUnit.DAYS.toMillis(10), policy.minimumTimestamp(now))
    }

    @Test
    fun defaultPolicyDocumentsThirtyDaysOrOneThousandEntries() {
        assertEquals("30 days or 1,000 entries", RunLogRetentionPolicy().displayLabel())
    }
}
