package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Variable
import com.opentasker.ui.components.SelectionBar
import com.opentasker.ui.components.SelectionCheck
import com.opentasker.ui.components.selectableItem

private val SENSITIVE_NAMES = setOf("password", "token", "secret", "key", "credential", "auth")

private fun isSensitive(name: String): Boolean =
    SENSITIVE_NAMES.any { name.lowercase().contains(it) }

/** Stable selection key for a variable (name alone isn't unique across scopes). */
fun variableKey(v: Variable): String = "${v.projectId}:${v.name}"

@Composable
fun VariablesScreen(
    variables: List<Variable>,
    contentPadding: PaddingValues,
    onUpdate: (projectId: Long, name: String, value: String) -> Unit,
    onDelete: (projectId: Long, name: String) -> Unit,
    onMessage: (String) -> Unit,
    expandedVars: SnapshotStateMap<String, Boolean>,
    selectedKeys: Set<String>,
    onLongPressVar: (Variable) -> Unit,
    onToggleSelectVar: (Variable) -> Unit,
    onSelectAllVars: () -> Unit,
    onClearVarSelection: () -> Unit,
    onDeleteSelectedVars: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<Variable?>(null) }
    val selectionActive = selectedKeys.isNotEmpty()

    val filtered = remember(variables, searchQuery) {
        if (searchQuery.isBlank()) variables
        else variables.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.value.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        if (selectionActive) {
            SelectionBar(
                count = selectedKeys.size,
                total = variables.size,
                onSelectAll = onSelectAllVars,
                onClear = onClearVarSelection,
                onDelete = onDeleteSelectedVars,
            )
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search variables") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (filtered.isEmpty()) {
            Text(
                text = if (variables.isEmpty()) "No global variables set" else "No matches",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { variableKey(it) }) { variable ->
                val key = variableKey(variable)
                VariableRow(
                    variable = variable,
                    selectionActive = selectionActive,
                    selected = key in selectedKeys,
                    expanded = expandedVars[key] == true,
                    onToggleExpanded = { expandedVars[key] = expandedVars[key] != true },
                    onLongPress = { onLongPressVar(variable) },
                    onToggleSelect = { onToggleSelectVar(variable) },
                    onEdit = { editTarget = variable },
                    onDelete = { onDelete(variable.projectId, variable.name) },
                )
            }
        }
    }

    editTarget?.let { target ->
        EditVariableDialog(
            variable = target,
            onDismiss = { editTarget = null },
            onSave = { newValue ->
                onUpdate(target.projectId, target.name, newValue)
                editTarget = null
                onMessage("Updated ${target.name}")
            },
        )
    }
}

@Composable
private fun VariableRow(
    variable: Variable,
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize().selectableItem(
            selectionActive = selectionActive,
            onLongPress = onLongPress,
            onToggleSelect = onToggleSelect,
            onTapNormal = onToggleExpanded,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "%${variable.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = if (variable.projectId == 0L) "super-global" else "project-global",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse variable" else "Expand variable",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Text(
                    text = if (isSensitive(variable.name)) "***" else variable.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit variable")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete variable")
                    }
                }
            }
        }
    }
}

@Composable
private fun EditVariableDialog(
    variable: Variable,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(variable.value) }

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("%${variable.name}") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
