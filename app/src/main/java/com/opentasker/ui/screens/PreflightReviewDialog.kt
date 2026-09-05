package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionSummaryFormatter
import com.opentasker.core.engine.PreflightReport
import com.opentasker.core.engine.PreflightStep
import com.opentasker.core.engine.PreflightStepStatus

internal fun parsePreflightEventVariables(raw: String): Map<String, String> =
    raw.lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            key.takeIf { it.isNotBlank() }?.let { it to value }
        }
        .take(32)
        .toMap()

@Composable
internal fun PreflightReviewDialog(
    state: PreflightReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRerun: (Map<String, String>) -> Unit,
) {
    var syntheticVariables by rememberSaveable {
        mutableStateOf(state.inputs.eventVariables.entries.joinToString("\n") { (key, value) -> "$key=$value" })
    }
    val report = state.report
    val scrollState = rememberScrollState()
    val setupRequirements = report.setupRequirements
        .map { requirement -> stringResource(setupRequirementLabelRes(requirement)) }
        .joinToString()
        .ifBlank { stringResource(R.string.preflight_no_values) }
    val missingSetup = report.missingSetupRequirements
        .map { requirement -> stringResource(setupRequirementLabelRes(requirement)) }
        .joinToString()
        .ifBlank { stringResource(R.string.preflight_no_values) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_preflight_review)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Re-running a preflight replaces the report in place. The title is its summary
                // line, so announcing just that says the run finished and how it went, without
                // reading the whole report back.
                Text(
                    report.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.preflight_notice), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = syntheticVariables,
                    onValueChange = { syntheticVariables = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.preflight_synthetic_variables)) },
                    supportingText = { Text(stringResource(R.string.preflight_synthetic_variables_hint)) },
                    minLines = 2,
                )
                Button(
                    onClick = { onRerun(parsePreflightEventVariables(syntheticVariables)) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (busy) R.string.preflight_running else R.string.preflight_run,
                        ),
                    )
                }
                Text(
                    stringResource(R.string.preflight_side_effects_suppressed),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(
                        if (report.canPreflight) R.string.preflight_can_run else R.string.preflight_has_blockers,
                    ),
                    color = if (report.canPreflight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(stringResource(R.string.preflight_setup_required, setupRequirements))
                Text(stringResource(R.string.preflight_setup_missing, missingSetup))
                if (report.contexts.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.preflight_contexts, report.contexts.size), style = MaterialTheme.typography.titleSmall)
                    report.contexts.forEachIndexed { index, context ->
                        Text(
                            stringResource(
                                R.string.preflight_context_detail,
                                index + 1,
                                stringResource(contextTitleRes(context.type)),
                                context.configuration.entries.joinToString { (key, value) -> "$key=$value" },
                            ),
                        )
                        Text(context.intendedEffect, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.preflight_tasks, report.tasks.size), style = MaterialTheme.typography.titleSmall)
                report.tasks.forEach { task ->
                    Text(
                        stringResource(R.string.preflight_task_detail, task.taskPath, task.steps.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    task.steps.forEach { step -> PreflightStepCard(step) }
                }
                if (report.warnings.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.preflight_warnings), style = MaterialTheme.typography.titleSmall)
                    report.warnings.forEach { warning ->
                        Text(stringResource(R.string.preflight_warning_item, warning), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun PreflightStepCard(step: PreflightStep) {
    val resources = LocalContext.current.resources
    val actionSummary = ActionSummaryFormatter.format(resources, step.actionType, step.expandedArguments)
    val actionName = actionDisplayName(step.actionType)
    val label = step.label.takeUnless { it == step.actionType } ?: actionName
    val status = when (step.status) {
        PreflightStepStatus.SIMULATED -> stringResource(R.string.preflight_status_simulated)
        PreflightStepStatus.SKIPPED -> stringResource(R.string.preflight_status_skipped)
        PreflightStepStatus.BLOCKED -> stringResource(R.string.preflight_status_blocked)
    }
    val statusColor = when (step.status) {
        PreflightStepStatus.SIMULATED -> MaterialTheme.colorScheme.primary
        PreflightStepStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
        PreflightStepStatus.BLOCKED -> MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.preflight_step_detail, step.actionIndex + 1, label, actionName, status),
                color = statusColor,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(stringResource(R.string.preflight_effect, step.intendedEffect))
            Text(actionSummary)
            step.branchDecision?.let { Text(stringResource(R.string.preflight_branch, it)) }
            step.warnings.forEach { warning ->
                Text(stringResource(R.string.preflight_warning_item, warning), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
