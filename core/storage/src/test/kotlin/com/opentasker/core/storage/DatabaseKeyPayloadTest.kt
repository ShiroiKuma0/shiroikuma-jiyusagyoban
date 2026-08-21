package com.opentasker.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Keystore-loss decision, asserted rather than assumed.
 *
 * The SQLCipher key exists only inside the Keystore-wrapped blob. When that cannot be unwrapped the
 * database is unreadable and no retry helps, so the app fails with a named terminal type instead of
 * a bare IllegalStateException a caller cannot tell apart from a transient error.
 */
class DatabaseKeyPayloadTest {
    @Test
    fun aTruncatedBlobIsTerminalRatherThanRetried() {
        listOf(0, 1, DatabaseKeyPayload.NONCE_BYTES).forEach { size ->
            val error = assertThrows(DatabaseKeyUnavailableException::class.java) {
                DatabaseKeyPayload.requireWellFormed(ByteArray(size))
            }
            assertTrue("The message must name the cause: $size bytes", "truncated" in error.message.orEmpty())
        }
    }

    @Test
    fun aBlobWithACiphertextIsAccepted() {
        val payload = ByteArray(DatabaseKeyPayload.NONCE_BYTES + 1)

        assertSame(payload, DatabaseKeyPayload.requireWellFormed(payload))
        assertEquals(DatabaseKeyPayload.NONCE_BYTES, DatabaseKeyPayload.nonceOf(payload).size)
    }

    @Test
    fun aKeyOfTheWrongLengthIsRefusedRatherThanHandedToSqlCipher() {
        listOf(0, 16, 31, 33, 64).forEach { size ->
            assertThrows(
                "A $size-byte key must not be used to open the database",
                DatabaseKeyUnavailableException::class.java,
            ) {
                DatabaseKeyPayload.requireKeyLength(ByteArray(size))
            }
        }

        val key = ByteArray(DatabaseKeyPayload.DATABASE_KEY_BYTES)
        assertSame(key, DatabaseKeyPayload.requireKeyLength(key))
    }

    @Test
    fun theTerminalTypeIsDistinguishableFromAnOrdinaryFailure() {
        val error = DatabaseKeyUnavailableException("gone", IllegalStateException("cause"))

        assertTrue("Existing catch sites must keep working", error is IllegalStateException)
        assertEquals("gone", error.message)
        assertEquals("cause", error.cause?.message)
    }
}
