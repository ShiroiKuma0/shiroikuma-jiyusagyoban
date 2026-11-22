package com.opentasker.core.storage

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A restore staged from a portable backup is plaintext SQLite in `files/backups`. Once it's applied
 * it's renamed off the journal name and shredded, so nothing of it stays there.
 */
class RestoreJournalShredTest {
    private val plaintext = "SQLite format 3".toByteArray() + ByteArray(5_000) { 7 }

    private fun <T> inTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("restore-journal").toFile()
        try {
            return block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anAppliedJournalLeavesNothingBehind() = inTempDir { dir ->
        val pending = File(dir, "opentasker_restore_pending.db").apply { writeBytes(plaintext) }
        val spent = retireRestoreJournal(pending)
        // Off the journal name first, so the next start won't apply it again.
        assertFalse(pending.exists())
        shredFile(spent)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun aJournalADeathLeftMidShredGoesAtTheNextStart() = inTempDir { dir ->
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val spent = spentRestoreJournal(DatabaseBackupManager.pendingRestoreFile(context)).apply { writeBytes(plaintext) }
        assertEquals(PendingRestoreApplyResult.NoPending, DatabaseBackupManager.applyPendingRestoreIfPresent(context))
        assertFalse("a spent journal stayed in files/backups", spent.exists())
    }
}
