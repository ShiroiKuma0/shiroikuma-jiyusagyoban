package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R

@Composable
internal fun OpenTaskerBundleReviewDialog(
    state: OpenTaskerBundleReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bundle = state.bundle
    val plan = state.plan
    val reviewWarnings = (bundle.metadata.warnings + plan.warnings + plan.lossyWarnings).distinct()
    val capabilityRequirements = bundle.metadata.capabilityRequirements
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.dialog_review_bundle)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.import_disabled_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    InlineNotice(
                        title = bundle.metadata.name.ifBlank { stringResource(R.string.import_opentasker_bundle) },
                        body = "Schema ${bundle.schemaVersion} - exported by app ${bundle.appVersion}",
                        color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.tasks.size}", stringResource(R.string.import_count_tasks), Modifier.weight(1f))
                        SummaryMetric("${bundle.profiles.size}", stringResource(R.string.import_count_profiles), Modifier.weight(1f))
                        SummaryMetric("${bundle.variables.size}", stringResource(R.string.import_count_variables), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.scenes.size}", stringResource(R.string.import_count_scenes), Modifier.weight(1f))
                        SummaryMetric("${capabilityRequirements.size}", stringResource(R.string.import_count_setup_notes), Modifier.weight(1f))
                        SummaryMetric("${reviewWarnings.size}", stringResource(R.string.import_count_warnings), Modifier.weight(1f))
                    }
                }
                if (!plan.canImport) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_incompatible),
                            values = plan.warnings.ifEmpty { listOf(stringResource(R.string.import_incompatible_body)) },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (capabilityRequirements.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_capability_review),
                            values = capabilityRequirements.map {
                                "${it.actionId}: ${it.level.name.lowercase().replace('_', ' ')} - ${it.reason}"
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (reviewWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_warnings),
                            values = reviewWarnings,
                            color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = plan.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) stringResource(R.string.status_importing) else stringResource(R.string.import_disabled))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun TaskerImportReviewDialog(
    state: TaskerImportReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = state.preview
    val migrationWarnings = (preview.warnings + preview.lossyWarnings).distinct()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.dialog_review_tasker)) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.import_disabled_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.importTaskCount}", stringResource(R.string.import_count_tasks), Modifier.weight(1f))
                        SummaryMetric("${preview.importProfileCount}", stringResource(R.string.import_count_profiles), Modifier.weight(1f))
                        SummaryMetric("${preview.importVariableCount}", stringResource(R.string.import_count_variables), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.sourceTaskCount}", stringResource(R.string.import_count_src_tasks), Modifier.weight(1f))
                        SummaryMetric("${preview.sourceProfileCount}", stringResource(R.string.import_count_src_profiles), Modifier.weight(1f))
                        SummaryMetric("${preview.sourceSceneCount}", stringResource(R.string.import_count_scenes), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("${preview.mappedActionCount} ${stringResource(R.string.import_mapped)}", MaterialTheme.colorScheme.tertiary)
                        StatusPill("${preview.unsupportedActionCount} ${stringResource(R.string.import_unsupported)}", MaterialTheme.colorScheme.error)
                    }
                }
                if (preview.capabilityWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_capability_review),
                            values = preview.capabilityWarnings,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (migrationWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_migration_warnings),
                            values = migrationWarnings,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.report.unsupportedActions.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_unsupported_actions),
                            values = state.report.unsupportedActions.map {
                                "${it.taskName} step ${it.actionIndex + 1}: code ${it.taskerCode}"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.report.mappedActions.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = stringResource(R.string.import_mapped_actions),
                            values = state.report.mappedActions.map {
                                "${it.taskName}: ${it.taskerCode} -> ${it.openTaskerActionId}"
                            },
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) stringResource(R.string.status_importing) else stringResource(R.string.import_for_review))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun TaskerImportListSection(
    title: String,
    values: List<String>,
    color: Color,
) {
    InlineNotice(
        title = title,
        body = values.take(5).joinToString("\n") + if (values.size > 5) "\n${values.size - 5} more" else "",
        color = color,
    )
}
