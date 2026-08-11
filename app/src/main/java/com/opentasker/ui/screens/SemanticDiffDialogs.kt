package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.diff.SemanticDiffChange
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.diff.SemanticDiffEntity
import com.opentasker.core.diff.SemanticDiffEntry
import com.opentasker.core.diff.SemanticDiffKind
import com.opentasker.core.diff.SemanticDiffStrings
import com.opentasker.ui.theme.DesignSystem

@Composable
internal fun SemanticDiffDialog(
    document: SemanticDiffDocument,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.semantic_diff_title)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item { SemanticDiffSummary(document) }
                SemanticDiffDetails(document)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/**
 * Emits one lazy row per entry. This used to build every entry inside a single `item`, so a large
 * bundle import composed hundreds of rows at once inside a dialog with no recycling.
 */
internal fun LazyListScope.SemanticDiffDetails(document: SemanticDiffDocument) {
    items(document.entries) { entry -> SemanticDiffEntryView(entry) }
}

@Composable
internal fun SemanticDiffSummary(document: SemanticDiffDocument) {
    if (document.isEmpty) {
        Text(
            stringResource(R.string.semantic_diff_no_changes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
        Text(
            stringResource(
                R.string.semantic_diff_summary,
                pluralStringResource(R.plurals.semantic_diff_summary_changes, document.changeCount, document.changeCount),
                pluralStringResource(R.plurals.semantic_diff_summary_profiles, document.entries.size, document.entries.size),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (document.flowNodeKeys.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.semantic_diff_flow_note,
                    document.flowNodeKeys.size,
                    document.flowNodeKeys.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun SemanticDiffEntryView(entry: SemanticDiffEntry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs),
    ) {
        Text(
            stringResource(R.string.semantic_diff_entry_title, entityLabel(entry.entity), entry.name),
            style = MaterialTheme.typography.titleSmall,
        )
        entry.changes.forEach { change -> SemanticDiffChangeView(change) }
    }
}

@Composable
private fun SemanticDiffChangeView(change: SemanticDiffChange) {
    val strings = SemanticDiffStrings.from(LocalContext.current.resources)
    val color = when (change.kind) {
        SemanticDiffKind.ADDED -> MaterialTheme.colorScheme.tertiary
        SemanticDiffKind.REMOVED -> MaterialTheme.colorScheme.error
        SemanticDiffKind.CHANGED -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.08f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(DesignSystem.Radii.md),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                Text(
                    text = kindLabel(change.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
                Text(strings.path(change.path), style = MaterialTheme.typography.labelMedium)
            }
            when (change.kind) {
                SemanticDiffKind.ADDED -> Text(
                    stringResource(R.string.semantic_diff_after_value, strings.value(change.path, change.after)),
                    style = MaterialTheme.typography.bodySmall,
                )
                SemanticDiffKind.REMOVED -> Text(
                    stringResource(R.string.semantic_diff_before_value, strings.value(change.path, change.before)),
                    style = MaterialTheme.typography.bodySmall,
                )
                SemanticDiffKind.CHANGED -> Text(
                    stringResource(
                        R.string.semantic_diff_before_after,
                        strings.value(change.path, change.before),
                        strings.value(change.path, change.after),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: SemanticDiffKind): String = stringResource(
    when (kind) {
        SemanticDiffKind.ADDED -> R.string.semantic_diff_added
        SemanticDiffKind.REMOVED -> R.string.semantic_diff_removed
        SemanticDiffKind.CHANGED -> R.string.semantic_diff_changed
    },
)

@Composable
private fun entityLabel(entity: SemanticDiffEntity): String = stringResource(
    when (entity) {
        SemanticDiffEntity.PROFILE -> R.string.semantic_diff_entity_profile
        SemanticDiffEntity.TASK -> R.string.semantic_diff_entity_task
        SemanticDiffEntity.SCENE -> R.string.semantic_diff_entity_scene
        SemanticDiffEntity.VARIABLE -> R.string.semantic_diff_entity_variable
    },
)
