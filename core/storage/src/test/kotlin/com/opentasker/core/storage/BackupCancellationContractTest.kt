package com.opentasker.core.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.readText
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup and restore are deliberately uninterruptible; see the decision log for the reasoning.
 *
 * The tests here pin the two facts that make that decision safe. A write that stops partway
 * through really does leave an unusable artifact behind, and the one production caller that
 * writes outside app-private storage removes what it created when the write does not finish,
 * cancellation included.
 */
class BackupCancellationContractTest {

    private val passphrase get() = "correct horse battery".toCharArray()

    @Test
    fun anInterruptedEncryptedExportLeavesAnUnrestorableArtifactBehind() {
        val plaintext = ByteArray(BackupEncryption.STREAM_CHUNK_BYTES * 3) { (it % 251).toByte() }
        val destination = ByteArrayOutputStream()

        val exportError = runCatching {
            BackupEncryption.encrypt(
                plainInput = ByteArrayInputStream(plaintext),
                output = destination,
                passphrase = passphrase,
                cancellationCheck = {
                    if (destination.size() > BackupEncryption.STREAM_CHUNK_BYTES) {
                        throw CancellationException("export stopped mid-stream")
                    }
                },
            )
        }.exceptionOrNull()

        assertTrue("expected the export to stop", exportError is CancellationException)
        assertTrue("expected bytes already at the destination", destination.size() > 0)

        val restoreError = runCatching {
            BackupEncryption.decrypt(
                ByteArrayInputStream(destination.toByteArray()),
                ByteArrayOutputStream(),
                passphrase,
            )
        }.exceptionOrNull()

        assertNotNull("a truncated export must not decrypt", restoreError)
    }

    @Test
    fun theSnapshotWorkerRemovesAnArchiveItsExportDidNotFinishWriting() {
        // Runs inside core:storage, so the file it inspects is a sibling of the test.
        val source = read("ConfigurationSnapshotWorker.kt")

        val createArchive = source.indexOf("archiveStore.createArchive(")
        assertTrue("snapshot worker no longer creates the archive first", createArchive >= 0)
        val export = source.indexOf("exportEncryptedBackup(", createArchive)
        assertTrue("the export no longer follows archive creation", export > createArchive)

        // Throwable, not Exception: a cancelled export must clean up like a failed one.
        val handler = blockAfter(
            source = source,
            start = "catch (error: Throwable)",
            end = "archiveStore.enforceRetention(",
            from = export,
        )
        assertTrue("a stopped export no longer deletes its archive", "archiveStore.deleteArchive(" in handler)
        assertTrue("the stop reason is no longer rethrown", "throw error" in handler)
    }

    @Test
    fun aFailedBackupCopyRemovesItsTemporaryFileAndTheSidecarsValidationLeftBehind() {
        val source = read("DatabaseBackupManager.kt")

        val publish = source.indexOf("publishValidatedBackup(context, tempFile, backupFile)")
        assertTrue("backup no longer publishes the validated copy", publish >= 0)

        val handler = blockAfter(
            source = source,
            start = "catch (error: Exception)",
            end = "AppLogger.info(tag, \"Database backed up to",
            from = publish,
        )
        assertTrue("an abandoned staged copy is no longer deleted", "tempFile.delete()" in handler)
        // Validating the staged copy opens it, so SQLite leaves -wal/-shm next to a .tmp whose
        // name carries a timestamp. Nothing enumerates those, so one failed backup used to cost
        // two files that no retention pass could ever reach.
        assertTrue("the staged copy's sidecars are no longer deleted", "deleteDatabaseSidecars(tempFile)" in handler)
        assertTrue("the failure is no longer rethrown", "throw error" in handler)
    }

    private fun read(fileName: String): String =
        Path.of("src/main/kotlin/com/opentasker/core/storage/$fileName").readText()

    /**
     * The text between [start] and the first [end] after it, searching from [from].
     *
     * Ordered `indexOf` calls are not enough on their own: an assertion that some later occurrence
     * of `throw error` or `tempFile.delete()` exists is satisfied by an unrelated one elsewhere in
     * the file, so deleting the real handler leaves the test green. Both of these files have such a
     * decoy, and both of these tests passed against a mutant before this was bounded.
     */
    private fun blockAfter(source: String, start: String, end: String, from: Int): String {
        val open = source.indexOf(start, from)
        assertTrue("no '$start' after offset $from", open >= 0)
        val close = source.indexOf(end, open)
        assertTrue("no '$end' closing the block that starts at $open", close > open)
        return source.substring(open, close)
    }
}
