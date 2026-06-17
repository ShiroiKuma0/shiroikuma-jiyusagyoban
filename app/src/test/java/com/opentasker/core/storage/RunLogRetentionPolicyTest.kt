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

    @Test
    fun normalizedClampsExcessiveValues() {
        val policy = RunLogRetentionPolicy(maxEntries = 999_999, maxAgeDays = 9999).normalized()
        assertEquals(10_000, policy.maxEntries)
        assertEquals(365, policy.maxAgeDays)
    }

    @Test
    fun normalizedPreservesValidValues() {
        val policy = RunLogRetentionPolicy(maxEntries = 500, maxAgeDays = 14).normalized()
        assertEquals(500, policy.maxEntries)
        assertEquals(14, policy.maxAgeDays)
    }

    @Test
    fun normalizedClampsBoundaryValues() {
        val low = RunLogRetentionPolicy(maxEntries = 50, maxAgeDays = 1).normalized()
        assertEquals(50, low.maxEntries)
        assertEquals(1, low.maxAgeDays)

        val high = RunLogRetentionPolicy(maxEntries = 10_000, maxAgeDays = 365).normalized()
        assertEquals(10_000, high.maxEntries)
        assertEquals(365, high.maxAgeDays)
    }

    @Test
    fun minimumTimestampNormalizesBeforeComputing() {
        val now = TimeUnit.DAYS.toMillis(100)
        val badPolicy = RunLogRetentionPolicy(maxEntries = 1, maxAgeDays = 0)
        val timestamp = badPolicy.minimumTimestamp(now)
        assertEquals(TimeUnit.DAYS.toMillis(99), timestamp)
    }

    @Test
    fun retentionOptionsAllNormalize() {
        for (option in RunLogRetentionOptions.all) {
            val normalized = option.policy.normalized()
            assertEquals(option.policy.maxEntries, normalized.maxEntries)
            assertEquals(option.policy.maxAgeDays, normalized.maxAgeDays)
        }
    }

    @Test
    fun displayLabelShowsSingularForOne() {
        val policy = RunLogRetentionPolicy(maxEntries = 50, maxAgeDays = 1)
        assertEquals("1 day or 50 entries", policy.displayLabel())
    }
}
