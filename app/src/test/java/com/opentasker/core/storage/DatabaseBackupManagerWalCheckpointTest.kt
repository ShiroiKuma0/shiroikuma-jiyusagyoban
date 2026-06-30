package com.opentasker.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseBackupManagerWalCheckpointTest {
    @Test
    fun checkpointStatusOnlyAllowsMainFileCopyWhenWalIsComplete() {
        assertTrue(WalCheckpointStatus(busy = 0, logFrames = 0, checkpointedFrames = 0).readyForMainFileCopy)
        assertTrue(WalCheckpointStatus(busy = 0, logFrames = 12, checkpointedFrames = 12).readyForMainFileCopy)
        assertFalse(WalCheckpointStatus(busy = 1, logFrames = 12, checkpointedFrames = 12).readyForMainFileCopy)
        assertFalse(WalCheckpointStatus(busy = 0, logFrames = 12, checkpointedFrames = 7).readyForMainFileCopy)
    }

    @Test
    fun busyCheckpointMessageIdentifiesWalRaceAndDatabasePath() {
        val message = walCheckpointIncompleteMessage(
            databaseName = "opentasker.db",
            sourcePath = "/data/data/com.opentasker.app/databases/opentasker.db",
            status = WalCheckpointStatus(busy = 1, logFrames = 12, checkpointedFrames = 7),
        )

        assertTrue(message.contains("WAL checkpoint did not complete"))
        assertTrue(message.contains("opentasker.db"))
        assertTrue(message.contains("retry backup after current database reads finish"))
        assertTrue(message.contains("busy=1"))
    }
}
