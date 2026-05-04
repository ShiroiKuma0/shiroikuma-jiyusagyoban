package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.core.templates.TemplateAvailability
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class OpenTaskerScreen(val label: String) {
    Profiles("Profiles"),
    Tasks("Tasks"),
    Setup("Setup"),
    RunLog("Run Log"),
}

private data class ActionEditState(
    val task: Task,
    val metadata: ActionMetadata,
    val index: Int? = null,
    val existing: ActionSpec? = null,
)

private data class ContextEditState(
    val profile: Profile,
    val type: ContextType,
    val index: Int? = null,
    val existing: ContextSpec? = null,
)

class ActiveAutomationViewModel(private val db: AppDatabase) : ViewModel() {
    val profiles: StateFlow<List<Profile>> = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<Task>> = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runLogs: StateFlow<List<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    fun createTask(name: String, priority: Int) = launchWithMessage("Task created") {
        db.taskDao().insert(Task(name = name.trim(), priority = priority.coerceIn(0, 10)).toEntity())
    }

    fun updateTask(task: Task, message: String = "Task updated") = launchWithMessage(message) {
        db.taskDao().update(task.toEntity())
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            runCatching {
                val profilesUsingTask = db.profileDao().getAll().map { it.toDomain() }
                    .filter { it.enterTaskId == task.id || it.exitTaskId == task.id }
                if (profilesUsingTask.isNotEmpty()) {
                    events.send("Task is used by ${profilesUsingTask.size} profile(s). Reassign or delete those profiles first.")
                    return@launch
                }
                db.taskDao().delete(task.toEntity())
            }
                .onSuccess { events.send("Task deleted") }
                .onFailure { events.send("Error: ${it.message ?: "Task delete failed"}") }
        }
    }

    fun createProfile(name: String, enabled: Boolean, enterTaskId: Long, cooldownSec: Int, automationMode: AutomationMode) =
        launchWithMessage("Profile created") {
            db.profileDao().insert(
                Profile(
                    name = name.trim(),
                    enabled = enabled,
                    enterTaskId = enterTaskId,
                    cooldownSec = cooldownSec.coerceAtLeast(0),
                    automationMode = automationMode,
                ).toEntity()
            )
        }

    fun updateProfile(profile: Profile, message: String = "Profile updated") =
        launchWithMessage(message) {
            db.profileDao().update(profile.toEntity())
        }

    fun deleteProfile(profile: Profile) = launchWithMessage("Profile deleted") {
        db.profileDao().delete(profile.toEntity())
    }

    fun installProfileTemplate(template: ProfileTemplate, slotValues: Map<String, String>) =
        launchWithMessage("Template installed as a disabled profile") {
            val applied = template.instantiate(slotValues)
            db.withTransaction {
                val taskId = db.taskDao().insert(applied.task.toEntity())
                db.profileDao().insert(applied.profile.copy(enterTaskId = taskId).toEntity())
            }
        }

    private fun launchWithMessage(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { events.send(successMessage) }
                .onFailure { events.send("Error: ${it.message ?: "Operation failed"}") }
        }
    }
}

class ActiveAutomationViewModelFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActiveAutomationViewModel::class.java)) {
            return ActiveAutomationViewModel(db) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActiveAutomationUi(
    db: AppDatabase,
    modifier: Modifier = Modifier,
    viewModel: ActiveAutomationViewModel = viewModel(factory = ActiveAutomationViewModelFactory(db)),
) {
    val profiles by viewModel.profiles.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val runLogs by viewModel.runLogs.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(OpenTaskerScreen.Profiles) }
    var taskDialog by remember { mutableStateOf<Task?>(null) }
    var showCreateTaskDialog by remember { mutableStateOf(false) }
    var profileDialog by remember { mutableStateOf<Profile?>(null) }
    var showCreateProfileDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var selectedTemplate by remember { mutableStateOf<ProfileTemplate?>(null) }
    var actionPickerTask by remember { mutableStateOf<Task?>(null) }
    var actionEdit by remember { mutableStateOf<ActionEditState?>(null) }
    var contextPickerProfile by remember { mutableStateOf<Profile?>(null) }
    var contextEdit by remember { mutableStateOf<ContextEditState?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OpenTasker", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            screen.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            when (screen) {
                OpenTaskerScreen.Profiles -> FloatingActionButton(
                    onClick = {
                        if (tasks.isEmpty()) {
                            showCreateTaskDialog = true
                        } else {
                            showCreateProfileDialog = true
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create profile")
                }

                OpenTaskerScreen.Tasks -> FloatingActionButton(onClick = { showCreateTaskDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Create task")
                }

                OpenTaskerScreen.Setup,
                OpenTaskerScreen.RunLog -> Unit
            }
        },
        bottomBar = {
            NavigationBar {
                OpenTaskerScreen.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = screen == destination,
                        onClick = { screen = destination },
                        icon = {
                            val icon = when (destination) {
                                OpenTaskerScreen.Profiles -> Icons.Filled.CheckCircle
                                OpenTaskerScreen.Tasks -> Icons.Filled.Edit
                                OpenTaskerScreen.Setup -> Icons.Filled.Settings
                                OpenTaskerScreen.RunLog -> Icons.Filled.Info
                            }
                            Icon(icon, contentDescription = null)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (screen) {
            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = profiles,
                tasks = tasks,
                onCreateTaskFirst = {
                    screen = OpenTaskerScreen.Tasks
                    showCreateTaskDialog = true
                },
                onCreateProfile = { showCreateProfileDialog = true },
                onBrowseTemplates = { showTemplateDialog = true },
                onEditProfile = { profileDialog = it },
                onDeleteProfile = viewModel::deleteProfile,
                onToggleProfile = { profile, enabled ->
                    viewModel.updateProfile(profile.copy(enabled = enabled), "Profile ${if (enabled) "enabled" else "disabled"}")
                },
                onAddContext = { contextPickerProfile = it },
                onEditContext = { profile, index, context ->
                    contextEdit = ContextEditState(profile, context.type, index, context)
                },
                onDeleteContext = { profile, index ->
                    viewModel.updateProfile(
                        profile.copy(contexts = profile.contexts.filterIndexed { i, _ -> i != index }),
                        "Context removed",
                    )
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = tasks,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { taskDialog = it },
                onDeleteTask = viewModel::deleteTask,
                onAddAction = { actionPickerTask = it },
                onEditAction = { task, index, action ->
                    ActionMetadataRegistry.get(action.type)?.let { metadata ->
                        actionEdit = ActionEditState(task, metadata, index, action)
                    }
                },
                onDeleteAction = { task, index ->
                    viewModel.updateTask(
                        task.copy(actions = task.actions.filterIndexed { i, _ -> i != index }),
                        "Action removed",
                    )
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Setup -> PermissionOnboardingScreen(
                contentPadding = innerPadding,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            )

            OpenTaskerScreen.RunLog -> RunLogScreenContent(runLogs, innerPadding)
        }
    }

    if (showCreateTaskDialog) {
        TaskEditorDialog(
            task = null,
            onDismiss = { showCreateTaskDialog = false },
            onSave = { name, priority ->
                viewModel.createTask(name, priority)
                showCreateTaskDialog = false
            },
        )
    }

    taskDialog?.let { task ->
        TaskEditorDialog(
            task = task,
            onDismiss = { taskDialog = null },
            onSave = { name, priority ->
                viewModel.updateTask(task.copy(name = name.trim(), priority = priority.coerceIn(0, 10)))
                taskDialog = null
            },
        )
    }

    if (showCreateProfileDialog) {
        ProfileEditorDialog(
            profile = null,
            tasks = tasks,
            onDismiss = { showCreateProfileDialog = false },
            onSave = { name, enabled, enterTaskId, cooldown, automationMode ->
                viewModel.createProfile(name, enabled, enterTaskId, cooldown, automationMode)
                showCreateProfileDialog = false
            },
        )
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            onDismiss = { showTemplateDialog = false },
            onSelect = { template ->
                showTemplateDialog = false
                selectedTemplate = template
            },
        )
    }

    selectedTemplate?.let { template ->
        TemplateSlotDialog(
            template = template,
            onDismiss = { selectedTemplate = null },
            onInstall = { values ->
                viewModel.installProfileTemplate(template, values)
                selectedTemplate = null
                screen = OpenTaskerScreen.Profiles
            },
        )
    }

    profileDialog?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            tasks = tasks,
            onDismiss = { profileDialog = null },
            onSave = { name, enabled, enterTaskId, cooldown, automationMode ->
                viewModel.updateProfile(
                    profile.copy(
                        name = name.trim(),
                        enabled = enabled,
                        enterTaskId = enterTaskId,
                        cooldownSec = cooldown.coerceAtLeast(0),
                        automationMode = automationMode,
                    )
                )
                profileDialog = null
            },
        )
    }

    actionPickerTask?.let { task ->
        ActionPickerDialog(
            onDismiss = { actionPickerTask = null },
            onSelect = { metadata ->
                actionPickerTask = null
                actionEdit = ActionEditState(task, metadata)
            },
        )
    }

    actionEdit?.let { state ->
        ActionConfigDialog(
            state = state,
            onDismiss = { actionEdit = null },
            onSave = { action ->
                val updatedActions = state.index?.let { index ->
                    state.task.actions.mapIndexed { i, existing -> if (i == index) action else existing }
                } ?: (state.task.actions + action)
                viewModel.updateTask(state.task.copy(actions = updatedActions), if (state.index == null) "Action added" else "Action updated")
                actionEdit = null
            },
        )
    }

    contextPickerProfile?.let { profile ->
        ContextTypePickerDialog(
            onDismiss = { contextPickerProfile = null },
            onSelect = { type ->
                contextPickerProfile = null
                contextEdit = ContextEditState(profile, type)
            },
        )
    }

    contextEdit?.let { state ->
        ContextConfigDialog(
            state = state,
            onDismiss = { contextEdit = null },
            onSave = { context ->
                val updatedContexts = state.index?.let { index ->
                    state.profile.contexts.mapIndexed { i, existing -> if (i == index) context else existing }
                } ?: (state.profile.contexts + context)
                viewModel.updateProfile(
                    state.profile.copy(contexts = updatedContexts),
                    if (state.index == null) "Context added" else "Context updated",
                )
                contextEdit = null
            },
        )
    }
}

@Composable
private fun ProfilesScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    onCreateTaskFirst: () -> Unit,
    onCreateProfile: () -> Unit,
    onBrowseTemplates: () -> Unit,
    onEditProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onToggleProfile: (Profile, Boolean) -> Unit,
    onAddContext: (Profile) -> Unit,
    onEditContext: (Profile, Int, ContextSpec) -> Unit,
    onDeleteContext: (Profile, Int) -> Unit,
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = "Start with a template or task",
            body = "Templates create a disabled profile plus its starter task in one step. Use a blank task if you want to build everything manually.",
            actionLabel = "Browse Templates",
            onAction = onBrowseTemplates,
            secondaryActionLabel = "Create Blank Task",
            onSecondaryAction = onCreateTaskFirst,
            contentPadding = contentPadding,
        )
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            title = "No profiles yet",
            body = "Profiles connect contexts to tasks. Start from a curated template or create a blank profile and attach contexts yourself.",
            actionLabel = "Browse Templates",
            onAction = onBrowseTemplates,
            secondaryActionLabel = "Create Blank Profile",
            onSecondaryAction = onCreateProfile,
            contentPadding = contentPadding,
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TemplatePromptCard(onBrowseTemplates)
        }
        items(profiles, key = { it.id }) { profile ->
            val enterTaskName = tasks.firstOrNull { it.id == profile.enterTaskId }?.name ?: "Missing task #${profile.enterTaskId}"
            ProfileCard(
                profile = profile,
                enterTaskName = enterTaskName,
                onEdit = { onEditProfile(profile) },
                onDelete = { onDeleteProfile(profile) },
                onToggle = { onToggleProfile(profile, it) },
                onAddContext = { onAddContext(profile) },
                onEditContext = { index, context -> onEditContext(profile, index, context) },
                onDeleteContext = { index -> onDeleteContext(profile, index) },
            )
        }
    }
}

@Composable
private fun TemplatePromptCard(onBrowseTemplates: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Templates", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Create safe starter profiles with slot-based names, contexts, and task actions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onBrowseTemplates) {
                Text("Browse")
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    enterTaskName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAddContext: () -> Unit,
    onEditContext: (Int, ContextSpec) -> Unit,
    onDeleteContext: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (profile.enabled) 0.72f else 0.42f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Runs: $enterTaskName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = profile.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("${profile.contexts.size} context${plural(profile.contexts.size)}") })
                AssistChip(onClick = {}, label = { Text("${profile.cooldownSec}s cooldown") })
                AssistChip(onClick = {}, label = { Text(profile.automationMode.name.lowercase()) })
            }
            if (profile.contexts.isEmpty()) {
                Text(
                    "No contexts yet. This profile will not match until at least one context is added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                profile.contexts.forEachIndexed { index, context ->
                    ContextRow(
                        context = context,
                        onEdit = { onEditContext(index, context) },
                        onDelete = { onDeleteContext(index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onAddContext) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Context")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun TasksScreen(
    tasks: List<Task>,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = "No tasks yet",
            body = "Tasks are ordered action lists. Create a task, then add actions from the metadata registry.",
            actionLabel = "Create Task",
            onAction = onCreateTask,
            contentPadding = contentPadding,
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onEdit = { onEditTask(task) },
                onDelete = { onDeleteTask(task) },
                onAddAction = { onAddAction(task) },
                onEditAction = { index, action -> onEditAction(task, index, action) },
                onDeleteAction = { index -> onDeleteAction(task, index) },
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${task.actions.size} action${plural(task.actions.size)} - priority ${task.priority} - ${task.collisionMode.name.lowercase()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (task.actions.isEmpty()) {
                Text(
                    "No actions yet. Add at least one action before attaching this task to an enabled profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                task.actions.forEachIndexed { index, action ->
                    ActionRow(
                        index = index,
                        action = action,
                        onEdit = { onEditAction(index, action) },
                        onDelete = { onDeleteAction(index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onAddAction) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Action")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    index: Int,
    action: ActionSpec,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("${index + 1}. ${action.label ?: metadata?.name ?: action.type}", style = MaterialTheme.typography.titleSmall)
                Text(
                    action.args.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { metadata?.description ?: "No arguments" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capability.level != CapabilityLevel.Supported) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (capability.level == CapabilityLevel.Unsupported) "Unsupported" else "Needs setup") },
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit action")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete action")
            }
        }
    }
}

@Composable
private fun ContextRow(
    context: ContextSpec,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(context.type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                Text(
                    context.config.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "No configuration" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (context.invert) {
                AssistChip(onClick = {}, label = { Text("Inverted") })
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit context")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete context")
            }
        }
    }
}

@Composable
private fun RunLogScreenContent(logs: List<RunLogEntry>, contentPadding: PaddingValues) {
    if (logs.isEmpty()) {
        EmptyState(
            title = "No execution history yet",
            body = "Run log entries appear here when enabled profiles execute tasks.",
            actionLabel = null,
            onAction = null,
            contentPadding = contentPadding,
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(logs, key = { it.id }) { entry ->
            RunLogCard(entry)
        }
    }
}

@Composable
private fun RunLogCard(entry: RunLogEntry) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (entry.success) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = null,
                tint = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(Modifier.weight(1f)) {
                Text(entry.taskName, style = MaterialTheme.typography.titleMedium)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.message.isNotBlank()) {
                    Text(entry.message, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("${entry.durationMs} ms", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    contentPadding: PaddingValues,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSecondaryAction) {
                Text(secondaryActionLabel)
            }
        }
    }
}

@Composable
private fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ProfileTemplate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profile Templates") },
        text = {
            LazyColumn(
                modifier = Modifier.height(460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ProfileTemplateCatalog.all, key = { it.id }) { template ->
                    val status = when (template.availability) {
                        TemplateAvailability.Ready -> "Ready"
                        TemplateAvailability.RequiresSetup -> "Needs setup"
                        TemplateAvailability.Planned -> "Planned"
                    }
                    Card(
                        onClick = { onSelect(template) },
                        enabled = template.installable,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(template.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                AssistChip(onClick = {}, label = { Text(status) })
                            }
                            Text(template.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(template.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(template.safetyNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TemplateSlotDialog(
    template: ProfileTemplate,
    onDismiss: () -> Unit,
    onInstall: (Map<String, String>) -> Unit,
) {
    var values by remember(template.id) { mutableStateOf(template.defaults()) }
    val missingRequired = template.slots.any { it.required && values[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.title) },
        text = {
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(template.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "Profiles created from templates start disabled so you can review permissions, actions, and contexts before enabling.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(template.slots, key = { it.key }) { slot ->
                    OutlinedTextField(
                        value = values[slot.key].orEmpty(),
                        onValueChange = { values = values + (slot.key to it) },
                        label = { Text(slot.label + if (slot.required) " *" else "") },
                        placeholder = slot.hint?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && template.installable,
                onClick = { onInstall(values) },
            ) {
                Text("Create Disabled Profile")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TaskEditorDialog(
    task: Task?,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var name by remember(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var priority by remember(task?.id) { mutableStateOf((task?.priority ?: 5).toString()) }
    val canSave = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Create Task" else "Edit Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = priority, onValueChange = { priority = it.filter(Char::isDigit) }, label = { Text("Priority 0-10") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, priority.toIntOrNull() ?: 5) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileEditorDialog(
    profile: Profile?,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Long, Int, AutomationMode) -> Unit,
) {
    val initialTaskId = profile?.enterTaskId ?: tasks.firstOrNull()?.id ?: 0L
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var enabled by remember(profile?.id) { mutableStateOf(profile?.enabled ?: true) }
    var enterTaskId by remember(profile?.id, tasks) { mutableStateOf(initialTaskId) }
    var cooldown by remember(profile?.id) { mutableStateOf((profile?.cooldownSec ?: 0).toString()) }
    var automationMode by remember(profile?.id) { mutableStateOf(profile?.automationMode ?: AutomationMode.SINGLE) }
    val canSave = name.isNotBlank() && enterTaskId > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Create Profile" else "Edit Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Text("Enter task", style = MaterialTheme.typography.labelLarge)
                tasks.forEach { task ->
                    OutlinedButton(
                        onClick = { enterTaskId = task.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (task.id == enterTaskId) "${task.name} selected" else task.name)
                    }
                }
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit) },
                    label = { Text("Cooldown seconds") },
                    singleLine = true,
                )
                Text("Re-trigger behavior", style = MaterialTheme.typography.labelLarge)
                AutomationMode.entries.forEach { mode ->
                    OutlinedButton(
                        onClick = { automationMode = mode },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                        Text(if (mode == automationMode) "$label selected" else "$label - ${automationModeDescription(mode)}")
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, enabled, enterTaskId, cooldown.toIntOrNull() ?: 0, automationMode) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    val actions = remember { ActionMetadataRegistry.all().sortedWith(compareBy({ it.category }, { it.name })) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Action") },
        text = {
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(actions, key = { it.id }) { metadata ->
                    val capability = ActionCapabilityRegistry.get(metadata.id)
                    Card(
                        onClick = { onSelect(metadata) },
                        enabled = capability.canAdd,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(metadata.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                if (capability.level != CapabilityLevel.Supported) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(if (capability.level == CapabilityLevel.Unsupported) "Unsupported" else "Setup") },
                                    )
                                }
                            }
                            Text(metadata.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(metadata.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (capability.level != CapabilityLevel.Supported) {
                                Text(capability.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ActionConfigDialog(
    state: ActionEditState,
    onDismiss: () -> Unit,
    onSave: (ActionSpec) -> Unit,
) {
    var label by remember(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.label ?: state.metadata.name)
    }
    var values by remember(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.metadata.fields.associate { field -> field.key to state.existing?.args?.get(field.key).orEmpty() })
    }
    val capability = remember(state.metadata.id) { ActionCapabilityRegistry.get(state.metadata.id) }
    val missingRequired = state.metadata.fields.any { it.required && values[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.metadata.name) },
        text = {
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(state.metadata.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (capability.level != CapabilityLevel.Supported) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = if (capability.level == CapabilityLevel.Unsupported) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(
                                capability.reason,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, singleLine = true)
                }
                items(state.metadata.fields, key = { it.key }) { field ->
                    ActionFieldInput(
                        field = field,
                        value = values[field.key].orEmpty(),
                        onChange = { newValue -> values = values + (field.key to newValue) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && capability.canAdd,
                onClick = {
                    onSave(
                        ActionSpec(
                            id = state.existing?.id ?: 0,
                            type = state.metadata.id,
                            label = label.trim().ifBlank { state.metadata.name },
                            args = values.filterValues { it.isNotBlank() },
                            continueOnError = state.existing?.continueOnError ?: false,
                            condition = state.existing?.condition,
                        )
                    )
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ActionFieldInput(field: ActionField, value: String, onChange: (String) -> Unit) {
    val label = field.label + if (field.required) " *" else ""
    when (field.fieldType) {
        FieldType.CHECKBOX -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = value.toBoolean(), onCheckedChange = { onChange(it.toString()) })
        }

        FieldType.MULTILINE -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '-' || ch == '.' }) },
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.DROPDOWN,
        FieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            singleLine = field.fieldType != FieldType.MULTILINE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ContextTypePickerDialog(onDismiss: () -> Unit, onSelect: (ContextType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Context") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextType.entries.forEach { type ->
                    Card(onClick = { onSelect(type) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                            Text(contextDescription(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ContextConfigDialog(
    state: ContextEditState,
    onDismiss: () -> Unit,
    onSave: (ContextSpec) -> Unit,
) {
    var invert by remember(state.existing, state.type) { mutableStateOf(state.existing?.invert ?: false) }
    var config by remember(state.existing, state.type) {
        mutableStateOf(defaultContextConfig(state.type) + (state.existing?.config ?: emptyMap()))
    }
    val fields = contextFields(state.type)
    val missingRequired = fields.any { it.required && config[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.type.name.lowercase().replaceFirstChar { it.uppercase() }) },
        text = {
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(contextDescription(state.type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Invert match", modifier = Modifier.weight(1f))
                        Switch(checked = invert, onCheckedChange = { invert = it })
                    }
                    HorizontalDivider()
                }
                items(fields, key = { it.key }) { field ->
                    ActionFieldInput(
                        field = field,
                        value = config[field.key].orEmpty(),
                        onChange = { value -> config = config + (field.key to value) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired,
                onClick = { onSave(ContextSpec(state.type, config.filterValues { it.isNotBlank() }, invert)) },
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun contextFields(type: ContextType): List<ActionField> = when (type) {
    ContextType.APPLICATION -> listOf(ActionField("package", "Package name", required = true, hint = "com.example.app"))
    ContextType.TIME -> listOf(
        ActionField("start", "Start HH:mm", required = true, hint = "09:00"),
        ActionField("end", "End HH:mm", required = true, hint = "17:00"),
    )
    ContextType.DAY -> listOf(ActionField("days", "Days", required = true, hint = "MON,TUE,WED,THU,FRI"))
    ContextType.LOCATION -> listOf(
        ActionField("latitude", "Latitude", FieldType.NUMBER, required = true),
        ActionField("longitude", "Longitude", FieldType.NUMBER, required = true),
        ActionField("radiusMeters", "Radius meters", FieldType.NUMBER, required = true, hint = "100"),
    )
    ContextType.STATE -> listOf(
        ActionField("key", "State key", required = true, hint = "battery, wifi, charging, screen"),
        ActionField("value", "Expected value", required = true, hint = "true, home-ssid, 80"),
    )
    ContextType.EVENT -> listOf(
        ActionField("event", "Event type", required = true, hint = "boot, sms, intent"),
        ActionField("filter", "Filter", hint = "Optional text/package/action filter"),
    )
}

private fun defaultContextConfig(type: ContextType): Map<String, String> = when (type) {
    ContextType.TIME -> mapOf("start" to "09:00", "end" to "17:00")
    ContextType.DAY -> mapOf("days" to "MON,TUE,WED,THU,FRI")
    ContextType.LOCATION -> mapOf("radiusMeters" to "100")
    else -> emptyMap()
}

private fun automationModeDescription(mode: AutomationMode): String = when (mode) {
    AutomationMode.SINGLE -> "ignore while running"
    AutomationMode.RESTART -> "cancel and restart"
    AutomationMode.QUEUED -> "run again in order"
    AutomationMode.PARALLEL -> "allow overlap"
}

private fun contextDescription(type: ContextType): String = when (type) {
    ContextType.APPLICATION -> "Matches when an app is detected in the foreground."
    ContextType.TIME -> "Matches during a clock time window."
    ContextType.DAY -> "Matches on selected days of the week."
    ContextType.LOCATION -> "Matches near a latitude/longitude radius."
    ContextType.STATE -> "Matches a device state such as battery, charging, screen, or WiFi."
    ContextType.EVENT -> "Matches a one-shot event such as boot, SMS, or intent."
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
