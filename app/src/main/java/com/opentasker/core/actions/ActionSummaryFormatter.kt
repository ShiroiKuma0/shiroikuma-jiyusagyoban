package com.opentasker.core.actions

import android.content.res.Resources
import com.opentasker.app.R

/**
 * Resource-backed, single-line copy for an action and its stored parameters.
 *
 * The parameter portion always goes through [ActionArgumentSensitivity], so task rows, flow
 * nodes, and preflight cannot accidentally expose a credential while trying to explain a step.
 * Upstream resolves a per-action `summaryRes` template through the registry. The fork keeps its
 * metadata as inline strings rather than string resources, so the action NAME comes straight from
 * the metadata and every action shares the one generic template.
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
        val actionName = metadata?.name?.takeUnless(String::isBlank) ?: actionType
        val parameters = ActionArgumentSensitivity.summarize(
            actionType = actionType,
            args = args,
            limit = MAX_ARGUMENTS,
            maxValueLength = MAX_VALUE_LENGTH,
        ).ifBlank { resources.getString(R.string.action_summary_default_parameters) }
        return resources.getString(R.string.action_parameter_summary, actionName, parameters)
    }
}
