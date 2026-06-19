package com.opentasker.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

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
}
