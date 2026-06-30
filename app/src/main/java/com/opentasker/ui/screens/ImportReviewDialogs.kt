package com.opentasker.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem

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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Review 白い熊 自由作業盤 bundle") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
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
                        title = bundle.metadata.name.ifBlank { "白い熊 自由作業盤 bundle" },
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
                        SummaryMetric("${bundle.templates.size}", "Templates", Modifier.weight(1f))
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
            OutlinedButton(
                enabled = plan.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) "Importing..." else "Import")
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Review Tasker import") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
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
            OutlinedButton(
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

/** Asks how to handle a bundle whose project name(s) already exist: import into the existing project
 *  (MERGE) or create a separate renamed project (RENAME). "Import into existing" is the default. */
@Composable
internal fun ImportProjectConflictDialog(
    conflictingNames: List<String>,
    onOverwrite: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit,
) {
    val single = conflictingNames.singleOrNull()
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Project already exists") },
        text = {
            Text(
                if (single != null) {
                    "A project named “$single” already exists. Import into it (file the imported items under the existing project), or create a separate new (renamed) project?"
                } else {
                    "These projects already exist: ${conflictingNames.joinToString { "“$it”" }}. Import into them, or create separate new (renamed) projects?"
                },
            )
        },
        // Stacked so long names don't overflow; default (import into existing) on top and emphasised
        // (a filled Button), matching the item-conflict dialog's "Overwrite".
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Button(onClick = onOverwrite) {
                    Text(if (single != null) "Import into “$single”" else "Import into existing")
                }
                TextButton(onClick = onKeepBoth) { Text("Create new project") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Asks how to handle bundle items (task/profile/scene/template) whose names already exist: overwrite
 *  in place (default), overwrite keeping a backup, or import as renamed copies. */
@Composable
internal fun ImportItemConflictDialog(
    collisions: List<String>,
    onRename: () -> Unit,
    onOverwriteDelete: () -> Unit,
    onOverwriteBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Some items already exist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("These already exist in your workspace:", style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    collisions.take(12).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall) }
                    if (collisions.size > 12) {
                        Text("…and ${collisions.size - 12} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "Overwrite updates each existing item in place (keeping its group, notes and links) — the default. Overwrite and backup renames the existing ones to “.<timestamp>.bak” first. Import with new names keeps both.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // Stacked; default (overwrite in place) on top and emphasised.
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Button(onClick = onOverwriteDelete) { Text("Overwrite") }
                TextButton(onClick = onOverwriteBackup) { Text("Overwrite and backup current") }
                TextButton(onClick = onRename) { Text("Import with new names") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
