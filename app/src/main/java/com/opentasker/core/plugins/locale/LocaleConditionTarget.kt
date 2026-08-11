package com.opentasker.core.plugins.locale

import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.model.VariableNamePolicy

enum class LocaleConditionKind(val wireName: String) {
    PROFILE_ACTIVE("profile_active"),
    CONTEXT_SATISFIED("context_satisfied"),
    VARIABLE_COMPARE("variable_compare"),
}

enum class LocaleConditionOperator(val wireName: String) {
    EQUALS("equals"),
    NOT_EQUALS("not_equals"),
    CONTAINS("contains"),
    STARTS_WITH("starts_with"),
    ENDS_WITH("ends_with"),
}

data class LocaleConditionSpec(
    val kind: LocaleConditionKind,
    val profileId: Long? = null,
    val contextIndex: Int? = null,
    val variableName: String? = null,
    val variableProjectId: Long = DEFAULT_PROJECT_ID,
    val operator: LocaleConditionOperator? = null,
    val expectedValue: String? = null,
    /** Configure-time read grant; required for every exported condition query. */
    val grantToken: String? = null,
)

data class LocaleConditionSnapshot(
    val profileExists: Boolean = false,
    val profileEnabled: Boolean = false,
    val profileActive: Boolean? = null,
    val contextMatched: Boolean? = null,
    val variableExists: Boolean = false,
    val variableSecret: Boolean = false,
    val variableValue: String? = null,
)

object LocaleConditionTarget {
    const val SCHEMA_VERSION = "1"
    const val BUNDLE_KEY_SCHEMA = "com.opentasker.locale.condition.SCHEMA"
    const val BUNDLE_KEY_KIND = "com.opentasker.locale.condition.KIND"
    const val BUNDLE_KEY_PROFILE_ID = "com.opentasker.locale.condition.PROFILE_ID"
    const val BUNDLE_KEY_PROFILE_NAME = "com.opentasker.locale.condition.PROFILE_NAME"
    const val BUNDLE_KEY_CONTEXT_INDEX = "com.opentasker.locale.condition.CONTEXT_INDEX"
    const val BUNDLE_KEY_CONTEXT_LABEL = "com.opentasker.locale.condition.CONTEXT_LABEL"
    const val BUNDLE_KEY_VARIABLE_NAME = "com.opentasker.locale.condition.VARIABLE_NAME"
    const val BUNDLE_KEY_VARIABLE_PROJECT_ID = "com.opentasker.locale.condition.VARIABLE_PROJECT_ID"
    const val BUNDLE_KEY_OPERATOR = "com.opentasker.locale.condition.OPERATOR"
    const val BUNDLE_KEY_EXPECTED_VALUE = "com.opentasker.locale.condition.EXPECTED_VALUE"
    const val BUNDLE_KEY_GRANT = "com.opentasker.locale.condition.GRANT"
    const val MAX_GRANT_CHARS = 128
    const val MAX_CONTEXT_INDEX = 1_024
    const val MAX_EXPECTED_VALUE_BYTES = 4 * 1_024

    fun profileActive(profileId: Long, profileName: String, grantToken: String): Map<String, String> = mapOf(
        BUNDLE_KEY_SCHEMA to SCHEMA_VERSION,
        BUNDLE_KEY_KIND to LocaleConditionKind.PROFILE_ACTIVE.wireName,
        BUNDLE_KEY_PROFILE_ID to requirePositiveId(profileId).toString(),
        BUNDLE_KEY_PROFILE_NAME to profileName.trim().take(120),
        BUNDLE_KEY_GRANT to requireGrantToken(grantToken),
    )

    fun contextSatisfied(
        profileId: Long,
        profileName: String,
        contextIndex: Int,
        contextLabel: String,
        grantToken: String,
    ): Map<String, String> = mapOf(
        BUNDLE_KEY_SCHEMA to SCHEMA_VERSION,
        BUNDLE_KEY_KIND to LocaleConditionKind.CONTEXT_SATISFIED.wireName,
        BUNDLE_KEY_PROFILE_ID to requirePositiveId(profileId).toString(),
        BUNDLE_KEY_PROFILE_NAME to profileName.trim().take(120),
        BUNDLE_KEY_CONTEXT_INDEX to requireContextIndex(contextIndex).toString(),
        BUNDLE_KEY_CONTEXT_LABEL to contextLabel.trim().take(120),
        BUNDLE_KEY_GRANT to requireGrantToken(grantToken),
    )

    fun variableCompare(
        variableName: String,
        projectId: Long,
        operator: LocaleConditionOperator,
        expectedValue: String,
        grantToken: String,
    ): Map<String, String> {
        val name = VariableNamePolicy.normalize(variableName)
            ?: throw IllegalArgumentException("Invalid variable name.")
        requirePositiveId(projectId)
        requireExpectedValue(expectedValue)
        requireGrantToken(grantToken)
        return mapOf(
            BUNDLE_KEY_SCHEMA to SCHEMA_VERSION,
            BUNDLE_KEY_KIND to LocaleConditionKind.VARIABLE_COMPARE.wireName,
            BUNDLE_KEY_VARIABLE_NAME to name,
            BUNDLE_KEY_VARIABLE_PROJECT_ID to projectId.toString(),
            BUNDLE_KEY_OPERATOR to operator.wireName,
            BUNDLE_KEY_EXPECTED_VALUE to expectedValue,
            BUNDLE_KEY_GRANT to grantToken,
        )
    }

    fun parse(values: Map<String, String>): LocaleConditionSpec {
        require(values[BUNDLE_KEY_SCHEMA] == SCHEMA_VERSION) { "Unsupported Locale condition schema." }
        val kind = values[BUNDLE_KEY_KIND]?.let { raw ->
            LocaleConditionKind.entries.firstOrNull { it.wireName == raw }
        } ?: error("Unknown Locale condition kind.")
        return when (kind) {
            LocaleConditionKind.PROFILE_ACTIVE -> LocaleConditionSpec(
                kind = kind,
                profileId = parsePositiveId(values[BUNDLE_KEY_PROFILE_ID])
                    ?: error("Invalid profile identifier."),
                grantToken = requireGrantToken(values[BUNDLE_KEY_GRANT].orEmpty()),
            )
            LocaleConditionKind.CONTEXT_SATISFIED -> LocaleConditionSpec(
                kind = kind,
                profileId = parsePositiveId(values[BUNDLE_KEY_PROFILE_ID])
                    ?: error("Invalid profile identifier."),
                contextIndex = parseContextIndex(values[BUNDLE_KEY_CONTEXT_INDEX]),
                grantToken = requireGrantToken(values[BUNDLE_KEY_GRANT].orEmpty()),
            )
            LocaleConditionKind.VARIABLE_COMPARE -> LocaleConditionSpec(
                kind = kind,
                variableName = VariableNamePolicy.normalize(values[BUNDLE_KEY_VARIABLE_NAME].orEmpty())
                    ?: error("Invalid variable name."),
                variableProjectId = values[BUNDLE_KEY_VARIABLE_PROJECT_ID]
                    ?.takeIf(String::isNotBlank)
                    ?.let { parsePositiveId(it) ?: error("Invalid variable project.") }
                    ?: DEFAULT_PROJECT_ID,
                operator = values[BUNDLE_KEY_OPERATOR]?.let { raw ->
                    LocaleConditionOperator.entries.firstOrNull { it.wireName == raw }
                } ?: error("Unknown Locale condition comparison operator."),
                expectedValue = requireExpectedValue(values[BUNDLE_KEY_EXPECTED_VALUE].orEmpty()),
                grantToken = requireGrantToken(values[BUNDLE_KEY_GRANT].orEmpty()),
            )
        }
    }

    fun buildBlurb(spec: LocaleConditionSpec): String = when (spec.kind) {
        LocaleConditionKind.PROFILE_ACTIVE -> "Profile active: ${spec.profileId}"
        LocaleConditionKind.CONTEXT_SATISFIED -> "Context satisfied: profile ${spec.profileId}, context ${(spec.contextIndex ?: 0) + 1}"
        LocaleConditionKind.VARIABLE_COMPARE ->
            "${spec.variableName.orEmpty()} ${spec.operator?.wireName.orEmpty()} configured value"
    }

    private fun requirePositiveId(value: Long): Long = value.also {
        require(it > 0) { "Identifier must be positive." }
    }

    private fun parsePositiveId(raw: String?): Long? = raw?.toLongOrNull()?.takeIf { it > 0 }

    private fun requireContextIndex(value: Int): Int = value.also {
        require(it in 0..MAX_CONTEXT_INDEX) { "Context index is out of bounds." }
    }

    private fun parseContextIndex(raw: String?): Int = raw?.toIntOrNull()?.let(::requireContextIndex)
        ?: error("Invalid context index.")

    private fun requireGrantToken(value: String): String = value.also {
        require(it.isNotBlank()) { "This condition was configured before read grants existed; re-select the variable." }
        require(it.length <= MAX_GRANT_CHARS) { "Grant token is too large." }
    }

    private fun requireExpectedValue(value: String): String = value.also {
        require(it.isNotEmpty()) { "Comparison value must not be empty." }
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_EXPECTED_VALUE_BYTES) {
            "Comparison value is too large."
        }
    }
}

object LocaleConditionEvaluator {
    fun evaluate(spec: LocaleConditionSpec, snapshot: LocaleConditionSnapshot): LocalePluginConditionState = when (spec.kind) {
        LocaleConditionKind.PROFILE_ACTIVE -> when {
            !snapshot.profileExists -> LocalePluginConditionState.Unknown
            !snapshot.profileEnabled -> LocalePluginConditionState.Unsatisfied
            else -> snapshot.profileActive.toState()
        }
        LocaleConditionKind.CONTEXT_SATISFIED -> when {
            !snapshot.profileExists -> LocalePluginConditionState.Unknown
            !snapshot.profileEnabled -> LocalePluginConditionState.Unsatisfied
            else -> snapshot.contextMatched.toState()
        }
        LocaleConditionKind.VARIABLE_COMPARE -> when {
            !snapshot.variableExists || snapshot.variableSecret || snapshot.variableValue == null ->
                LocalePluginConditionState.Unknown
            else -> resultState(compareValues(snapshot.variableValue, spec.operator!!, spec.expectedValue!!))
        }
    }

    fun compareValues(
        actual: String,
        operator: LocaleConditionOperator,
        expected: String,
    ): Boolean = when (operator) {
        LocaleConditionOperator.EQUALS -> actual == expected
        LocaleConditionOperator.NOT_EQUALS -> actual != expected
        LocaleConditionOperator.CONTAINS -> actual.contains(expected)
        LocaleConditionOperator.STARTS_WITH -> actual.startsWith(expected)
        LocaleConditionOperator.ENDS_WITH -> actual.endsWith(expected)
    }

    private fun Boolean?.toState(): LocalePluginConditionState = when (this) {
        true -> LocalePluginConditionState.Satisfied
        false -> LocalePluginConditionState.Unsatisfied
        null -> LocalePluginConditionState.Unknown
    }

    private fun resultState(matched: Boolean): LocalePluginConditionState =
        if (matched) LocalePluginConditionState.Satisfied else LocalePluginConditionState.Unsatisfied
}
