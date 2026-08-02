package com.opentasker.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSignalTest {
    @Test
    fun requiredEvidencePrioritizesErrorThenStaleThenLoading() {
        val signals = listOf(
            HealthSignal("loading", "Engine", HealthSignalState.Loading, 1, "starting"),
            HealthSignal("stale", "Scheduler", HealthSignalState.Stale, 2, "blocked"),
            HealthSignal("error", "Matcher", HealthSignalState.Error, 3, "crashed"),
        )

        val assessment = assessHealth(signals)

        assertEquals(HealthSignalState.Error, assessment.state)
        assertEquals("Matcher: crashed", assessment.reason)
        assertFalse(assessment.healthy)
    }

    @Test
    fun optionalWarningsDoNotHideHealthyRequiredEvidence() {
        val assessment = assessHealth(
            listOf(
                HealthSignal("engine", "Engine", HealthSignalState.Ready, 1, "current"),
                HealthSignal("protection", "Advanced Protection", HealthSignalState.Stale, 2, "limited", required = false),
            ),
        )

        assertEquals(HealthSignalState.Ready, assessment.state)
        assertTrue(assessment.healthy)
    }
}
