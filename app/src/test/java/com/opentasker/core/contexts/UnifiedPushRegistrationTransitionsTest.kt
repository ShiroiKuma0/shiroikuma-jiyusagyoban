package com.opentasker.core.contexts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedPushRegistrationTransitionsTest {
    private val registered = UnifiedPushRegistrationState(
        instance = "default",
        status = UnifiedPushRegistrationStatus.REGISTERED,
        endpoint = "https://push.example/UP?token=secret",
        publicKey = "public-key",
        auth = "auth-secret",
        temporaryEndpoint = true,
        distributor = "org.unifiedpush.distributor",
    )

    @Test
    fun anUnreadableStoredStatusReadsAsIdle() {
        assertEquals(UnifiedPushRegistrationStatus.IDLE, UnifiedPushRegistrationTransitions.decodeStatus(null))
        assertEquals(UnifiedPushRegistrationStatus.IDLE, UnifiedPushRegistrationTransitions.decodeStatus(""))
        assertEquals(
            "A status written by a future build must not crash the reader",
            UnifiedPushRegistrationStatus.IDLE,
            UnifiedPushRegistrationTransitions.decodeStatus("SOMETHING_NEW"),
        )
        assertEquals(
            UnifiedPushRegistrationStatus.REGISTERED,
            UnifiedPushRegistrationTransitions.decodeStatus("REGISTERED"),
        )
    }

    @Test
    fun everyTransitionAwayFromRegisteredDropsTheEndpointAndItsKeys() {
        listOf(
            UnifiedPushRegistrationTransitions.registering(registered, "default"),
            UnifiedPushRegistrationTransitions.failed(registered, "default", "distributor gone"),
            UnifiedPushRegistrationTransitions.unregistered(registered, "default"),
        ).forEach { state ->
            assertNull("${state.status} must not keep the endpoint", state.endpoint)
            assertNull("${state.status} must not keep the public key", state.publicKey)
            assertNull("${state.status} must not keep the auth secret", state.auth)
            assertEquals("${state.status} must not keep the temporary flag", false, state.temporaryEndpoint)
            assertEquals("The chosen distributor is not part of a registration", registered.distributor, state.distributor)
        }
    }

    @Test
    fun aTemporaryOutageKeepsTheEndpointItIsWaitingOn() {
        val state = UnifiedPushRegistrationTransitions.temporarilyUnavailable(registered, "default")

        assertEquals(UnifiedPushRegistrationStatus.TEMPORARILY_UNAVAILABLE, state.status)
        assertEquals(registered.endpoint, state.endpoint)
        assertNull("A transient outage is not a failure to report", state.failureReason)
    }

    @Test
    fun registeringClearsAStaleFailureReason() {
        val failed = UnifiedPushRegistrationTransitions.failed(registered, "default", "no distributor")

        assertEquals("no distributor", failed.failureReason)
        assertNull(UnifiedPushRegistrationTransitions.registering(failed, "default").failureReason)
    }

    @Test
    fun everyStoredStringIsLengthCapped() {
        val state = UnifiedPushRegistrationTransitions.registered(
            current = registered,
            instance = "default",
            endpoint = "e".repeat(UnifiedPushRegistrationTransitions.MAX_ENDPOINT_CHARS * 2),
            publicKey = "k".repeat(UnifiedPushRegistrationTransitions.MAX_KEY_CHARS * 2),
            auth = "a".repeat(UnifiedPushRegistrationTransitions.MAX_KEY_CHARS * 2),
            temporary = false,
        )

        assertEquals(UnifiedPushRegistrationTransitions.MAX_ENDPOINT_CHARS, state.endpoint?.length)
        assertEquals(UnifiedPushRegistrationTransitions.MAX_KEY_CHARS, state.publicKey?.length)
        assertEquals(UnifiedPushRegistrationTransitions.MAX_KEY_CHARS, state.auth?.length)

        val failure = UnifiedPushRegistrationTransitions.failed(
            registered,
            "default",
            "r".repeat(UnifiedPushRegistrationTransitions.MAX_FAILURE_CHARS * 2),
        )
        assertEquals(UnifiedPushRegistrationTransitions.MAX_FAILURE_CHARS, failure.failureReason?.length)
    }

    @Test
    fun aRegistrationWithoutKeysIsStillARegistration() {
        val state = UnifiedPushRegistrationTransitions.registered(
            current = registered,
            instance = "other",
            endpoint = "https://push.example/UP?token=next",
            publicKey = null,
            auth = null,
            temporary = false,
        )

        assertEquals(UnifiedPushRegistrationStatus.REGISTERED, state.status)
        assertEquals("other", state.instance)
        assertNull(state.publicKey)
        assertNull(state.auth)
        assertTrue("A new registration replaces the previous endpoint", state.endpoint!!.endsWith("next"))
    }
}
