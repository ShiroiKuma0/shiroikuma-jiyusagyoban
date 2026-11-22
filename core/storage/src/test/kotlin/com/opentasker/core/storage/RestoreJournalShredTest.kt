package com.opentasker.core.storage

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun theShredOverwritesEveryByteBeforeTheFileGoes() = inTempDir { dir ->
        val journal = File(dir, "opentasker_restore_pending.db.spent").apply { writeBytes(plaintext) }
        // A second name for the same bytes still reads them once the first name is deleted.
        val sameBytes = Files.createLink(File(dir, "same-bytes.db").toPath(), journal.toPath()).toFile()
        shredFile(journal)
        assertFalse(journal.exists())
        val left = sameBytes.readBytes()
        assertEquals(plaintext.size, left.size)
        assertTrue("plaintext survived the shred", left.all { it == 0.toByte() })
    }

    @Test
    fun aJournalADeathLeftMidShredGoesAtTheNextStart() = inTempDir { dir ->
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val spent = spentRestoreJournal(DatabaseBackupManager.pendingRestoreFile(context)).apply { writeBytes(plaintext) }
        // A spent journal is only there once its database went in, so the start says that run
        // stopped after the swap, and the app clears the grants that run never got to.
        assertEquals(PendingRestoreApplyResult.StoppedAfterSwap, DatabaseBackupManager.applyPendingRestoreIfPresent(context))
        assertFalse("a spent journal stayed in files/backups", spent.exists())
    }

    @Test
    fun aStartWithNoJournalAtAllHasNothingToReport() = inTempDir { dir ->
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        assertEquals(PendingRestoreApplyResult.NoPending, DatabaseBackupManager.applyPendingRestoreIfPresent(context))
    }
}
