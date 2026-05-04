package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType

/**
 * Screen to pick a context type and configure it for a profile.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ContextPickerScreen(
    onContextSelected: (ContextSpec) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedType by remember { mutableStateOf<ContextType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Context") },
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
            if (selectedType == null) {
                // Type picker
                Text("Select context type:", style = MaterialTheme.typography.titleMedium)
                LazyColumn {
                    items(ContextType.entries.toList()) { contextType ->
                        Button(
                            onClick = { selectedType = contextType },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(contextType.name)
                        }
                    }
                }
            } else {
                // Config editor for selected type
                Button(
                    onClick = { selectedType = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("← Back")
                }
                Spacer(modifier = Modifier.height(8.dp))
                ContextConfigEditor(
                    contextType = selectedType!!,
                    onSave = { config ->
                        onContextSelected(
                            ContextSpec(
                                type = selectedType!!,
                                config = config
                            )
                        )
                    },
                    onCancel = { selectedType = null }
                )
            }
        }
    }
}

/**
 * Dynamic form editor for configuring a context based on its type.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ContextConfigEditor(
    contextType: ContextType,
    onSave: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val config = remember { mutableMapOf<String, String>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure ${contextType.name}") }
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
                when (contextType) {
                    ContextType.APPLICATION -> {
                        item {
                            Text("Apps to monitor (comma-separated package names):", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = config.getOrDefault("apps", ""),
                                onValueChange = { config["apps"] = it },
                                label = { Text("Package names") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                minLines = 3,
                                placeholder = { Text("com.android.chrome\ncom.spotify.music") }
                            )
                        }
                    }

                    ContextType.TIME -> {
                        item {
                            Text("Time window:", style = MaterialTheme.typography.bodyMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = config.getOrDefault("from", "09:00"),
                                    onValueChange = { config["from"] = it },
                                    label = { Text("From (HH:MM)") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = config.getOrDefault("to", "17:00"),
                                    onValueChange = { config["to"] = it },
                                    label = { Text("To (HH:MM)") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Optional: Days (SMTWTFS, default all)", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = config.getOrDefault("days", ""),
                                onValueChange = { config["days"] = it },
                                label = { Text("Days (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("SMTWTFS") }
                            )
                        }
                    }

                    ContextType.DAY -> {
                        item {
                            Text("Days of week:", style = MaterialTheme.typography.bodyMedium)
                            val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
                            days.forEach { day ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(day)
                                    Switch(
                                        checked = config.getOrDefault("days", "").contains(day.first()),
                                        onCheckedChange = { checked ->
                                            val dayChar = day.first().toString()
                                            val current = config.getOrDefault("days", "")
                                            config["days"] = if (checked) {
                                                current + dayChar
                                            } else {
                                                current.replace(dayChar, "")
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    ContextType.LOCATION -> {
                        item {
                            Text("Geofence:", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = config.getOrDefault("latitude", ""),
                                onValueChange = { config["latitude"] = it },
                                label = { Text("Latitude") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = config.getOrDefault("longitude", ""),
                                onValueChange = { config["longitude"] = it },
                                label = { Text("Longitude") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = config.getOrDefault("radius", "100"),
                                onValueChange = { config["radius"] = it },
                                label = { Text("Radius (meters)") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                singleLine = true
                            )
                        }
                    }

                    ContextType.STATE -> {
                        item {
                            Text("Device state to monitor:", style = MaterialTheme.typography.bodyMedium)
                            val stateOptions = listOf("battery", "headphones", "charging", "screen", "wifi", "cellular")
                            stateOptions.forEach { state ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(state.capitalize())
                                    Switch(
                                        checked = config.getOrDefault("state", "").contains(state),
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                config["state"] = state
                                            }
                                        }
                                    )
                                }
                            }
                            if (config.containsKey("state")) {
                                OutlinedTextField(
                                    value = config.getOrDefault("value", "on"),
                                    onValueChange = { config["value"] = it },
                                    label = { Text("Value (on/off/yes/no, etc.)") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    singleLine = true
                                )
                            }
                        }
                    }

                    ContextType.EVENT -> {
                        item {
                            Text("Event type:", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = config.getOrDefault("event", ""),
                                onValueChange = { config["event"] = it },
                                label = { Text("Event type (SMS, notification, intent, etc.)") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                minLines = 2
                            )
                        }
                    }
                }
            }

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
                    onClick = { onSave(config) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }
}

/**
 * Display a list of configured contexts for a profile, with ability to add/remove.
 */
@Composable
fun ContextListItem(
    context: ContextSpec,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(context.type.name, style = MaterialTheme.typography.titleSmall)
                if (context.invert) {
                    Text("Inverted", style = MaterialTheme.typography.labelSmall)
                }
                context.config.forEach { (key, value) ->
                    Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
