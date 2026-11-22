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
        var drops = 0
        assertEquals(PendingRestoreApplyResult.NoPending, DatabaseBackupManager.applyPendingRestoreIfPresent(context) { drops++; true })
        assertEquals("nothing was replaced, so nothing is dropped", 0, drops)
    }

    /**
     * The spent journal is the only record that a restored database went in, so it goes only once
     * the replaced database's state is dropped on disk. Until then every start drops it again.
     */
    @Test
    fun aStoppedRestoreKeepsItsJournalUntilTheDropIsOnDisk() = inTempDir { dir ->
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val spent = spentRestoreJournal(DatabaseBackupManager.pendingRestoreFile(context)).apply { writeBytes(plaintext) }
        var drops = 0

        assertEquals(PendingRestoreApplyResult.StoppedAfterSwap, DatabaseBackupManager.applyPendingRestoreIfPresent(context) { drops++; false })
        assertTrue("a drop that isn't on disk keeps the journal", spent.exists())
        assertEquals(
            PendingRestoreApplyResult.StoppedAfterSwap,
            DatabaseBackupManager.applyPendingRestoreIfPresent(context) { drops++; throw IllegalStateException("prefs") },
        )
        assertTrue("so does one that throws", spent.exists())
        assertEquals(PendingRestoreApplyResult.StoppedAfterSwap, DatabaseBackupManager.applyPendingRestoreIfPresent(context) { drops++; true })
        assertFalse(spent.exists())
        assertEquals(3, drops)
        assertEquals(PendingRestoreApplyResult.NoPending, DatabaseBackupManager.applyPendingRestoreIfPresent(context) { drops++; true })
        assertEquals(3, drops)
    }

    /**
     * A review lives only as long as the process. A candidate still there at start is one nobody
     * staged or dismissed, and one decrypted from a snapshot is that snapshot in plaintext.
     */
    @Test
    fun aCandidateLeftByAStoppedReviewGoesAtTheNextStart() = inTempDir { dir ->
        val context = object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
        val candidate = DatabaseBackupManager.candidateRestoreFile(context).apply { writeBytes(plaintext) }
        val decryptTemp = File(candidate.parentFile, "${candidate.name}.decrypt.tmp").apply { writeBytes(plaintext) }
        val decryptSidecar = File(candidate.parentFile, "${decryptTemp.name}-shm").apply { writeBytes(plaintext) }
        val pending = DatabaseBackupManager.pendingRestoreFile(context).apply { writeBytes(plaintext) }
        val backup = File(candidate.parentFile, "opentasker_backup_2026-11-22_01-38-22.db").apply { writeBytes(plaintext) }

        DatabaseBackupManager.discardLeftoverRestoreCandidate(context)

        assertFalse("the candidate stayed", candidate.exists())
        assertFalse("the decrypt staging file stayed", decryptTemp.exists())
        assertFalse("the staging file's sidecar stayed", decryptSidecar.exists())
        assertTrue("a staged restore is the user's decision, not a leftover", pending.exists())
        assertTrue("backups aren't leftovers", backup.exists())
    }
}
