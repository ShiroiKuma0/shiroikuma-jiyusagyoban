package com.opentasker.automation.constraint.impl

import com.opentasker.automation.core.ConstraintDefinition
import com.opentasker.automation.model.ConstraintConfig

/**
 * Variable-value constraint that checks if a stored variable matches a value.
 * Useful for state-based rule logic.
 */
class VariableConstraint(private val variableStore: Map<String, String> = emptyMap()) : ConstraintDefinition {
    override val id = "variable"
    override val displayName = "Variable Value"

    override suspend fun evaluate(config: ConstraintConfig): Boolean {
        val variableName = config.config["name"] as String? ?: return false
        val expectedValue = config.config["value"] as String? ?: return false
        val operator = config.config["operator"] as String? ?: "equals"

        val currentValue = variableStore[variableName] ?: ""

        return when (operator.lowercase()) {
            "equals" -> currentValue == expectedValue
            "contains" -> currentValue.contains(expectedValue)
            "starts_with" -> currentValue.startsWith(expectedValue)
            "ends_with" -> currentValue.endsWith(expectedValue)
            "regex" -> try {
                currentValue.matches(Regex(expectedValue))
            } catch (e: Exception) {
                false
            }
            else -> false
        }
    }
}
