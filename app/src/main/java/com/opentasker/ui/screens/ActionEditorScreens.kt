package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.model.ActionSpec

/**
 * Screen to pick an action type and configure its arguments.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActionPickerScreen(
    onActionSelected: (ActionSpec) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    val categories = listOf("Notification", "Variable", "Settings", "App", "File", "Network", "Media", "System", "Flow")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Action") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            if (selectedCategory == null) {
                // Category picker
                Text("Select action category:", style = MaterialTheme.typography.titleMedium)
                LazyColumn {
                    items(categories) { category ->
                        Button(
                            onClick = { selectedCategory = category },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(category)
                        }
                    }
                }
            } else {
                // Action picker for selected category
                Button(
                    onClick = { selectedCategory = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("← Back")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Select action:", style = MaterialTheme.typography.titleMedium)
                val actions = ActionMetadataRegistry.byCategory(selectedCategory ?: "")
                LazyColumn {
                    items(actions) { metadata ->
                        ActionMetadataCard(
                            metadata = metadata,
                            onClick = {
                                // Go to editor to configure this action
                                selectedCategory = null // Reset
                                onActionSelected(ActionSpec(type = metadata.id))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActionMetadataCard(
    metadata: ActionMetadata,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(metadata.name, style = MaterialTheme.typography.titleSmall)
            Text(metadata.description, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = onClick,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.End)
                    .padding(top = 8.dp)
            ) {
                Text("Select")
            }
        }
    }
}

/**
 * Screen to edit action arguments based on the action's metadata.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActionEditorScreen(
    actionSpec: ActionSpec,
    onSave: (ActionSpec) -> Unit,
    onCancel: () -> Unit,
) {
    val metadata = ActionMetadataRegistry.get(actionSpec.type)
    var editedArgs by remember { mutableStateOf(actionSpec.args.toMutableMap()) }
    var label by remember { mutableStateOf(actionSpec.label ?: "") }
    var continueOnError by remember { mutableStateOf(actionSpec.continueOnError) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metadata?.name ?: actionSpec.type) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                // Description
                item {
                    metadata?.description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // Action label (optional)
                item {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Action fields
                if (metadata != null) {
                    items(metadata.fields) { field ->
                        when (field.fieldType) {
                            FieldType.TEXT -> {
                                OutlinedTextField(
                                    value = editedArgs[field.key] ?: "",
                                    onValueChange = { editedArgs[field.key] = it },
                                    label = { Text(field.label) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    singleLine = true,
                                    isError = field.required && editedArgs[field.key].isNullOrBlank()
                                )
                            }
                            FieldType.NUMBER -> {
                                OutlinedTextField(
                                    value = editedArgs[field.key] ?: "",
                                    onValueChange = { editedArgs[field.key] = it },
                                    label = { Text(field.label) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    singleLine = true,
                                    isError = field.required && editedArgs[field.key].isNullOrBlank()
                                )
                            }
                            FieldType.MULTILINE -> {
                                OutlinedTextField(
                                    value = editedArgs[field.key] ?: "",
                                    onValueChange = { editedArgs[field.key] = it },
                                    label = { Text(field.label) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .heightIn(min = 80.dp),
                                    minLines = 3,
                                    isError = field.required && editedArgs[field.key].isNullOrBlank()
                                )
                            }
                            FieldType.DROPDOWN -> {
                                // Simple text input for now; could be expanded to real dropdown
                                OutlinedTextField(
                                    value = editedArgs[field.key] ?: "",
                                    onValueChange = { editedArgs[field.key] = it },
                                    label = { Text(field.label) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    singleLine = true,
                                    isError = field.required && editedArgs[field.key].isNullOrBlank(),
                                    placeholder = { Text(field.hint ?: "") }
                                )
                            }
                            FieldType.CHECKBOX -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(field.label)
                                    Switch(
                                        checked = editedArgs[field.key]?.toBoolean() ?: false,
                                        onCheckedChange = { editedArgs[field.key] = it.toString() }
                                    )
                                }
                            }
                        }
                    }
                }

                // Continue on error option
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Continue on error", modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
                        Switch(
                            checked = continueOnError,
                            onCheckedChange = { continueOnError = it }
                        )
                    }
                }
            }

            // Save/Cancel buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSave(
                            actionSpec.copy(
                                label = label.ifBlank { null },
                                args = editedArgs,
                                continueOnError = continueOnError
                            )
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}
