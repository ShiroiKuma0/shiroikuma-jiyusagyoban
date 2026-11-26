package com.sysadmindoc.ruledeck.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSnapshotsTest {
    /**
     * A folder that won't delete an old archive used to fail the whole run after a good export, and
     * WorkManager's retries each wrote another full archive into it.
     */
    @Test
    fun aDeleteTheFolderRefusesIsCountedAndTheRestStillGo() {
        // Two snapshots is the least the policy keeps, so the last three expire.
        val snapshots = listOf(
            SnapshotFile(name = "newest", lastModifiedMs = NOW, sizeBytes = 1),
            SnapshotFile(name = "kept", lastModifiedMs = NOW - HOUR, sizeBytes = 2),
            SnapshotFile(name = "refused", lastModifiedMs = NOW - 2 * HOUR, sizeBytes = 10),
            SnapshotFile(name = "throws", lastModifiedMs = NOW - 3 * HOUR, sizeBytes = 100),
            SnapshotFile(name = "deleted", lastModifiedMs = NOW - 4 * HOUR, sizeBytes = 1_000),
        )
        val attempted = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val inventory = applySnapshotRetention(
            snapshots,
            fileOf = { it },
            policy = ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 2, maxAgeDays = 365),
            nowMs = NOW,
            onDeleteError = { file, _ -> errors += file.name },
        ) { file ->
            attempted += file.name
            when (file.name) {
                "refused" -> false
                "throws" -> throw UnsupportedOperationException("Delete not supported")
                else -> true
            }
        }

        assertEquals(listOf("refused", "throws", "deleted"), attempted)
        assertEquals(listOf("throws"), errors)
        assertEquals(
            ConfigurationSnapshotArchiveInventory(snapshotCount = 4, storageBytes = 113, removedCount = 1, refusedCount = 2),
            inventory,
        )
    }

    /** Lost access to the folder is not a refused delete: the run stops and says to pick it again. */
    @Test
    fun losingTheFolderDuringRetentionStillStopsTheRun() {
        val snapshots = listOf(
            SnapshotFile(name = "newest", lastModifiedMs = NOW),
            SnapshotFile(name = "kept", lastModifiedMs = NOW - HOUR),
            SnapshotFile(name = "old", lastModifiedMs = NOW - 2 * HOUR),
        )

        val error = runCatching {
            applySnapshotRetention(
                snapshots,
                fileOf = { it },
                policy = ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 2, maxAgeDays = 365),
                nowMs = NOW,
            ) { throw SnapshotDestinationUnavailableException("gone") }
        }.exceptionOrNull()

        assertTrue(error is SnapshotDestinationUnavailableException)
    }

    /**
     * One file's delete used to read as the whole folder lost: Setup said to pick it again right
     * after a good snapshot, and the success went unrecorded.
     */
    @Test
    fun oneFileGoneOrRefusedIsNotTheFolderLost() {
        val gone = SnapshotDestinationUnavailableException("lost", java.io.FileNotFoundException("gone"))
        val refused = SnapshotDestinationUnavailableException("lost", SecurityException("no"))

        assertTrue(singleArchiveDeleteOutcome(gone, stillGranted = true))
        assertTrue(!singleArchiveDeleteOutcome(refused, stillGranted = true))
        val lost = runCatching { singleArchiveDeleteOutcome(refused, stillGranted = false) }.exceptionOrNull()
        assertTrue(lost === refused)
    }

    /** A folder that couldn't be listed after a good export used to fail the run, and each retry wrote another archive. */
    @Test
    fun retentionThatCantListTheFolderStillLetsTheRunSucceed() {
        val lastRun = ConfigurationSnapshotArchiveInventory(snapshotCount = 3, storageBytes = 30, removedCount = 0)
        val errors = mutableListOf<Exception>()

        val inventory = retainAfterExport(fallback = { lastRun }, onError = { errors += it }) {
            throw java.io.IOException("The snapshot folder could not be read")
        }

        assertEquals(lastRun, inventory)
        assertEquals(1, errors.size)
        val lost = runCatching {
            retainAfterExport(fallback = { lastRun }, onError = { errors += it }) {
                throw SnapshotDestinationUnavailableException("gone")
            }
        }.exceptionOrNull()
        assertTrue(lost is SnapshotDestinationUnavailableException)
        assertEquals(1, errors.size)
    }

    /** The stand-in used to drop the refused count, so Setup's note went away while those files stayed. */
    @Test
    fun aRunThatCantListTheFolderKeepsTheRefusedCountAndCountsItsOwnSnapshot() {
        val lastRun = ConfigurationSnapshotStatus(snapshotCount = 5, storageBytes = 50, refusedDeletes = 3)

        assertEquals(
            ConfigurationSnapshotArchiveInventory(snapshotCount = 6, storageBytes = 50, removedCount = 0, refusedCount = 3),
            inventoryWithoutListing(lastRun),
        )
    }

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
        val tooSmall = ConfigurationSnapshotPolicy(
            maxSnapshots = 0,
            maxAgeDays = 0,
            destinationTreeUri = "",
        ).normalized()
        val tooLarge = ConfigurationSnapshotPolicy(maxSnapshots = 9_999, maxAgeDays = 9_999).normalized()

        assertEquals(ConfigurationSnapshotPolicy.MIN_SNAPSHOTS, tooSmall.maxSnapshots)
        assertEquals(ConfigurationSnapshotPolicy.MIN_AGE_DAYS, tooSmall.maxAgeDays)
        assertEquals(null, tooSmall.destinationTreeUri)
        assertEquals(ConfigurationSnapshotPolicy.MAX_SNAPSHOTS, tooLarge.maxSnapshots)
        assertEquals(ConfigurationSnapshotPolicy.MAX_AGE_DAYS, tooLarge.maxAgeDays)
    }

    @Test
    fun archiveNamesAreStableUtcV2BackupNames() {
        val name = configurationSnapshotArchiveName(1_700_000_000_123L)

        assertEquals("ruledeck_snapshot_2023-11-14_22-13-20_123Z.rdbackup", name)
        assertTrue(isConfigurationSnapshotArchive(name))
        assertEquals(1_700_000_000_123L, configurationSnapshotTimestamp(name))
    }

    @Test
    fun archiveRetentionNeverClaimsUnrelatedOrPartialFiles() {
        assertTrue(isConfigurationSnapshotArchive("ruledeck_snapshot_2026-08-12_12-00-00_000Z.rdbackup"))
        assertTrue(!isConfigurationSnapshotArchive("family-photos.rdbackup"))
        assertTrue(!isConfigurationSnapshotArchive("ruledeck_snapshot_2026-08-12.partial"))
        assertEquals(null, configurationSnapshotTimestamp("ruledeck_snapshot_invalid.rdbackup"))
    }

    private companion object {
        const val HOUR = 60L * 60L * 1_000L
        const val DAY = 24L * HOUR
        const val NOW = 1_700_000_000_000L
    }
}
