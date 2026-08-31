package com.opentasker.core.storage

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
        val directory = Files.createTempDirectory("opentasker-atomic-restore").toFile()
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
        val directory = Files.createTempDirectory("opentasker-atomic-restore-failure").toFile()
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
        // Upstream runs this inside core:storage, where the subject is a sibling of the test. The
        // fork compiles those sources in :app (see settings.gradle.kts), so both layouts are tried.
        val source = listOf(
            java.nio.file.Path.of("src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt"),
            java.nio.file.Path.of("app/src/main/java/com/opentasker/core/storage/DatabaseBackupManager.kt"),
            java.nio.file.Path.of("src/main/kotlin/com/opentasker/core/storage/DatabaseBackupManager.kt"),
        ).first { java.nio.file.Files.exists(it) }.readText()
        val transactionStart = source.indexOf("The pending file is the durable restore journal")
        val atomicPublish = source.indexOf("replaceFileAtomically(temp, dbFile", transactionStart)
        val sidecarCleanup = source.indexOf("deleteDatabaseSidecars(dbFile)", atomicPublish)
        val journalDelete = source.indexOf("pending.delete()", sidecarCleanup)

        assertTrue(transactionStart >= 0)
        assertTrue(atomicPublish > transactionStart)
        assertTrue(sidecarCleanup > atomicPublish)
        assertTrue(journalDelete > sidecarCleanup)
        assertFalse(
            "The live database must never be deleted before its replacement is ready",
            source.substring(transactionStart, journalDelete).contains("dbFile.delete()"),
        )
    }
}
