package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

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
    onAddContext: () -> Unit = {},
    db: AppDatabase? = null,
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var contexts by remember { mutableStateOf(profile?.contexts ?: emptyList()) }
    var enterTaskId by remember { mutableStateOf(profile?.enterTaskId ?: 0L) }
    var exitTaskId by remember { mutableStateOf(profile?.exitTaskId) }
    var cooldownSec by remember { mutableStateOf(profile?.cooldownSec?.toString() ?: "0") }
    
    // Load available tasks
    val availableTasks = remember { mutableStateOf(emptyList<Task>()) }
    if (db != null) {
        val taskListVM: TaskListViewModel = viewModel(
            factory = TaskListViewModelFactory(db)
        )
        val tasks by taskListVM.tasks.collectAsState()
        availableTasks.value = tasks
    }

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
                "Contexts (${contexts.size})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )

            if (contexts.isEmpty()) {
                Text("No contexts configured", style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn {
                    items(contexts.size) { i ->
                        ContextListItem(
                            context = contexts[i],
                            onDelete = {
                                contexts = contexts.filterIndexed { idx, _ -> idx != i }
                            }
                        )
                    }
                }
            }

            Button(
                onClick = onAddContext,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Add Context")
            }

            Text(
                "Enter task (click to select)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            // Task picker dropdown
            if (availableTasks.value.isNotEmpty()) {
                var expandedEnter by remember { mutableStateOf(false) }
                val selectedEnterTask = availableTasks.value.find { it.id == enterTaskId }
                
                Button(
                    onClick = { expandedEnter = !expandedEnter },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedEnterTask?.name ?: "Select enter task")
                }
                
                if (expandedEnter) {
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)) {
                        LazyColumn {
                            items(availableTasks.value.size) { idx ->
                                val task = availableTasks.value[idx]
                                Button(
                                    onClick = {
                                        enterTaskId = task.id
                                        expandedEnter = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(task.name)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else {
                Text("No tasks yet. Create one first.", style = MaterialTheme.typography.bodySmall)
            }
            
            Text(
                "Exit task (optional)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            if (availableTasks.value.isNotEmpty()) {
                var expandedExit by remember { mutableStateOf(false) }
                val selectedExitTask = availableTasks.value.find { it.id == exitTaskId }
                
                Button(
                    onClick = { expandedExit = !expandedExit },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedExitTask?.name ?: "Select exit task (or none)")
                }
                
                if (expandedExit) {
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)) {
                        LazyColumn {
                            item {
                                Button(
                                    onClick = {
                                        exitTaskId = null
                                        expandedExit = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("(None)")
                                }
                            }
                            items(availableTasks.value.size) { idx ->
                                val task = availableTasks.value[idx]
                                Button(
                                    onClick = {
                                        exitTaskId = task.id
                                        expandedExit = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(task.name)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

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
                            contexts = contexts,
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
    onTaskUpdated: (Task) -> Unit = {},
    onAddAction: () -> Unit = {},
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
                onClick = onAddAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Add Action")
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

class TaskListViewModelForList(private val db: AppDatabase) : ViewModel() {
    val tasks: StateFlow<List<Task>> = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )
}

class TaskListViewModelForListFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TaskListViewModelForList::class.java)) {
            return TaskListViewModelForList(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    db: AppDatabase,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: TaskListViewModelForList = viewModel(
        factory = TaskListViewModelForListFactory(db)
    )
    val tasks by viewModel.tasks.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            Button(onClick = onCreateTask) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
                Text("New Task")
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            if (tasks.isEmpty()) {
                Text(
                    "No tasks yet. Create one to get started!",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                LazyColumn {
                    items(tasks, key = { it.id }) { task ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${task.actions.size} actions",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Button(onClick = { onEditTask(task) }) {
                                    Text("Edit")
                                }
                                IconButton(onClick = { onDeleteTask(task) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
