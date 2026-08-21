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

/** Stores a random SQLCipher key wrapped by an app-private Android Keystore key. */
internal object DatabaseKeyStore {
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

    private fun unwrap(encoded: String): ByteArray {
        val payload = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }
            .getOrElse { error -> throw IllegalStateException("Stored database key is malformed", error) }
        if (payload.size <= NONCE_BYTES) {
            throw IllegalStateException("Stored database key is truncated")
        }

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                masterKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload.copyOfRange(0, NONCE_BYTES)),
            )
            cipher.doFinal(payload, NONCE_BYTES, payload.size - NONCE_BYTES)
        }.getOrElse { error ->
            throw IllegalStateException("Could not unwrap the encrypted database key", error)
        }.also { key ->
            check(key.size == DATABASE_KEY_BYTES) { "Stored database key has an invalid length" }
        }
    }
}
