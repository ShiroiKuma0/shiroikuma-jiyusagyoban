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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem

@Composable
internal fun TaskerImportReviewDialog(
    state: TaskerImportReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    progress: TransferProgress? = null,
    onCancel: () -> Unit = {},
    onConfirm: () -> Unit,
) {
    val preview = state.preview
    val migrationWarnings = (preview.warnings + preview.lossyWarnings).distinct()
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(state.title) },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) item { TransferProgressRow(progress) }
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
                if (state.unsupportedActionRows.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Unsupported actions",
                            values = state.unsupportedActionRows,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.mappedActionRows.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Mapped actions",
                            values = state.mappedActionRows,
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
        // Stays enabled while busy. Disabling it left the only way out of a long import as force
        // stopping the app, because nothing held the job to cancel.
        dismissButton = {
            TextButton(onClick = if (busy) onCancel else onDismiss) {
                Text(stringResource(if (busy) R.string.action_stop else R.string.action_cancel))
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
        body = values.take(5).joinToString("\n") + if (values.size > 5) {
            "\n" + pluralStringResource(R.plurals.import_review_more, values.size - 5, values.size - 5)
        } else {
            ""
        },
        color = color,
    )
}

/**
 * What a running transfer is doing. A relabelled button could not distinguish reading a 16 MiB
 * backup from writing it, so a large import looked identical to a hang.
 */
@Composable
internal fun TransferProgressRow(progress: TransferProgress?) {
    if (progress == null) return
    Column(
        // The stage label is the only thing that reports an import or export moving from one step
        // to the next, and it changes in place without moving focus.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs),
    ) {
        Text(
            stringResource(progress.stage.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun TransferStage.labelRes(): Int = when (this) {
    TransferStage.Preflight -> R.string.transfer_stage_preflight
    TransferStage.Decode -> R.string.transfer_stage_decode
    TransferStage.Plan -> R.string.transfer_stage_plan
    TransferStage.Write -> R.string.transfer_stage_write
}
