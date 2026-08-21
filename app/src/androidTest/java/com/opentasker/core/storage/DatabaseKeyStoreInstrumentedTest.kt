package com.opentasker.core.storage

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What happens when the wrapped database key cannot be recovered.
 *
 * This needs a real Keystore, so it lives in the device lane. The decision it pins is that the
 * failure is terminal and typed: there is no second copy of the SQLCipher key, so unwrapping a blob
 * this app did not write must fail with [DatabaseKeyUnavailableException] rather than returning
 * something the database would then be opened with.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyStoreInstrumentedTest {

    @Test
    fun aBlobThatIsNotBase64IsTerminal() {
        val error = assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseKeyStore.unwrap("not base64 !!!")
        }

        assertTrue("malformed" in error.message.orEmpty())
    }

    @Test
    fun aTruncatedBlobIsTerminal() {
        val truncated = Base64.encodeToString(ByteArray(DatabaseKeyPayload.NONCE_BYTES), Base64.NO_WRAP)

        val error = assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseKeyStore.unwrap(truncated)
        }

        assertTrue("truncated" in error.message.orEmpty())
    }

    @Test
    fun aBlobTheMasterKeyCannotAuthenticateIsTerminalAndNotSilentlyAccepted() {
        // Right shape, wrong contents: this is what a Keystore reset or a restore onto another
        // device leaves behind.
        val foreign = Base64.encodeToString(ByteArray(DatabaseKeyPayload.NONCE_BYTES + 48), Base64.NO_WRAP)

        val error = assertThrows(DatabaseKeyUnavailableException::class.java) {
            DatabaseKeyStore.unwrap(foreign)
        }

        assertTrue("Could not unwrap" in error.message.orEmpty())
    }
}
