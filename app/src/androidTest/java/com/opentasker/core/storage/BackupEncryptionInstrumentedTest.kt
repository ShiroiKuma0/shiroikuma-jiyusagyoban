package com.opentasker.core.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackupEncryptionInstrumentedTest {

    @Test
    fun encryptDecryptRoundTripPreservesContent() {
        val plaintext = "OpenTasker backup test payload — profiles, tasks, and actions"
        val passphrase = "correct-horse-battery-staple".toCharArray()

        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(
            ByteArrayInputStream(plaintext.toByteArray()),
            encrypted,
            passphrase,
        )

        val decrypted = ByteArrayOutputStream()
        BackupEncryption.decrypt(
            ByteArrayInputStream(encrypted.toByteArray()),
            decrypted,
            passphrase,
        )

        assertArrayEquals(plaintext.toByteArray(), decrypted.toByteArray())
    }

    @Test
    fun decryptWithWrongPassphraseThrows() {
        val plaintext = "secret data"
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(
            ByteArrayInputStream(plaintext.toByteArray()),
            encrypted,
            "real-password".toCharArray(),
        )

        assertThrows(IOException::class.java) {
            BackupEncryption.decrypt(
                ByteArrayInputStream(encrypted.toByteArray()),
                ByteArrayOutputStream(),
                "wrong-password".toCharArray(),
            )
        }
    }

    @Test
    fun decryptInvalidMagicThrows() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03) + ByteArray(100)
        assertThrows(IOException::class.java) {
            BackupEncryption.decrypt(
                ByteArrayInputStream(garbage),
                ByteArrayOutputStream(),
                "any".toCharArray(),
            )
        }
    }
}
