package com.opentasker.core.actions

import android.content.res.Resources
import com.opentasker.app.R

/**
 * Resource-backed, single-line copy for an action and its stored parameters.
 *
 * The parameter portion always goes through [ActionArgumentSensitivity], so task rows, flow
 * nodes, and preflight cannot accidentally expose a credential while trying to explain a step.
 * Every built-in action resolves [ActionMetadata.summaryRes] through the registry; the fallback
 * keeps forward-compatible or test-only action metadata grammatical as well.
 */
object ActionSummaryFormatter {
    const val MAX_ARGUMENTS = 3
    const val MAX_VALUE_LENGTH = 36

    fun format(
        resources: Resources,
        actionType: String,
        args: Map<String, String>,
    ): String {
        val metadata = ActionMetadataRegistry.get(actionType)
        val actionName = metadata
            ?.let { resources.getString(it.nameRes) }
            ?.takeUnless(String::isBlank)
            ?: actionType
        val parameters = ActionArgumentSensitivity.summarize(
            actionType = actionType,
            args = args,
            limit = MAX_ARGUMENTS,
            maxValueLength = MAX_VALUE_LENGTH,
        ).ifBlank { resources.getString(R.string.action_summary_default_parameters) }
        val templateRes = metadata?.summaryRes ?: R.string.action_parameter_summary
        return resources.getString(templateRes, actionName, parameters)
    }
}
