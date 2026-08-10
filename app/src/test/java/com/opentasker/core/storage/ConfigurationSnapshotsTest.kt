package com.opentasker.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSnapshotsTest {
    @Test
    fun retentionKeepsTheNewestSnapshotsUpToTheCountLimit() {
        val snapshots = (0 until 8).map { index ->
            SnapshotFile(name = "snapshot-$index", lastModifiedMs = NOW - index * HOUR)
        }

        val expired = selectExpiredSnapshots(
            snapshots,
            ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 3, maxAgeDays = 365),
            nowMs = NOW,
        )

        assertEquals(
            listOf("snapshot-3", "snapshot-4", "snapshot-5", "snapshot-6", "snapshot-7"),
            expired.map(SnapshotFile::name),
        )
    }

    @Test
    fun retentionDropsSnapshotsOlderThanTheAgeWindow() {
        val snapshots = listOf(
            SnapshotFile(name = "recent", lastModifiedMs = NOW - HOUR),
            SnapshotFile(name = "yesterday", lastModifiedMs = NOW - 25 * HOUR),
            SnapshotFile(name = "ancient", lastModifiedMs = NOW - 40 * DAY),
        )

        val expired = selectExpiredSnapshots(
            snapshots,
            ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 30, maxAgeDays = 7),
            nowMs = NOW,
        )

        assertEquals(listOf("ancient"), expired.map(SnapshotFile::name))
    }

    /** One stale recovery point beats none, so age alone never empties the directory. */
    @Test
    fun theNewestSnapshotSurvivesEvenWhenItIsOlderThanTheWindow() {
        val snapshots = listOf(
            SnapshotFile(name = "only", lastModifiedMs = NOW - 400 * DAY),
            SnapshotFile(name = "older", lastModifiedMs = NOW - 500 * DAY),
        )

        val expired = selectExpiredSnapshots(
            snapshots,
            ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 10, maxAgeDays = 1),
            nowMs = NOW,
        )

        assertEquals(listOf("older"), expired.map(SnapshotFile::name))
    }

    @Test
    fun retentionIsAnEmptyDecisionForAnEmptyDirectory() {
        assertTrue(
            selectExpiredSnapshots(emptyList(), ConfigurationSnapshotPolicy(enabled = true), nowMs = NOW).isEmpty(),
        )
    }

    @Test
    fun policyValuesAreClampedToTheSupportedRange() {
        val tooSmall = ConfigurationSnapshotPolicy(maxSnapshots = 0, maxAgeDays = 0).normalized()
        val tooLarge = ConfigurationSnapshotPolicy(maxSnapshots = 9_999, maxAgeDays = 9_999).normalized()

        assertEquals(ConfigurationSnapshotPolicy.MIN_SNAPSHOTS, tooSmall.maxSnapshots)
        assertEquals(ConfigurationSnapshotPolicy.MIN_AGE_DAYS, tooSmall.maxAgeDays)
        assertEquals(ConfigurationSnapshotPolicy.MAX_SNAPSHOTS, tooLarge.maxSnapshots)
        assertEquals(ConfigurationSnapshotPolicy.MAX_AGE_DAYS, tooLarge.maxAgeDays)
    }

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
        const val NOW = 1_700_000_000_000L
    }
}
