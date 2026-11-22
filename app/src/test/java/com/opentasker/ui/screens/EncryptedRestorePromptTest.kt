package com.opentasker.ui.screens

import com.opentasker.core.storage.BackupEncryption
import com.opentasker.core.storage.DatabaseKeyUnavailableException
import com.opentasker.core.storage.LegacyBackupTooLargeException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which restore failures keep the passphrase prompt up (A-439). Only a failed decrypt does: the
 * file was a real snapshot and the passphrase didn't open it. Anything else closes the prompt and
 * says what went wrong, since typing again won't help.
 */
class EncryptedRestorePromptTest {
    private fun decryptError(encryptedWith: String, triedWith: String, damage: Boolean = false): Throwable {
        val encrypted = ByteArrayOutputStream()
        BackupEncryption.encrypt(ByteArrayInputStream(ByteArray(200) { it.toByte() }), encrypted, encryptedWith.toCharArray())
        val bytes = encrypted.toByteArray().also { if (damage) it[it.lastIndex - 3] = (it[it.lastIndex - 3].toInt() xor 1).toByte() }
        return runCatching { BackupEncryption.decrypt(ByteArrayInputStream(bytes), ByteArrayOutputStream(), triedWith.toCharArray()) }
            .exceptionOrNull() ?: error("expected the decrypt to fail")
    }

    @Test
    fun aWrongPassphraseOrADamagedFileAsksAgain() {
        assertTrue(readsAsWrongPassphrase(decryptError("right one", "wrong one")))
        assertTrue(readsAsWrongPassphrase(decryptError("right one", "right one", damage = true)))
        assertTrue("wrapped further out still counts", readsAsWrongPassphrase(IOException("outer", decryptError("a", "b"))))
    }

    @Test
    fun otherFailuresCloseThePrompt() {
        val notASnapshot = runCatching {
            BackupEncryption.decrypt(ByteArrayInputStream(ByteArray(64)), ByteArrayOutputStream(), "x".toCharArray())
        }.exceptionOrNull()!!

        assertFalse(readsAsWrongPassphrase(notASnapshot))
        assertFalse(readsAsWrongPassphrase(IOException("Backup file exceeds 100 MB limit")))
        assertFalse(readsAsWrongPassphrase(IllegalStateException("not a database")))
        // The snapshot decrypted fine and the device key failed while the candidate was checked:
        // a crypto error, but not the passphrase's, so asking again would loop forever.
        assertFalse(readsAsWrongPassphrase(DatabaseKeyUnavailableException("key unwrap failed", AEADBadTagException("keystore"))))
    }

    @Test
    fun anOldFormatSnapshotOverItsLimitSaysSo() {
        val overLimit = LegacyBackupTooLargeException(8L * 1024 * 1024)

        assertEquals(8L, legacyBackupLimitMb(overLimit))
        assertEquals("wrapped further out still counts", 8L, legacyBackupLimitMb(IOException("outer", overLimit)))
        assertFalse("not a wrong passphrase, so the prompt closes", readsAsWrongPassphrase(overLimit))
        assertNull(legacyBackupLimitMb(IOException("Backup file exceeds 100 MB limit")))
        assertNull(legacyBackupLimitMb(decryptError("a", "b")))
    }

    @Test
    fun aCauseChainThatLoopsStillEnds() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)

        assertFalse(readsAsWrongPassphrase(first))
    }
}
