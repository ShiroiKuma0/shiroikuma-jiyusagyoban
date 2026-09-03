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
        val source = Path.of("src/main/kotlin/com/opentasker/core/storage/ConfigurationSnapshotWorker.kt")
            .readText()

        val createArchive = source.indexOf("archiveStore.createArchive(")
        val export = source.indexOf("exportEncryptedBackup(", createArchive)
        // Throwable, not Exception: a cancelled export must clean up like a failed one.
        val catchThrowable = source.indexOf("catch (error: Throwable)", export)
        val deleteArchive = source.indexOf("archiveStore.deleteArchive(", catchThrowable)
        val rethrow = source.indexOf("throw error", deleteArchive)

        assertTrue("snapshot worker no longer creates the archive first", createArchive >= 0)
        assertTrue("the export no longer follows archive creation", export > createArchive)
        assertTrue("the export is no longer guarded against every throwable", catchThrowable > export)
        assertTrue("a stopped export no longer deletes its archive", deleteArchive > catchThrowable)
        assertTrue("the stop reason is no longer rethrown", rethrow > deleteArchive)
    }

    @Test
    fun aFailedBackupCopyRemovesItsTemporaryFile() {
        val source = Path.of("src/main/kotlin/com/opentasker/core/storage/DatabaseBackupManager.kt")
            .readText()

        val tempCreated = source.indexOf("val tempFile = File(backupDir,")
        val publish = source.indexOf("publishValidatedBackup(context, tempFile, backupFile)", tempCreated)
        val catchBlock = source.indexOf("catch (error: Exception)", publish)
        val tempDeleted = source.indexOf("tempFile.delete()", catchBlock)

        assertTrue("backup no longer stages through a temporary file", tempCreated >= 0)
        assertTrue("backup no longer publishes the validated copy", publish > tempCreated)
        assertTrue("the staged copy is no longer guarded", catchBlock > publish)
        assertTrue("an abandoned staged copy is no longer deleted", tempDeleted > catchBlock)
    }
}
