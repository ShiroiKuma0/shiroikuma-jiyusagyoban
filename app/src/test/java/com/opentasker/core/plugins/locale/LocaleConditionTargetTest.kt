package com.opentasker.core.plugins.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleConditionTargetTest {
    @Test
    fun profileAndContextBundlesRoundTripAsTypedSpecs() {
        val profile = LocaleConditionTarget.parse(
            LocaleConditionTarget.profileActive(42, "Work"),
        )
        val context = LocaleConditionTarget.parse(
            LocaleConditionTarget.contextSatisfied(42, "Work", 2, "Plugin condition"),
        )

        assertEquals(LocaleConditionKind.PROFILE_ACTIVE, profile.kind)
        assertEquals(42L, profile.profileId)
        assertEquals(LocaleConditionKind.CONTEXT_SATISFIED, context.kind)
        assertEquals(2, context.contextIndex)
    }

    @Test
    fun variableComparisonNeverIncludesExpectedValueInTheBlurb() {
        val values = LocaleConditionTarget.variableCompare(
            variableName = "Mode",
            projectId = 1,
            operator = LocaleConditionOperator.EQUALS,
            expectedValue = "private-value",
        )
        val spec = LocaleConditionTarget.parse(values)

        assertEquals("private-value", spec.expectedValue)
        assertFalse(LocaleConditionTarget.buildBlurb(spec).contains("private-value"))
    }

    @Test
    fun malformedAndOversizedConditionValuesFailClosed() {
        val malformed = runCatching {
            LocaleConditionTarget.parse(
                mapOf(
                    LocaleConditionTarget.BUNDLE_KEY_SCHEMA to LocaleConditionTarget.SCHEMA_VERSION,
                    LocaleConditionTarget.BUNDLE_KEY_KIND to LocaleConditionKind.VARIABLE_COMPARE.wireName,
                    LocaleConditionTarget.BUNDLE_KEY_VARIABLE_NAME to "Mode",
                    LocaleConditionTarget.BUNDLE_KEY_OPERATOR to LocaleConditionOperator.EQUALS.wireName,
                ),
            )
        }.exceptionOrNull()
        val oversized = runCatching {
            LocaleConditionTarget.variableCompare(
                "Mode",
                1,
                LocaleConditionOperator.EQUALS,
                "x".repeat(LocaleConditionTarget.MAX_EXPECTED_VALUE_BYTES + 1),
            )
        }.exceptionOrNull()

        assertTrue(malformed is IllegalArgumentException || malformed is IllegalStateException)
        assertTrue(oversized is IllegalArgumentException)
    }

    @Test
    fun evaluatorDistinguishesSatisfiedUnsatisfiedAndUnknown() {
        val profileSpec = LocaleConditionTarget.parse(LocaleConditionTarget.profileActive(7, "Home"))
        val contextSpec = LocaleConditionTarget.parse(LocaleConditionTarget.contextSatisfied(7, "Home", 0, "State"))
        val variableSpec = LocaleConditionTarget.parse(
            LocaleConditionTarget.variableCompare("Mode", 1, LocaleConditionOperator.CONTAINS, "work"),
        )

        assertEquals(
            LocalePluginConditionState.Satisfied,
            LocaleConditionEvaluator.evaluate(profileSpec, LocaleConditionSnapshot(profileExists = true, profileEnabled = true, profileActive = true)),
        )
        assertEquals(
            LocalePluginConditionState.Unsatisfied,
            LocaleConditionEvaluator.evaluate(profileSpec, LocaleConditionSnapshot(profileExists = true, profileEnabled = true, profileActive = false)),
        )
        assertEquals(
            LocalePluginConditionState.Unknown,
            LocaleConditionEvaluator.evaluate(contextSpec, LocaleConditionSnapshot(profileExists = true, profileEnabled = true)),
        )
        assertEquals(
            LocalePluginConditionState.Satisfied,
            LocaleConditionEvaluator.evaluate(variableSpec, LocaleConditionSnapshot(variableExists = true, variableValue = "work-hours")),
        )
        assertEquals(
            LocalePluginConditionState.Unknown,
            LocaleConditionEvaluator.evaluate(variableSpec, LocaleConditionSnapshot(variableExists = true, variableSecret = true, variableValue = "work-hours")),
        )
    }
}
