package com.opentasker.core.storage

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thrown when the wrapped database key cannot be recovered.
 *
 * This is terminal by design. The SQLCipher key exists only inside the Keystore-wrapped blob, so
 * losing the master key (a Keystore reset, a restore onto another device, a corrupted blob) means
 * the encrypted database is unreadable and no amount of retrying will change that. Failing with a
 * named type rather than a bare IllegalStateException is what lets a caller tell this apart from a
 * transient error and offer the only real remedy: restore a backup, or start over.
 */
class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * The parts of unwrapping that do not need the Keystore, split out so the failure path is testable.
 *
 * Nothing here recovers from a bad payload. It decides whether the stored blob is even shaped like
 * one this app wrote, and says so with the terminal type.
 */
// Public because the device lane asserts the Keystore-loss decision; core:storage is a module.
object DatabaseKeyPayload {
    const val NONCE_BYTES = 12
    const val DATABASE_KEY_BYTES = 32

    fun requireWellFormed(payload: ByteArray): ByteArray {
        if (payload.size <= NONCE_BYTES) {
            throw DatabaseKeyUnavailableException("Stored database key is truncated")
        }
        return payload
    }

    fun requireKeyLength(key: ByteArray): ByteArray {
        if (key.size != DATABASE_KEY_BYTES) {
            throw DatabaseKeyUnavailableException("Stored database key has an invalid length")
        }
        return key
    }

    fun nonceOf(payload: ByteArray): ByteArray = payload.copyOfRange(0, NONCE_BYTES)
}

/** Stores a random SQLCipher key wrapped by an app-private Android Keystore key. */
// Public for the same reason; wrap() and masterKey() stay private.
object DatabaseKeyStore {
    private const val PREFERENCES = "database_security"
    private const val WRAPPED_KEY = "wrapped_sqlcipher_key"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "OpenTaskerDatabaseMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private const val DATABASE_KEY_BYTES = 32

    fun getOrCreate(context: Context): ByteArray {
        synchronized(this) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val stored = preferences.getString(WRAPPED_KEY, null)
            if (stored != null) return unwrap(stored)

            val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
            val committed = preferences.edit()
                .putString(WRAPPED_KEY, wrap(databaseKey))
                .commit()
            check(committed) { "Could not durably store the encrypted database key" }
            return databaseKey
        }
    }

    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance("AES", KEYSTORE).apply {
            init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }

    private fun wrap(databaseKey: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val payload = cipher.iv + cipher.doFinal(databaseKey)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun unwrap(encoded: String): ByteArray {
        val decoded = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrElse { error -> throw DatabaseKeyUnavailableException("Stored database key is malformed", error) }
        val payload = DatabaseKeyPayload.requireWellFormed(decoded)

        val key = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(GCM_TAG_BITS, DatabaseKeyPayload.nonceOf(payload)),
            )
            cipher.doFinal(payload, NONCE_BYTES, payload.size - NONCE_BYTES)
        }.getOrElse { error ->
            // The master key is gone or the blob no longer authenticates. Terminal on purpose:
            // there is no second copy of the SQLCipher key anywhere.
            throw DatabaseKeyUnavailableException("Could not unwrap the encrypted database key", error)
        }
        return DatabaseKeyPayload.requireKeyLength(key)
    }
}
