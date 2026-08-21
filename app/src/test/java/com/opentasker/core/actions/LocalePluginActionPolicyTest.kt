package com.opentasker.core.actions

import com.opentasker.core.engine.ActionResult
import com.opentasker.core.plugins.locale.LocalePluginConditionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalePluginActionPolicyTest {

    @Test
    fun absentTimeoutUsesTheDefault() {
        assertEquals(LocalePluginActionPolicy.DEFAULT_TIMEOUT_MS, LocalePluginActionPolicy.parseTimeout(null))
        assertEquals(LocalePluginActionPolicy.DEFAULT_TIMEOUT_MS, LocalePluginActionPolicy.parseTimeout(""))
        assertEquals(LocalePluginActionPolicy.DEFAULT_TIMEOUT_MS, LocalePluginActionPolicy.parseTimeout("   "))
    }

    @Test
    fun explicitTimeoutIsHonoured() {
        assertEquals(1_500L, LocalePluginActionPolicy.parseTimeout("1500"))
        assertEquals(1_500L, LocalePluginActionPolicy.parseTimeout(" 1500 "))
    }

    @Test
    fun unparsableTimeoutIsRejectedRatherThanDefaulted() {
        assertNull(LocalePluginActionPolicy.parseTimeout("5s"))
        assertNull(LocalePluginActionPolicy.parseTimeout("1500.0"))
        assertNull(LocalePluginActionPolicy.parseTimeout("abc"))
    }

    @Test
    fun satisfiedConditionSucceeds() {
        assertEquals(
            ActionResult.Success,
            LocalePluginActionPolicy.conditionResult(LocalePluginConditionState.Satisfied, false, "m"),
        )
        assertEquals(
            ActionResult.Success,
            LocalePluginActionPolicy.conditionResult(LocalePluginConditionState.Satisfied, true, "m"),
        )
    }

    @Test
    fun unsatisfiedConditionIsAnAnswerAndOnlyFailsWhenAMatchIsRequired() {
        assertEquals(
            ActionResult.Success,
            LocalePluginActionPolicy.conditionResult(LocalePluginConditionState.Unsatisfied, false, "m"),
        )
        assertEquals(
            ActionResult.Failure("m"),
            LocalePluginActionPolicy.conditionResult(LocalePluginConditionState.Unsatisfied, true, "m"),
        )
    }

    @Test
    fun unknownConditionFailsEvenWhenAMatchIsNotRequired() {
        // This is the defect the policy exists for: a timed-out or silent plugin used to report
        // Success because requireSatisfied defaults to false.
        assertEquals(
            ActionResult.Failure("timed out"),
            LocalePluginActionPolicy.conditionResult(LocalePluginConditionState.Unknown, false, "timed out"),
        )
        assertEquals(
            ActionResult.Failure("timed out"),
            LocalePluginActionPolicy.conditionResult(LocalePluginConditionState.Unknown, true, "timed out"),
        )
    }

    @Test
    fun unknownAndUnsatisfiedTracesAreDistinguishable() {
        val unknown = LocalePluginActionPolicy.conditionTrace(LocalePluginConditionState.Unknown, "m")
        val unsatisfied = LocalePluginActionPolicy.conditionTrace(LocalePluginConditionState.Unsatisfied, "m")
        val satisfied = LocalePluginActionPolicy.conditionTrace(LocalePluginConditionState.Satisfied, "m")

        assertTrue(unknown.contains("unknown"))
        assertTrue(unsatisfied.contains("not satisfied"))
        assertEquals(3, setOf(unknown, unsatisfied, satisfied).size)
    }

    @Test
    fun dispatchedSettingTraceStatesThatDeliveryIsUnconfirmed() {
        val trace = LocalePluginActionPolicy.settingDispatchTrace("Locale plugin setting dispatched to x/y.")

        assertTrue(trace.contains("Locale plugin setting dispatched to x/y."))
        assertTrue(trace.contains("unconfirmed"))
    }
}
