package com.opentasker.core.storage

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts `.otbackup` files using AES-256-GCM with PBKDF2-derived keys.
 *
 * File format:
 * - 4 bytes: magic `OTBK`
 * - 4 bytes: format version (big-endian int, currently 1)
 * - 32 bytes: PBKDF2 salt
 * - 12 bytes: AES-GCM IV
 * - remaining: AES-GCM ciphertext (includes 16-byte auth tag)
 */
object BackupEncryption {

    private val MAGIC = byteArrayOf('O'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val FORMAT_VERSION = 1
    private const val SALT_LENGTH = 32
    private const val IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_LENGTH_BITS = 256
    private const val PBKDF2_ITERATIONS = 600_000
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_FACTORY = "PBKDF2WithHmacSHA256"

    fun encrypt(plainInput: InputStream, output: OutputStream, passphrase: CharArray) {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        val plainBytes = plainInput.use { it.readBounded() }
        val ciphertext = cipher.doFinal(plainBytes)

        output.write(MAGIC)
        output.write(intToBytes(FORMAT_VERSION))
        output.write(salt)
        output.write(iv)
        output.write(ciphertext)
        output.flush()
    }

    fun decrypt(encryptedInput: InputStream, output: OutputStream, passphrase: CharArray) {
        val magic = encryptedInput.readExact(4)
        if (!magic.contentEquals(MAGIC)) {
            throw IOException("Not a valid .otbackup file (bad magic)")
        }
        val version = bytesToInt(encryptedInput.readExact(4))
        if (version != FORMAT_VERSION) {
            throw IOException("Unsupported .otbackup format version: $version")
        }

        val salt = encryptedInput.readExact(SALT_LENGTH)
        val iv = encryptedInput.readExact(IV_LENGTH)
        val ciphertext = encryptedInput.use { it.readBounded() }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        val plainBytes = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw IOException("Decryption failed — wrong passphrase or corrupted file", e)
        }

        output.write(plainBytes)
        output.flush()
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(KEY_FACTORY)
        val keyBytes = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte(),
    )

    private fun bytesToInt(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)

    private fun InputStream.readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val count = this.read(buf, read, n - read)
            if (count < 0) throw IOException("Unexpected end of .otbackup file")
            read += count
        }
        return buf
    }

    private fun InputStream.readBounded(maxBytes: Long = DatabaseBackupManager.MAX_BACKUP_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw IOException("Backup file exceeds ${maxBytes / 1024 / 1024} MB limit")
            }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
