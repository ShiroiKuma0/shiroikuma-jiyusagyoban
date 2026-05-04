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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class TaskListViewModel(private val db: AppDatabase) : ViewModel() {
    val tasks: StateFlow<List<Task>> = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )
}

class TaskListViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskListViewModel::class.java)) {
            return TaskListViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

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
    var priority by remember { mutableStateOf(task?.priority?.toString() ?: "0") }
    var collisionMode by remember { mutableStateOf(task?.collisionMode?.name ?: "QUEUE") }

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

            OutlinedTextField(
                value = priority,
                onValueChange = { priority = it },
                label = { Text("Priority (0-100)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            OutlinedTextField(
                value = collisionMode,
                onValueChange = { collisionMode = it },
                label = { Text("Collision mode (QUEUE/INTERRUPT)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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
                    try {
                        onSave(
                            Task(
                                id = task?.id ?: 0,
                                name = name,
                                priority = priority.toIntOrNull() ?: 0,
                                collisionMode = CollisionMode.valueOf(collisionMode.uppercase()),
                                actions = actions,
                            )
                        )
                    } catch (e: Exception) {
                        // TODO: show error toast
                    }
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
