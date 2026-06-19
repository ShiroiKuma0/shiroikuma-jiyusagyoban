package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Variable
import com.opentasker.ui.theme.DesignSystem

private val SENSITIVE_NAMES = setOf("password", "token", "secret", "key", "credential", "auth")

private fun isSensitive(name: String): Boolean =
    SENSITIVE_NAMES.any { name.lowercase().contains(it) }

@Composable
fun VariablesScreen(
    variables: List<Variable>,
    contentPadding: PaddingValues,
    onUpdate: (name: String, value: String) -> Unit,
    onDelete: (name: String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var editTargetName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteName by rememberSaveable { mutableStateOf<String?>(null) }

    val filtered = remember(variables, searchQuery) {
        if (searchQuery.isBlank()) variables
        else variables.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.value.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.cardGap),
    ) {
        item {
            VariableSummaryCard(
                totalCount = variables.size,
                visibleCount = filtered.size,
                sensitiveCount = variables.count { isSensitive(it.name) },
            )
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.variables_search_label)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.variables_search_label)) },
                trailingIcon = if (searchQuery.isNotBlank()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.variables_search_clear))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(DesignSystem.Radii.md),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (filtered.isEmpty()) {
            item {
                VariableEmptyState(
                    title = if (variables.isEmpty()) stringResource(R.string.empty_variables_title) else stringResource(R.string.empty_variables_search),
                    body = if (variables.isEmpty()) {
                        stringResource(R.string.empty_variables_body)
                    } else {
                        stringResource(R.string.empty_variables_search_body)
                    },
                )
            }
        } else {
            items(filtered, key = { it.name }) { variable ->
                VariableRow(
                    variable = variable,
                    onEdit = { editTargetName = variable.name },
                    onDelete = { pendingDeleteName = variable.name },
                )
            }
        }
    }

    val editTarget = remember(variables, editTargetName) {
        editTargetName?.let { targetName -> variables.firstOrNull { it.name == targetName } }
    }

    pendingDeleteName?.let { name ->
        val deletedMsg = stringResource(R.string.variables_deleted, name)
        AlertDialog(
            onDismissRequest = { pendingDeleteName = null },
            icon = {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.dialog_delete_variable, name)) },
            text = {
                Text(
                    stringResource(R.string.variables_delete_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(name)
                        pendingDeleteName = null
                        if (editTargetName == name) editTargetName = null
                        onMessage(deletedMsg)
                    },
                ) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteName = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    editTarget?.let { target ->
        val updatedMsg = stringResource(R.string.variables_updated, target.name)
        EditVariableDialog(
            variable = target,
            onDismiss = { editTargetName = null },
            onSave = { newValue ->
                onUpdate(target.name, newValue)
                editTargetName = null
                onMessage(updatedMsg)
            },
        )
    }
}

@Composable
private fun VariableSummaryCard(
    totalCount: Int,
    visibleCount: Int,
    sensitiveCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DesignSystem.Opacity.elevatedSurface)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = DesignSystem.Opacity.subtleBorder)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(DesignSystem.Screen.heroCardPadding), verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.sectionGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_variable_vault), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.empty_variables_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VariablePill(
                    label = "$visibleCount shown",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VariableMetric("$totalCount", "Saved", Modifier.weight(1f))
                VariableMetric("$sensitiveCount", stringResource(R.string.label_masked), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VariableMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VariableEmptyState(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VariableRow(
    variable: Variable,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val sensitive = isSensitive(variable.name)
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DesignSystem.Opacity.restingSurface)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = DesignSystem.Opacity.subtleBorder)),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "%${variable.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (sensitive) stringResource(R.string.variables_masked_value) else variable.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sensitive) {
                    VariablePill(stringResource(R.string.label_hidden), MaterialTheme.colorScheme.secondary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VariablePill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun EditVariableDialog(
    variable: Variable,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable(variable.name) { mutableStateOf(variable.value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("%${variable.name}") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.variables_value_label, variable.name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
