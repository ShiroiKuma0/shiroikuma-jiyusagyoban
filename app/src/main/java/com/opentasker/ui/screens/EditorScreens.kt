package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.opentasker.core.validation.InputValidation
import com.opentasker.ui.components.LoadingButton
import com.opentasker.ui.components.TextFieldWithError
import com.opentasker.ui.theme.DesignSystem
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
    var isSaving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var cooldownError by remember { mutableStateOf<String?>(null) }
    
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
                title = { 
                    Text(
                        if (profile == null) "New Profile" else "Edit ${profile.name}",
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(DesignSystem.Spacing.lg)
        ) {
            TextFieldWithError(
                value = name,
                onValueChange = { 
                    name = it
                    nameError = null
                },
                label = "Profile name",
                error = nameError,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
            Text(
                "Contexts (${contexts.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))

            if (contexts.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DesignSystem.Spacing.md),
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                    elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm)
                ) {
                    Text(
                        "No contexts yet. Add one to define triggers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(DesignSystem.Spacing.lg)
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
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

            Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
            ElevatedButton(
                onClick = onAddContext,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
                Text("Add Context")
            }

            Text(
                "Enter task (required)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = DesignSystem.Spacing.xl)
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DesignSystem.Spacing.md),
                        shape = RoundedCornerShape(DesignSystem.Radii.md)
                    ) {
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
                modifier = Modifier.padding(top = DesignSystem.Spacing.xl)
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
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DesignSystem.Spacing.md),
                        shape = RoundedCornerShape(DesignSystem.Radii.md)
                    ) {
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

            TextFieldWithError(
                value = cooldownSec,
                onValueChange = { 
                    cooldownSec = it
                    cooldownError = null
                },
                label = "Cooldown (seconds)",
                error = cooldownError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DesignSystem.Spacing.md)
            )

            Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
            LoadingButton(
                onClick = {
                    // Validate input
                    nameError = if (name.trim().isEmpty()) "Profile name cannot be empty" else null
                    cooldownError = if (cooldownSec.toIntOrNull() == null) "Cooldown must be a valid number" else null
                    
                    if (nameError == null && cooldownError == null) {
                        isSaving = true
                        onSave(
                            Profile(
                                id = profile?.id ?: 0,
                                name = name.trim(),
                                enabled = profile?.enabled ?: true,
                                contexts = contexts,
                                enterTaskId = enterTaskId,
                                exitTaskId = exitTaskId,
                                cooldownSec = cooldownSec.toIntOrNull() ?: 0
                            )
                        )
                        isSaving = false
                    }
                },
                isLoading = isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Profile")
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
    var isSaving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var priorityError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (task == null) "New Task" else "Edit ${task.name}",
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(DesignSystem.Spacing.lg)
        ) {
            TextFieldWithError(
                value = name,
                onValueChange = { 
                    name = it
                    nameError = null
                },
                label = "Task name",
                error = nameError,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            TextFieldWithError(
                value = priority,
                onValueChange = { 
                    priority = it
                    priorityError = null
                },
                label = "Priority (0-100)",
                error = priorityError,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DesignSystem.Spacing.md)
            )

            Text(
                "Collision mode",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = DesignSystem.Spacing.md)
            )
            
            var collisionModeExpanded by remember { mutableStateOf(false) }
            Button(
                onClick = { collisionModeExpanded = !collisionModeExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(collisionMode)
            }
            
            if (collisionModeExpanded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = DesignSystem.Spacing.sm),
                    shape = RoundedCornerShape(DesignSystem.Radii.md)
                ) {
                    Column {
                        listOf("QUEUE", "INTERRUPT").forEach { mode ->
                            Button(
                                onClick = {
                                    collisionMode = mode
                                    collisionModeExpanded = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(mode)
                            }
                            if (mode != "INTERRUPT") HorizontalDivider()
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = DesignSystem.Spacing.xl))

            Text(
                "Actions (${actions.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = DesignSystem.Spacing.lg)
            )

            if (actions.isNotEmpty()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                    items(actions.size) { i ->
                        val action = actions[i]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(DesignSystem.Radii.md),
                            elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(DesignSystem.Spacing.lg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(action.type, style = MaterialTheme.typography.labelMedium)
                                    action.label?.let { 
                                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) 
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        actions = actions.filterIndexed { idx, _ -> idx != i }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.Delete, 
                                        contentDescription = "Delete action",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DesignSystem.Spacing.md),
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                    elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm)
                ) {
                    Text(
                        "No actions yet. Add one to define what happens.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(DesignSystem.Spacing.lg)
                    )
                }
            }

            Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
            ElevatedButton(
                onClick = onAddAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
                Text("Add Action")
            }

            Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
            LoadingButton(
                onClick = {
                    // Validate input
                    nameError = if (name.trim().isEmpty()) "Task name cannot be empty" else null
                    priorityError = if (priority.toIntOrNull() == null) "Priority must be a valid number" else null
                    
                    if (nameError == null && priorityError == null) {
                        isSaving = true
                        try {
                            onSave(
                                Task(
                                    id = task?.id ?: 0,
                                    name = name.trim(),
                                    actions = actions,
                                    priority = priority.toIntOrNull() ?: 0,
                                    collisionMode = CollisionMode.valueOf(collisionMode)
                                )
                            )
                        } catch (e: Exception) {
                            nameError = "Failed to save task: ${e.message}"
                        } finally {
                            isSaving = false
                        }
                    }
                },
                isLoading = isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Task")
            }
        }
    }
}
