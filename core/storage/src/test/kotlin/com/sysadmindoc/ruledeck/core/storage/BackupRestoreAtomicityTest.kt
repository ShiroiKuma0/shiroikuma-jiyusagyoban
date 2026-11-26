package com.sysadmindoc.ruledeck.core.storage

import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupRestoreAtomicityTest {
    @Test
    fun atomicReplacementPublishesTheCompleteSource() {
        val directory = Files.createTempDirectory("ruledeck-atomic-restore").toFile()
        try {
            val source = directory.resolve("source.tmp").apply { writeText("new database") }
            val target = directory.resolve("live.db").apply { writeText("old database") }

            replaceFileAtomically(source, target, "test restore")

            assertEquals("new database", target.readText())
            assertFalse(source.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedAtomicReplacementLeavesTheExistingTargetUntouched() {
        val directory = Files.createTempDirectory("ruledeck-atomic-restore-failure").toFile()
        try {
            val missingSource = directory.resolve("missing.tmp")
            val target = directory.resolve("live.db").apply { writeText("keep database") }

            try {
                replaceFileAtomically(missingSource, target, "test restore")
                fail("Expected missing source failure")
            } catch (_: IOException) {
                // Expected.
            }

            assertEquals("keep database", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupRestoreKeepsTheJournalUntilAfterAtomicPublication() {
        // Runs inside core:storage, so the file it inspects is a sibling of the test.
        val source = java.nio.file.Path.of("src/main/kotlin/com/sysadmindoc/ruledeck/core/storage/DatabaseBackupManager.kt")
            .readText()
        val transactionStart = source.indexOf("The pending file is the durable restore journal")
        val atomicPublish = source.indexOf("replaceFileAtomically(temp, dbFile", transactionStart)
        val sidecarCleanup = source.indexOf("deleteDatabaseSidecars(dbFile)", atomicPublish)
        // Retired (renamed off its journal name) only once the new database is in place.
        val journalDelete = source.indexOf("retireRestoreJournal(pending)", sidecarCleanup)

        assertTrue(transactionStart >= 0)
        assertTrue(atomicPublish > transactionStart)
        assertTrue(sidecarCleanup > atomicPublish)
        assertTrue(journalDelete > sidecarCleanup)
        // The spent journal is shredded only after the rollback is recorded, and its commit checked.
        // Recording is a local function, since a failed restore that keeps its database tries it again.
        val recorder = source.indexOf("fun recordRollback(): Boolean")
        assertTrue("recording the rollback commits", recorder >= 0 && source.indexOf("commit()", recorder) in recorder until transactionStart)
        val rollbackRecorded = source.indexOf("rollbackRecorded = recordRollback()", journalDelete)
        val commitChecked = source.indexOf("if (!rollbackRecorded", rollbackRecorded)
        val journalShred = source.indexOf("shredFile(spent)", commitChecked)
        assertTrue(rollbackRecorded > journalDelete)
        assertTrue(commitChecked > rollbackRecorded)
        assertTrue(journalShred > commitChecked)
        assertFalse("nothing shreds before the rollback is recorded", source.substring(journalDelete, commitChecked).contains("shredFile("))
        assertFalse(
            "The live database must never be deleted before its replacement is ready",
            source.substring(transactionStart, journalDelete).contains("dbFile.delete()"),
        )
    }
}
