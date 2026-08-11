package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.AutomationLintSeverity
import com.opentasker.core.capabilities.AutomationLintStrings
import com.opentasker.core.capabilities.ImportedProfileEnablePolicy
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.DesignSystem

@Composable
internal fun ImportedProfileRiskDialog(
    profile: Profile,
    tasks: List<Task>,
    otherProfiles: List<Profile> = emptyList(),
    onDismiss: () -> Unit,
    onAcknowledgeAndEnable: () -> Unit,
) {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    val review = ImportedProfileEnablePolicy.review(
        profile = profile,
        tasks = tasks,
        otherProfiles = otherProfiles,
        strings = AutomationLintStrings.from(resources),
    )
    val reachableTasks = AutomationSensitivityRegistry.reachableTasks(profile, tasks)
    var acknowledged by rememberSaveable(profile.id) { mutableStateOf(false) }
    val powerLabels = review.risk.powers.map { power -> automationPowerLabel(power) }
    val chainLabels = review.risk.dataToExternalChains.map { chain ->
        stringResource(R.string.import_power_chain, chain.sourceActionId, chain.sinkActionId)
    }
    val missingTaskLabels = review.missingTaskIds.map { taskId ->
        stringResource(R.string.imported_profile_missing_task_reference, taskId)
    }
    val feedbackLabels = review.feedbackLoopRisks.map { risk ->
        stringResource(
            R.string.imported_profile_feedback_body,
            risk.taskPath.joinToString(" → "),
        )
    }
    val lintLabels = review.lintFindings.map { finding ->
        val prefix = if (finding.severity == AutomationLintSeverity.BLOCKING) {
            stringResource(R.string.automation_lint_blocked_prefix)
        } else {
            stringResource(R.string.automation_lint_warning_prefix)
        }
        "$prefix ${finding.detail} ${finding.suggestedFix}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imported_profile_review_title)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.imported_profile_review_body, profile.name),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                item {
                    InlineNotice(
                        title = stringResource(R.string.imported_profile_tasks_title),
                        body = reachableTasks.takeIf { it.isNotEmpty() }
                            ?.joinToString { it.name }
                            ?: stringResource(R.string.imported_profile_no_tasks),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item {
                    InlineNotice(
                        title = stringResource(R.string.import_power_review),
                        body = powerLabels.takeIf { it.isNotEmpty() }?.joinToString()
                            ?: stringResource(R.string.imported_profile_no_sensitive_powers),
                        color = if (powerLabels.isEmpty()) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (chainLabels.isNotEmpty()) {
                    item {
                        InlineNotice(
                            title = stringResource(R.string.imported_profile_data_chain_title),
                            body = chainLabels.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (review.feedbackLoopRisks.isNotEmpty()) {
                    item {
                        InlineNotice(
                            title = stringResource(R.string.imported_profile_feedback_title),
                            body = feedbackLabels.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (lintLabels.isNotEmpty()) {
                    item {
                        InlineNotice(
                            title = stringResource(R.string.automation_lint_title),
                            body = lintLabels.joinToString("\n"),
                            color = if (review.lintFindings.any { it.severity == AutomationLintSeverity.BLOCKING }) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                        )
                    }
                }
                if (!review.canAcknowledge) {
                    item {
                        InlineNotice(
                            title = stringResource(R.string.imported_profile_blocked_title),
                            body = stringResource(
                                R.string.imported_profile_blocked_body,
                                (
                                    review.unsupportedActionIds +
                                        review.risk.unknownActionIds +
                                        missingTaskLabels
                                    ).sorted().joinToString(),
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                        ) {
                            Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                            Text(stringResource(R.string.imported_profile_acknowledgement))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = review.canAcknowledge && acknowledged,
                onClick = onAcknowledgeAndEnable,
            ) {
                Text(stringResource(R.string.imported_profile_acknowledge_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
