package com.opentasker.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.Arrays
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupEncryptionTest {

    @Test
    fun roundTripPreservesContent() {
        val original = "Hello, OpenTasker backup! 🔒".toByteArray()
        val passphrase = "test-passphrase-123".toCharArray()

        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, passphrase)

        val decrypted = ByteArrayOutputStream()
        BackupEncryption.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, passphrase)

        assertArrayEquals(original, decrypted.toByteArray())
    }

    @Test
    fun wrongPassphraseFailsDecryption() {
        val original = "Secret data".toByteArray()
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, "correct".toCharArray())

        try {
            val decrypted = ByteArrayOutputStream()
            BackupEncryption.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, "wrong".toCharArray())
            fail("Expected IOException for wrong passphrase")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Decryption failed"))
        }
    }

    @Test
    fun invalidMagicRejectsFile() {
        val garbage = ByteArray(100) { it.toByte() }
        try {
            BackupEncryption.decrypt(ByteArrayInputStream(garbage), ByteArrayOutputStream(), "pass".toCharArray())
            fail("Expected IOException for bad magic")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("bad magic"))
        }
    }

    @Test
    fun emptyPayloadRoundTrips() {
        val original = ByteArray(0)
        val passphrase = "empty".toCharArray()

        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, passphrase)

        val decrypted = ByteArrayOutputStream()
        BackupEncryption.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, passphrase)

        assertArrayEquals(original, decrypted.toByteArray())
    }

    @Test
    fun largePayloadRoundTrips() {
        val original = ByteArray(1_000_000) { (it % 256).toByte() }
        val passphrase = "large-file".toCharArray()

        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), encrypted, passphrase)

        assertTrue(encrypted.size() > original.size)

        val decrypted = ByteArrayOutputStream()
        BackupEncryption.decrypt(ByteArrayInputStream(encrypted.toByteArray()), decrypted, passphrase)

        assertArrayEquals(original, decrypted.toByteArray())
    }

    @Test
    fun encryptedOutputStartsWithMagic() {
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream("data".toByteArray()), encrypted, "pass".toCharArray())
        val bytes = encrypted.toByteArray()
        assertTrue(bytes.size >= 52)
        assertTrue(bytes[0] == 'O'.code.toByte())
        assertTrue(bytes[1] == 'T'.code.toByte())
        assertTrue(bytes[2] == 'B'.code.toByte())
        assertTrue(bytes[3] == 'K'.code.toByte())
        assertEquals(BackupEncryption.CURRENT_FORMAT_VERSION, bytes.readInt(4))
    }

    @Test
    fun differentEncryptionsProduceDifferentOutput() {
        val original = "same data".toByteArray()
        val passphrase = "same-pass".toCharArray()

        val enc1 = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), enc1, passphrase)

        val enc2 = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(original), enc2, passphrase)

        val bytes1 = enc1.toByteArray()
        val bytes2 = enc2.toByteArray()
        assertTrue(bytes1.size == bytes2.size)
        var differ = false
        for (i in bytes1.indices) {
            if (bytes1[i] != bytes2[i]) { differ = true; break }
        }
        assertTrue("Same plaintext + passphrase should produce different ciphertext (random salt/IV)", differ)
    }

    @Test
    fun legacyV1BackupRemainsRestorable() {
        val original = "legacy OpenTasker backup".toByteArray()
        val passphrase = "legacy-passphrase".toCharArray()

        val restored = ByteArrayOutputStream()
        BackupEncryption.decrypt(ByteArrayInputStream(legacyV1Backup(original, passphrase)), restored, passphrase)

        assertArrayEquals(original, restored.toByteArray())
    }

    @Test
    fun firstFrameTagFailurePublishesNoPlaintext() {
        val encrypted = encryptedBytes("authenticated data".toByteArray())
        encrypted[V2_HEADER_BYTES + Int.SIZE_BYTES + 3] =
            (encrypted[V2_HEADER_BYTES + Int.SIZE_BYTES + 3].toInt() xor 0x40).toByte()
        val restored = ByteArrayOutputStream()

        expectDecryptionFailure(encrypted, restored)

        assertEquals(0, restored.size())
    }

    @Test
    fun truncationAndTrailingDataAreRejected() {
        val encrypted = encryptedBytes("complete".toByteArray())

        expectDecryptionFailure(encrypted.copyOf(encrypted.size - 1), ByteArrayOutputStream())
        expectDecryptionFailure(encrypted + byteArrayOf(0x01), ByteArrayOutputStream())
    }

    @Test
    fun plaintextLimitFailsBeforeADataFrameIsPublished() {
        val encrypted = ByteArrayOutputStream()

        try {
            BackupEncryption.encrypt(
                ByteArrayInputStream(ByteArray(1_001)),
                encrypted,
                "bounded".toCharArray(),
                maxPlaintextBytes = 1_000,
            )
            fail("Expected an IOException for an oversized plaintext")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("exceeds"))
        }

        assertEquals(V2_HEADER_BYTES, encrypted.size())
    }

    @Test
    fun cancellationStopsStreamingBeforeTheInputIsConsumed() {
        val input = GeneratedInputStream(BackupEncryption.STREAM_CHUNK_BYTES.toLong() * 4)
        var checks = 0

        try {
            BackupEncryption.encrypt(
                input,
                CountingOutputStream(),
                "cancel".toCharArray(),
                cancellationCheck = {
                    checks += 1
                    if (checks >= 4) throw CancellationException("cancelled")
                },
            )
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is never wrapped as a generic backup failure.
        }

        assertTrue(input.remaining > 0)
    }

    @Test
    fun nearCapRoundTripKeepsExplicitBuffersBelowThreeFrames() {
        val plaintextBytes = DatabaseBackupManager.MAX_BACKUP_BYTES - 1_024
        val generated = GeneratedInputStream(plaintextBytes)
        val encryptedFile = Files.createTempFile("opentasker-near-cap", ".otbackup").toFile()
        try {
            FileOutputStream(encryptedFile).use { output ->
                BackupEncryption.encrypt(generated, output, "near-cap".toCharArray())
            }
            val restored = CountingOutputStream()
            encryptedFile.inputStream().use { input ->
                BackupEncryption.decrypt(input, restored, "near-cap".toCharArray())
            }

            assertEquals(plaintextBytes, restored.count)
            assertTrue(generated.maxRequestedBytes <= BackupEncryption.STREAM_CHUNK_BYTES)
            assertTrue(restored.maxWriteBytes <= BackupEncryption.STREAM_CHUNK_BYTES)
            assertTrue(
                BackupEncryption.MAX_EXPLICIT_WORKING_BUFFER_BYTES < BackupEncryption.STREAM_CHUNK_BYTES * 3,
            )
        } finally {
            encryptedFile.delete()
        }
    }

    private fun encryptedBytes(plaintext: ByteArray, passphrase: String = TEST_PASSPHRASE): ByteArray {
        val output = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(plaintext), output, passphrase.toCharArray())
        return output.toByteArray()
    }

    private fun expectDecryptionFailure(encrypted: ByteArray, output: OutputStream) {
        try {
            BackupEncryption.decrypt(ByteArrayInputStream(encrypted), output, TEST_PASSPHRASE.toCharArray())
            fail("Expected encrypted backup rejection")
        } catch (_: IOException) {
            // Expected.
        }
    }

    /** Deterministic fixture generator for the shipped v1 whole-file AES-GCM contract. */
    private fun legacyV1Backup(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { (it + 32).toByte() }
        val spec = PBEKeySpec(passphrase, salt, 600_000, 256)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
        spec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte()) +
            byteArrayOf(0, 0, 0, 1) + salt + iv + cipher.doFinal(plaintext)
    }

    private fun ByteArray.readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private class GeneratedInputStream(byteCount: Long) : InputStream() {
        var remaining: Long = byteCount
            private set
        var maxRequestedBytes: Int = 0
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining -= 1
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            maxRequestedBytes = maxOf(maxRequestedBytes, length)
            val count = minOf(length.toLong(), remaining).toInt()
            Arrays.fill(buffer, offset, offset + count, 0.toByte())
            remaining -= count
            return count
        }
    }

    private class CountingOutputStream : OutputStream() {
        var count: Long = 0
            private set
        var maxWriteBytes: Int = 0
            private set

        override fun write(value: Int) {
            count += 1
            maxWriteBytes = maxOf(maxWriteBytes, 1)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            count += length
            maxWriteBytes = maxOf(maxWriteBytes, length)
        }
    }

    private companion object {
        const val TEST_PASSPHRASE = "test-passphrase"
        const val V2_HEADER_BYTES = 4 + 4 + 32 + 8 + 4
    }
}
