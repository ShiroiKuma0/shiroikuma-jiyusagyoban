package com.opentasker.core.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionRecoveryPolicyTest {
    @Test
    fun firstDenialRemainsRetryableEvenWhenRationaleIsUnavailable() {
        val requested = RuntimePermissionRecoveryPolicy.afterRequest(RuntimePermissionRequestState())

        val decision = RuntimePermissionRecoveryPolicy.afterResult(requested, granted = false, shouldShowRationale = false)

        assertEquals(RuntimePermissionOutcome.DeniedCanRetry, decision.outcome)
        assertFalse(decision.state.settingsRequired)
    }

    @Test
    fun secondDenialWithoutRationaleRoutesToSettings() {
        val first = RuntimePermissionRecoveryPolicy.afterRequest(RuntimePermissionRequestState())
        val second = RuntimePermissionRecoveryPolicy.afterRequest(first)

        val decision = RuntimePermissionRecoveryPolicy.afterResult(second, granted = false, shouldShowRationale = false)

        assertEquals(RuntimePermissionOutcome.SettingsRequired, decision.outcome)
        assertTrue(decision.state.settingsRequired)
    }

    @Test
    fun rationaleKeepsARepeatedDenialRetryable() {
        val state = RuntimePermissionRequestState(attemptCount = 2)

        val decision = RuntimePermissionRecoveryPolicy.afterResult(state, granted = false, shouldShowRationale = true)

        assertEquals(RuntimePermissionOutcome.DeniedCanRetry, decision.outcome)
        assertFalse(decision.state.settingsRequired)
    }

    @Test
    fun grantResetsHistorySoALaterRevocationStartsFresh() {
        val granted = RuntimePermissionRecoveryPolicy.afterResult(
            RuntimePermissionRequestState(attemptCount = 2, settingsRequired = true),
            granted = true,
            shouldShowRationale = false,
        )
        val afterRevocationRequest = RuntimePermissionRecoveryPolicy.afterRequest(granted.state)

        assertEquals(RuntimePermissionOutcome.Granted, granted.outcome)
        assertEquals(1, afterRevocationRequest.attemptCount)
        assertFalse(afterRevocationRequest.settingsRequired)
    }

    @Test
    fun persistedAttemptStateSurvivesRecreationBeforeTheResult() {
        val persistedBeforeRecreation = RuntimePermissionRecoveryPolicy.afterRequest(RuntimePermissionRequestState(attemptCount = 1))

        val restoredDecision = RuntimePermissionRecoveryPolicy.afterResult(
            state = persistedBeforeRecreation.copy(),
            granted = false,
            shouldShowRationale = false,
        )

        assertEquals(RuntimePermissionOutcome.SettingsRequired, restoredDecision.outcome)
    }
}
