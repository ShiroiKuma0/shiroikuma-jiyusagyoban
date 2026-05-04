package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    profile: Profile?,
    onSave: (Profile) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var enterTaskId by remember { mutableStateOf(profile?.enterTaskId ?: 0L) }
    var exitTaskId by remember { mutableStateOf(profile?.exitTaskId) }
    var cooldownSec by remember { mutableStateOf(profile?.cooldownSec?.toString() ?: "0") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profile == null) "New Profile" else "Edit ${profile.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Profile name") },
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "Contexts (not yet implemented in UI)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )

            Text(
                "Enter task (click to select)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            // TODO: Task picker

            OutlinedTextField(
                value = cooldownSec,
                onValueChange = { cooldownSec = it },
                label = { Text("Cooldown (seconds)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Button(
                onClick = {
                    onSave(
                        Profile(
                            id = profile?.id ?: 0,
                            name = name,
                            enabled = profile?.enabled ?: true,
                            contexts = profile?.contexts ?: emptyList(),
                            enterTaskId = enterTaskId,
                            exitTaskId = exitTaskId,
                            cooldownSec = cooldownSec.toIntOrNull() ?: 0
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorScreen(
    task: Task?,
    onSave: (Task) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(task?.name ?: "") }
    var actions by remember { mutableStateOf(task?.actions ?: emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (task == null) "New Task" else "Edit ${task.name}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Task name") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

            Text(
                "Actions (${actions.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp)
            )

            LazyColumn {
                items(actions.size) { i ->
                    val action = actions[i]
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(action.type, style = MaterialTheme.typography.labelMedium)
                            action.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        IconButton(onClick = {
                            actions = actions.filterIndexed { idx, _ -> idx != i }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }

            Button(
                onClick = {
                    onSave(
                        Task(
                            id = task?.id ?: 0,
                            name = name,
                            actions = actions,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text("Save")
            }
        }
    }
}
