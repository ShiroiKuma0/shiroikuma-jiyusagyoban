package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleGrantStoreTest {
    @Test
    fun validGrantMatchesStoredTaskBinding() {
        assertTrue(isGrantValid(storedTaskId = 5L, requestedToken = "tok", requestedTaskId = 5L))
    }

    @Test
    fun forgedOrRevokedGrantFails() {
        // No stored binding for the token (unknown or revoked) -> stored is null.
        assertFalse(isGrantValid(storedTaskId = null, requestedToken = "tok", requestedTaskId = 5L))
    }

    @Test
    fun mutatedGrantBoundToDifferentTaskFails() {
        assertFalse(isGrantValid(storedTaskId = 3L, requestedToken = "tok", requestedTaskId = 5L))
    }

    @Test
    fun missingOrBlankTokenFails() {
        assertFalse(isGrantValid(storedTaskId = 5L, requestedToken = null, requestedTaskId = 5L))
        assertFalse(isGrantValid(storedTaskId = 5L, requestedToken = "", requestedTaskId = 5L))
        assertFalse(isGrantValid(storedTaskId = 5L, requestedToken = "  ", requestedTaskId = 5L))
    }

    @Test
    fun nonPositiveTaskIdFails() {
        assertFalse(isGrantValid(storedTaskId = 0L, requestedToken = "tok", requestedTaskId = 0L))
        assertFalse(isGrantValid(storedTaskId = -1L, requestedToken = "tok", requestedTaskId = -1L))
    }

    @Test
    fun newTokenIsHighEntropyUrlSafeAndUnique() {
        val a = LocaleGrantStore.newToken()
        val b = LocaleGrantStore.newToken()
        assertNotEquals(a, b)
        // 32 bytes, base64url without padding -> 43 chars.
        assertEquals(43, a.length)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
