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
import androidx.compose.ui.unit.dp

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
        title = { Text("Review OpenTasker bundle") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                item {
                    Text(
                        "Imported profiles will be created disabled so contexts, actions, and permissions can be reviewed before use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    InlineNotice(
                        title = bundle.metadata.name.ifBlank { "OpenTasker bundle" },
                        body = "Schema ${bundle.schemaVersion} - exported by app ${bundle.appVersion}",
                        color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.tasks.size}", "Tasks", Modifier.weight(1f))
                        SummaryMetric("${bundle.profiles.size}", "Profiles", Modifier.weight(1f))
                        SummaryMetric("${bundle.variables.size}", "Variables", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.scenes.size}", "Scenes", Modifier.weight(1f))
                        SummaryMetric("${capabilityRequirements.size}", "Setup notes", Modifier.weight(1f))
                        SummaryMetric("${reviewWarnings.size}", "Warnings", Modifier.weight(1f))
                    }
                }
                if (!plan.canImport) {
                    item {
                        TaskerImportListSection(
                            title = "Cannot import",
                            values = plan.warnings.ifEmpty { listOf("Bundle schema is not compatible with this build.") },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (capabilityRequirements.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Capability review",
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
                            title = "Import warnings",
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
                Text(if (busy) "Importing..." else "Import Disabled")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel")
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
        title = { Text("Review Tasker import") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
            ) {
                item {
                    Text(
                        "Imported profiles will be created disabled so actions, contexts, and permissions can be reviewed before use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.importTaskCount}", "Tasks", Modifier.weight(1f))
                        SummaryMetric("${preview.importProfileCount}", "Profiles", Modifier.weight(1f))
                        SummaryMetric("${preview.importVariableCount}", "Variables", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.sourceTaskCount}", "Src tasks", Modifier.weight(1f))
                        SummaryMetric("${preview.sourceProfileCount}", "Src profiles", Modifier.weight(1f))
                        SummaryMetric("${preview.sourceSceneCount}", "Scenes", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("${preview.mappedActionCount} mapped", MaterialTheme.colorScheme.tertiary)
                        StatusPill("${preview.unsupportedActionCount} unsupported", MaterialTheme.colorScheme.error)
                    }
                }
                if (preview.capabilityWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Capability review",
                            values = preview.capabilityWarnings,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (migrationWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Migration warnings",
                            values = migrationWarnings,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.report.unsupportedActions.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Unsupported Tasker actions",
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
                            title = "Mapped actions",
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
                Text(if (busy) "Importing..." else "Import for Review")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel")
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
