package com.opentasker.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Variable

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
    var searchQuery by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<Variable?>(null) }

    val filtered = remember(variables, searchQuery) {
        if (searchQuery.isBlank()) variables
        else variables.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.value.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
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
            items(filtered, key = { it.name }) { variable ->
                VariableRow(
                    variable = variable,
                    onEdit = { editTarget = variable },
                    onDelete = { onDelete(variable.name) },
                )
            }
        }
    }

    editTarget?.let { target ->
        EditVariableDialog(
            variable = target,
            onDismiss = { editTarget = null },
            onSave = { newValue ->
                onUpdate(target.name, newValue)
                editTarget = null
                onMessage("Updated ${target.name}")
            },
        )
    }
}

@Composable
private fun VariableRow(
    variable: Variable,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "%${variable.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = if (isSensitive(variable.name)) "***" else variable.value,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete variable")
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
