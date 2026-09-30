package com.opentasker.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore leaves a pre-restore copy and, when it fails to apply, a failed-restore copy, and both
 * share the retention window with ordinary backups. The window keeps only the newest file of any
 * kind, so without a guard those copies could push out the last ordinary backup.
 */
class BackupRetentionTest {
    private val policy = ConfigurationSnapshotPolicy(enabled = true, maxSnapshots = 2, maxAgeDays = 365)

    @Test
    fun restoreCopiesNeverPushOutTheLastOrdinaryBackup() {
        val backups = listOf(
            SnapshotFile("opentasker_restore_failed_2026-09-30_09-00-00.db", NOW),
            SnapshotFile("opentasker_pre_restore_2026-09-30_08-00-00.db", NOW - HOUR),
            SnapshotFile("opentasker_backup_2026-09-30_07-00-00.db", NOW - 2 * HOUR),
            SnapshotFile("opentasker_backup_2026-09-29_07-00-00.db", NOW - 26 * HOUR),
        )

        val expired = DatabaseBackupManager.expiredBackupNames("opentasker.db", backups, policy, NOW)

        assertEquals(setOf("opentasker_backup_2026-09-29_07-00-00.db"), expired)
    }

    @Test
    fun ordinaryBackupsAloneFollowTheWindowUnchanged() {
        val backups = (0 until 4).map { day ->
            SnapshotFile("opentasker_backup_2026-09-${30 - day}_07-00-00.db", NOW - day * 24 * HOUR)
        }

        val expired = DatabaseBackupManager.expiredBackupNames("opentasker.db", backups, policy, NOW)

        assertEquals(
            selectExpiredSnapshots(backups, policy, NOW).map(SnapshotFile::name).toSet(),
            expired,
        )
        assertEquals(2, expired.size)
    }

    @Test
    fun onlyOrdinaryBackupsCountAsTheLatestBackup() {
        assertTrue(DatabaseBackupManager.isRegularBackupName("opentasker.db", "opentasker_backup_2026-09-30_07-00-00.db"))
        assertFalse(DatabaseBackupManager.isRegularBackupName("opentasker.db", "opentasker_pre_restore_2026-09-30_07-00-00.db"))
        assertFalse(DatabaseBackupManager.isRegularBackupName("opentasker.db", "opentasker_restore_failed_2026-09-30_07-00-00.db"))
        assertFalse(DatabaseBackupManager.isRegularBackupName("opentasker.db", "other_backup_2026-09-30_07-00-00.db"))
    }

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val HOUR = 60L * 60L * 1_000L
    }
}
