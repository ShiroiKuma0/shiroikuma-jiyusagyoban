package com.opentasker.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.opentasker.app.BuildConfig
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.contexts.CalendarSunEventPresets
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.EventContextPreset
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.RunLogActionDiagnostic
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.outcome
import com.opentasker.core.engine.toRunLogDiagnostics
import com.opentasker.core.flow.AutomationFlowTarget
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.core.templates.TemplateAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TASKER_XML_IMPORT_MAX_BYTES = 4 * 1024 * 1024
private val TASKER_XML_MIME_TYPES = arrayOf("application/xml", "text/xml", "text/*", "*/*")

private enum class OpenTaskerScreen(val label: String) {
    Profiles("Profiles"),
    Tasks("Tasks"),
    Flow("Flow"),
    Scenes("Scenes"),
    Inspector("Inspect"),
    Setup("Setup"),
    RunLog("Log"),
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

internal data class TaskerImportReviewState(
    val report: TaskerXmlImportReport,
    val preview: TaskerImportPreview,
)

private sealed interface DeleteTarget {
    val title: String
    val body: String
    val confirmLabel: String

    data class ProfileTarget(val profile: Profile) : DeleteTarget {
        override val title = "Delete profile?"
        override val body = "This removes \"${profile.name}\" and its contexts. The linked task remains available."
        override val confirmLabel = "Delete Profile"
    }

    data class TaskTarget(val task: Task) : DeleteTarget {
        override val title = "Delete task?"
        override val body = "This permanently removes \"${task.name}\" and its ${task.actions.size} action${plural(task.actions.size)}."
        override val confirmLabel = "Delete Task"
    }

    data class SceneTarget(val scene: Scene) : DeleteTarget {
        override val title = "Delete scene?"
        override val body = "This permanently removes \"${scene.name}\" and its ${scene.elements.size} element${plural(scene.elements.size)}."
        override val confirmLabel = "Delete Scene"
    }

    data class ActionTarget(val task: Task, val index: Int, val action: ActionSpec) : DeleteTarget {
        override val title = "Remove action?"
        override val body = "This removes step ${index + 1} from \"${task.name}\". Remaining actions keep their order."
        override val confirmLabel = "Remove Action"
    }

    data class ContextTarget(val profile: Profile, val index: Int, val context: ContextSpec) : DeleteTarget {
        override val title = "Remove context?"
        override val body = "This removes the ${context.type.name.lowercase()} condition from \"${profile.name}\"."
        override val confirmLabel = "Remove Context"
    }
}

class ActiveAutomationViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {
    private val locationDwellStateStore = LocationDwellStateStore(appContext)
    private val bundleRepository = OpenTaskerBundleRepository(db)

    val profiles: StateFlow<List<Profile>> = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<Task>> = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scenes: StateFlow<List<Scene>> = db.sceneDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runLogs: StateFlow<List<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    internal var taskerImportReview by mutableStateOf<TaskerImportReviewState?>(null)
        private set

    var taskerImportBusy by mutableStateOf(false)
        private set

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

    fun createScene(name: String, widthDp: Int, heightDp: Int) = launchWithMessage("Scene created") {
        db.sceneDao().insert(
            Scene(
                name = name.trim(),
                widthDp = widthDp.coerceIn(120, 1440),
                heightDp = heightDp.coerceIn(80, 2560),
            ).toEntity()
        )
    }

    fun updateScene(scene: Scene, message: String = "Scene updated") = launchWithMessage(message) {
        db.sceneDao().update(scene.toEntity())
    }

    fun deleteScene(scene: Scene) = launchWithMessage("Scene deleted") {
        db.sceneDao().delete(scene.toEntity())
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
            val previous = profile.id.takeIf { it > 0L }
                ?.let { db.profileDao().getById(it)?.toDomain() }
            if (previous != null && previous.contexts != profile.contexts) {
                locationDwellStateStore.clearProfile(profile.id)
            }
            db.profileDao().update(profile.toEntity())
        }

    fun deleteProfile(profile: Profile) = launchWithMessage("Profile deleted") {
        db.profileDao().delete(profile.toEntity())
        locationDwellStateStore.clearProfile(profile.id)
    }

    fun installProfileTemplate(template: ProfileTemplate, slotValues: Map<String, String>) =
        launchWithMessage("Template installed as a disabled profile") {
            val applied = template.instantiate(slotValues)
            db.withTransaction {
                val taskId = db.taskDao().insert(applied.task.toEntity())
                db.profileDao().insert(applied.profile.copy(enterTaskId = taskId).toEntity())
            }
        }

    fun previewTaskerXml(uri: Uri, appVersion: String) {
        viewModelScope.launch {
            if (taskerImportBusy) return@launch
            taskerImportBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val rawXml = readBoundedTaskerXml(appContext, uri)
                    val report = TaskerXmlImporter.parse(rawXml = rawXml, appVersion = appVersion)
                    TaskerImportReviewState(report = report, preview = TaskerImportPlanner.preview(report))
                }
            }
                .onSuccess {
                    taskerImportReview = it
                    events.send("Tasker XML ready for review")
                }
                .onFailure { events.send("Error: ${it.message ?: "Tasker XML import preview failed"}") }
            taskerImportBusy = false
        }
    }

    fun clearTaskerImportReview() {
        if (!taskerImportBusy) {
            taskerImportReview = null
        }
    }

    fun confirmTaskerImport(report: TaskerXmlImportReport) {
        viewModelScope.launch {
            if (taskerImportBusy) return@launch
            taskerImportBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bundleRepository.importBundle(TaskerImportPlanner.confirmedBundle(report))
                }
            }
                .onSuccess { importReport ->
                    taskerImportReview = null
                    events.send(
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}"
                    )
                }
                .onFailure { events.send("Error: ${it.message ?: "Tasker XML import failed"}") }
            taskerImportBusy = false
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

class ActiveAutomationViewModelFactory(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ActiveAutomationViewModel::class.java)) {
            return ActiveAutomationViewModel(db, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ActiveAutomationUi(
    db: AppDatabase,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: ActiveAutomationViewModel = viewModel(factory = ActiveAutomationViewModelFactory(db, context))
    val profiles by viewModel.profiles.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val scenes by viewModel.scenes.collectAsState()
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
    var pendingDelete by remember { mutableStateOf<DeleteTarget?>(null) }
    val taskerImportReview = viewModel.taskerImportReview
    val taskerImportBusy = viewModel.taskerImportBusy
    val taskerXmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.previewTaskerXml(it, BuildConfig.VERSION_NAME) }
    }
    val openFlowTarget: (AutomationFlowTarget) -> Unit = { target ->
        var opened = true
        when (target) {
            is AutomationFlowTarget.Profile -> {
                profiles.firstOrNull { it.id == target.profileId }?.let { profile ->
                    screen = OpenTaskerScreen.Profiles
                    profileDialog = profile
                } ?: run { opened = false }
            }

            is AutomationFlowTarget.Context -> {
                val profile = profiles.firstOrNull { it.id == target.profileId }
                val contextSpec = profile?.contexts?.getOrNull(target.index)
                if (profile != null && contextSpec != null) {
                    screen = OpenTaskerScreen.Profiles
                    contextEdit = ContextEditState(profile, contextSpec.type, target.index, contextSpec)
                } else {
                    opened = false
                }
            }

            is AutomationFlowTarget.Task -> {
                tasks.firstOrNull { it.id == target.taskId }?.let { task ->
                    screen = OpenTaskerScreen.Tasks
                    taskDialog = task
                } ?: run { opened = false }
            }

            is AutomationFlowTarget.Action -> {
                val task = tasks.firstOrNull { it.id == target.taskId }
                val action = task?.actions?.getOrNull(target.index)
                val metadata = action?.let { ActionMetadataRegistry.get(it.type) }
                if (task != null && action != null && metadata != null) {
                    screen = OpenTaskerScreen.Tasks
                    actionEdit = ActionEditState(task, metadata, target.index, action)
                } else {
                    opened = false
                }
            }
        }
        if (!opened) {
            scope.launch { snackbarHostState.showSnackbar("Flow target no longer exists") }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    val headerDetail = when (screen) {
        OpenTaskerScreen.Profiles -> "${profiles.count { it.enabled }} enabled - ${profiles.size} total"
        OpenTaskerScreen.Tasks -> "${tasks.sumOf { it.actions.size }} actions - ${tasks.size} tasks"
        OpenTaskerScreen.Flow -> "${profiles.size} profiles - ${tasks.size} tasks"
        OpenTaskerScreen.Scenes -> "${scenes.sumOf { it.elements.size }} elements - ${scenes.size} scenes"
        OpenTaskerScreen.Inspector -> "Live context health"
        OpenTaskerScreen.Setup -> "Permission and reliability checks"
        OpenTaskerScreen.RunLog -> "${runLogs.size} recent entries"
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
                            "${screen.label} - $headerDetail",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                    Icon(Icons.Filled.Add, contentDescription = if (tasks.isEmpty()) "Create task first" else "Create profile")
                }

                OpenTaskerScreen.Tasks -> FloatingActionButton(onClick = { showCreateTaskDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Create task")
                }

                OpenTaskerScreen.Flow,
                OpenTaskerScreen.Scenes,
                OpenTaskerScreen.Inspector,
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
                                OpenTaskerScreen.Flow -> Icons.Filled.Info
                                OpenTaskerScreen.Scenes -> Icons.Filled.Edit
                                OpenTaskerScreen.Inspector -> Icons.Filled.Info
                                OpenTaskerScreen.Setup -> Icons.Filled.Settings
                                OpenTaskerScreen.RunLog -> Icons.Filled.Info
                            }
                            Icon(icon, contentDescription = null)
                        },
                        label = { Text(destination.label) },
                        alwaysShowLabel = true,
                    )
                }
            }
        },
    ) { innerPadding ->
        when (screen) {
            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = profiles,
                tasks = tasks,
                runLogs = runLogs,
                onCreateTaskFirst = {
                    screen = OpenTaskerScreen.Tasks
                    showCreateTaskDialog = true
                },
                onCreateProfile = { showCreateProfileDialog = true },
                onBrowseTemplates = { showTemplateDialog = true },
                onImportTaskerXml = { taskerXmlLauncher.launch(TASKER_XML_MIME_TYPES) },
                taskerImportBusy = taskerImportBusy,
                onEditProfile = { profileDialog = it },
                onDeleteProfile = { pendingDelete = DeleteTarget.ProfileTarget(it) },
                onToggleProfile = { profile, enabled ->
                    viewModel.updateProfile(profile.copy(enabled = enabled), "Profile ${if (enabled) "enabled" else "disabled"}")
                },
                onAddContext = { contextPickerProfile = it },
                onEditContext = { profile, index, context ->
                    contextEdit = ContextEditState(profile, context.type, index, context)
                },
                onDeleteContext = { profile, index ->
                    profile.contexts.getOrNull(index)?.let { context ->
                        pendingDelete = DeleteTarget.ContextTarget(profile, index, context)
                    }
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = tasks,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { taskDialog = it },
                onDeleteTask = { pendingDelete = DeleteTarget.TaskTarget(it) },
                onAddAction = { actionPickerTask = it },
                onEditAction = { task, index, action ->
                    ActionMetadataRegistry.get(action.type)?.let { metadata ->
                        actionEdit = ActionEditState(task, metadata, index, action)
                    }
                },
                onDeleteAction = { task, index ->
                    task.actions.getOrNull(index)?.let { action ->
                        pendingDelete = DeleteTarget.ActionTarget(task, index, action)
                    }
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Flow -> AutomationFlowScreen(
                profiles = profiles,
                tasks = tasks,
                contentPadding = innerPadding,
                onNodeTargetSelected = openFlowTarget,
                onAddContext = { profileId ->
                    val profile = profiles.firstOrNull { it.id == profileId }
                    if (profile != null) {
                        screen = OpenTaskerScreen.Profiles
                        contextPickerProfile = profile
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Flow target no longer exists") }
                    }
                },
                onAddAction = { taskId ->
                    val task = tasks.firstOrNull { it.id == taskId }
                    if (task != null) {
                        screen = OpenTaskerScreen.Tasks
                        actionPickerTask = task
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Flow target no longer exists") }
                    }
                },
            )

            OpenTaskerScreen.Scenes -> SceneLibraryScreen(
                scenes = scenes,
                tasks = tasks,
                onCreateScene = viewModel::createScene,
                onUpdateScene = viewModel::updateScene,
                onDeleteScene = { pendingDelete = DeleteTarget.SceneTarget(it) },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Setup -> PermissionOnboardingScreen(
                contentPadding = innerPadding,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            )

            OpenTaskerScreen.Inspector -> ContextInspectorScreen(db = db, contentPadding = innerPadding)

            OpenTaskerScreen.RunLog -> RunLogScreenContent(runLogs, tasks, innerPadding)
        }
    }

    pendingDelete?.let { target ->
        DeleteConfirmationDialog(
            target = target,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                when (target) {
                    is DeleteTarget.ProfileTarget -> viewModel.deleteProfile(target.profile)
                    is DeleteTarget.TaskTarget -> viewModel.deleteTask(target.task)
                    is DeleteTarget.SceneTarget -> viewModel.deleteScene(target.scene)
                    is DeleteTarget.ActionTarget -> viewModel.updateTask(
                        target.task.copy(actions = target.task.actions.filterIndexed { i, _ -> i != target.index }),
                        "Action removed",
                    )
                    is DeleteTarget.ContextTarget -> viewModel.updateProfile(
                        target.profile.copy(contexts = target.profile.contexts.filterIndexed { i, _ -> i != target.index }),
                        "Context removed",
                    )
                }
                pendingDelete = null
            },
        )
    }

    taskerImportReview?.let { state ->
        TaskerImportReviewDialog(
            state = state,
            busy = taskerImportBusy,
            onDismiss = viewModel::clearTaskerImportReview,
            onConfirm = { viewModel.confirmTaskerImport(state.report) },
        )
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
    runLogs: List<RunLogEntry>,
    onCreateTaskFirst: () -> Unit,
    onCreateProfile: () -> Unit,
    onBrowseTemplates: () -> Unit,
    onImportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
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
            body = "Import an existing Tasker XML export, start from a guided template, or create a blank task if you want to build everything manually.",
            actionLabel = if (taskerImportBusy) "Reading Tasker XML..." else "Import Tasker XML",
            onAction = onImportTaskerXml,
            actionEnabled = !taskerImportBusy,
            secondaryActionLabel = "Browse Templates",
            onSecondaryAction = onBrowseTemplates,
            tertiaryActionLabel = "Create Blank Task",
            onTertiaryAction = onCreateTaskFirst,
            contentPadding = contentPadding,
        )
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            title = "No profiles yet",
            body = "Profiles connect contexts to tasks. Import Tasker XML, start from a curated template, or create a blank profile and attach contexts yourself.",
            actionLabel = if (taskerImportBusy) "Reading Tasker XML..." else "Import Tasker XML",
            onAction = onImportTaskerXml,
            actionEnabled = !taskerImportBusy,
            secondaryActionLabel = "Browse Templates",
            onSecondaryAction = onBrowseTemplates,
            tertiaryActionLabel = "Create Blank Profile",
            onTertiaryAction = onCreateProfile,
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
            WorkspaceSummaryCard(
                profiles = profiles,
                tasks = tasks,
                runLogs = runLogs,
                onBrowseTemplates = onBrowseTemplates,
                onImportTaskerXml = onImportTaskerXml,
                taskerImportBusy = taskerImportBusy,
            )
        }
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

private fun readBoundedTaskerXml(context: Context, uri: Uri): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: error("Unable to open selected Tasker XML file")
    ByteArrayOutputStream().use { output ->
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                require(totalBytes <= TASKER_XML_IMPORT_MAX_BYTES) {
                    "Tasker XML file is larger than ${TASKER_XML_IMPORT_MAX_BYTES / (1024 * 1024)} MB"
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

@Composable
private fun WorkspaceSummaryCard(
    profiles: List<Profile>,
    tasks: List<Task>,
    runLogs: List<RunLogEntry>,
    onBrowseTemplates: () -> Unit,
    onImportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
) {
    val enabledProfiles = profiles.count { it.enabled }
    val configuredContexts = profiles.sumOf { it.contexts.size }
    val totalActions = tasks.sumOf { it.actions.size }
    val recentFailure = runLogs.firstOrNull { !it.success }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Automation workspace", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Review readiness before enabling profiles. Templates stay disabled until you approve them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    label = if (enabledProfiles > 0) "$enabledProfiles live" else "Paused",
                    color = if (enabledProfiles > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryMetric("${profiles.size}", "Profiles", Modifier.weight(1f))
                SummaryMetric("$configuredContexts", "Contexts", Modifier.weight(1f))
                SummaryMetric("$totalActions", "Actions", Modifier.weight(1f))
            }
            if (recentFailure != null) {
                InlineNotice(
                    title = "Recent failure",
                    body = "${recentFailure.taskName}: ${recentFailure.message.ifBlank { "Review the run log for details." }}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBrowseTemplates, modifier = Modifier.weight(1f)) {
                    Text("Templates")
                }
                OutlinedButton(
                    onClick = onImportTaskerXml,
                    enabled = !taskerImportBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (taskerImportBusy) "Reading XML" else "Import Tasker")
                }
            }
        }
    }
}

@Composable
private fun TaskLibrarySummaryCard(tasks: List<Task>, onCreateTask: () -> Unit) {
    val totalActions = tasks.sumOf { it.actions.size }
    val emptyTasks = tasks.count { it.actions.isEmpty() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Task library", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Build reusable action sequences, then attach them to profiles when the order and permissions are ready.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onCreateTask) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Task")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryMetric("${tasks.size}", "Tasks", Modifier.weight(1f))
                SummaryMetric("$totalActions", "Actions", Modifier.weight(1f))
                SummaryMetric("$emptyTasks", "Need actions", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun InlineNotice(title: String, body: String, color: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (color == MaterialTheme.colorScheme.error) Icons.Filled.Error else Icons.Filled.Info,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TemplatePromptCard(onBrowseTemplates: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
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
                    "Create starter profiles with named slots, clear safety notes, and disabled-by-default review.",
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
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (profile.enabled) 0.72f else 0.46f),
        ),
        border = BorderStroke(
            1.dp,
            if (profile.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
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
                StatusPill(
                    label = if (profile.enabled) "Enabled" else "Paused",
                    color = if (profile.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusPill("${profile.contexts.size} context${plural(profile.contexts.size)}", MaterialTheme.colorScheme.primary)
                StatusPill("${profile.cooldownSec}s cooldown", MaterialTheme.colorScheme.secondary)
            }
            StatusPill(profile.automationMode.name.lowercase(), MaterialTheme.colorScheme.onSurfaceVariant)
            if (profile.contexts.isEmpty()) {
                InlineNotice(
                    title = "Profile cannot match yet",
                    body = "Add at least one context before relying on this profile.",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onAddContext, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Context")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete Profile")
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
        item {
            TaskLibrarySummaryCard(tasks = tasks, onCreateTask = onCreateTask)
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Priority ${task.priority} - ${task.collisionMode.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("${task.actions.size} action${plural(task.actions.size)}", MaterialTheme.colorScheme.primary)
                    StatusPill("Priority ${task.priority}", MaterialTheme.colorScheme.secondary)
                }
                StatusPill(task.collisionMode.name.lowercase().replace('_', ' '), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.actions.isEmpty()) {
                InlineNotice(
                    title = "Task has no actions",
                    body = "Add at least one action before attaching this task to an enabled profile.",
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onAddAction, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Action")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete Task")
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill("#${index + 1}", MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(action.label ?: metadata?.name ?: action.type, style = MaterialTheme.typography.titleSmall)
                Text(
                    action.args.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { metadata?.description ?: "No arguments" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capability.level != CapabilityLevel.Supported) {
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        if (capability.level == CapabilityLevel.Unsupported) "Unsupported" else "Needs setup",
                        if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit action")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete action", tint = MaterialTheme.colorScheme.error)
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(context.type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                Text(
                    contextConfigSummary(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (context.invert) {
                StatusPill("Inverted", MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit context")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete context", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RunLogScreenContent(logs: List<RunLogEntry>, tasks: List<Task>, contentPadding: PaddingValues) {
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
    var statusFilter by remember { mutableStateOf(RunLogStatusFilter.All) }
    var taskIdFilter by remember { mutableStateOf<Long?>(null) }
    var query by remember { mutableStateOf("") }
    val taskOptions = remember(logs, tasks) { runLogTaskOptions(logs, tasks) }
    val filteredLogs = remember(logs, statusFilter, taskIdFilter, query) {
        filterRunLogs(logs, RunLogFilterState(status = statusFilter, taskId = taskIdFilter, query = query))
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RunLogSummaryCard(logs)
        }
        item {
            RunLogFilterCard(
                totalCount = logs.size,
                visibleCount = filteredLogs.size,
                statusFilter = statusFilter,
                onStatusFilterChange = { statusFilter = it },
                taskOptions = taskOptions,
                selectedTaskId = taskIdFilter,
                onTaskFilterChange = { taskIdFilter = it },
                query = query,
                onQueryChange = { query = it },
            )
        }
        if (filteredLogs.isEmpty()) {
            item {
                InlineNotice(
                    title = "No matching runs",
                    body = "Adjust the status filter or search text to review more execution history.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredLogs, key = { it.id }) { entry ->
            RunLogCard(entry)
        }
    }
}

@Composable
private fun RunLogFilterCard(
    totalCount: Int,
    visibleCount: Int,
    statusFilter: RunLogStatusFilter,
    onStatusFilterChange: (RunLogStatusFilter) -> Unit,
    taskOptions: List<Pair<Long, String>>,
    selectedTaskId: Long?,
    onTaskFilterChange: (Long?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Find runs", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$visibleCount of $totalCount shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (statusFilter != RunLogStatusFilter.All || selectedTaskId != null || query.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onStatusFilterChange(RunLogStatusFilter.All)
                            onTaskFilterChange(null)
                            onQueryChange("")
                        },
                    ) {
                        Text("Clear")
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    RunLogFilterChip(
                        label = "Any task",
                        selected = selectedTaskId == null,
                        onClick = { onTaskFilterChange(null) },
                    )
                }
                items(taskOptions, key = { it.first }) { (taskId, taskName) ->
                    RunLogFilterChip(
                        label = taskName,
                        selected = selectedTaskId == taskId,
                        onClick = { onTaskFilterChange(taskId) },
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(RunLogStatusFilter.entries.toList(), key = { it.name }) { filter ->
                    RunLogFilterChip(
                        label = filter.label,
                        selected = statusFilter == filter,
                        onClick = { onStatusFilterChange(filter) },
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Search task or message") },
                placeholder = { Text("permission, WiFi, task name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunLogFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
            } else {
                Color.Transparent
            },
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
            },
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunLogSummaryCard(logs: List<RunLogEntry>) {
    val outcomes = remember(logs) { logs.map { it.outcome() } }
    val failures = outcomes.count { it == RunLogOutcome.Failed }
    val skipped = outcomes.count { it == RunLogOutcome.Skipped }
    val latest = logs.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Execution history", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Recent runs with duration and failure details.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    when {
                        failures > 0 -> "$failures failed"
                        skipped > 0 -> "$skipped skipped"
                        else -> "Healthy"
                    },
                    when {
                        failures > 0 -> MaterialTheme.colorScheme.error
                        skipped > 0 -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.tertiary
                    },
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item { SummaryMetric("${logs.size}", "Entries", Modifier.width(104.dp)) }
                item { SummaryMetric("${outcomes.count { it == RunLogOutcome.Succeeded }}", "Succeeded", Modifier.width(104.dp)) }
                item { SummaryMetric("$failures", "Failed", Modifier.width(104.dp)) }
                item { SummaryMetric("$skipped", "Skipped", Modifier.width(104.dp)) }
            }
            latest?.let {
                Text(
                    "Latest: ${it.taskName}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunLogCard(entry: RunLogEntry) {
    val time = remember(entry.timestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    val diagnostics = remember(entry.message) { entry.message.toRunLogDiagnostics() }
    val hasStructuredDiagnostics = diagnostics.source != null || diagnostics.decision != null || diagnostics.traces.isNotEmpty()
    val outcome = remember(entry.success, entry.message) { entry.outcome() }
    val accent = when (outcome) {
        RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.primary
        RunLogOutcome.Failed -> MaterialTheme.colorScheme.error
        RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondary
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (outcome) {
                RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                RunLogOutcome.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.32f)
                RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.36f)
            }
        ),
        border = BorderStroke(
            1.dp,
            when (outcome) {
                RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
                RunLogOutcome.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.30f)
                RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.34f)
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
                when (outcome) {
                    RunLogOutcome.Succeeded -> Icons.Filled.CheckCircle
                    RunLogOutcome.Failed -> Icons.Filled.Error
                    RunLogOutcome.Skipped -> Icons.Filled.Info
                },
                contentDescription = null,
                tint = accent,
            )
            Column(Modifier.weight(1f)) {
                Text(entry.taskName, style = MaterialTheme.typography.titleMedium)
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                diagnostics.source?.let { source ->
                    Text(
                        "Source: $source",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hasStructuredDiagnostics && diagnostics.detailLines.isNotEmpty()) {
                    Text(
                        diagnostics.detailLines.joinToString("  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                diagnostics.reason?.let { reason ->
                    Text(reason, style = MaterialTheme.typography.bodyMedium, color = accent)
                }
                if (diagnostics.traces.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        diagnostics.traces.take(4).forEach { trace ->
                            RunLogTraceRow(trace)
                        }
                        if (diagnostics.traces.size > 4) {
                            Text(
                                "${diagnostics.traces.size - 4} more action(s)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (diagnostics.detailLines.isNotEmpty()) {
                    Text(
                        diagnostics.detailLines.joinToString("\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(outcome.label, accent)
                StatusPill("${entry.durationMs} ms", accent)
            }
        }
    }
}

@Composable
private fun RunLogTraceRow(trace: RunLogActionDiagnostic) {
    val color = when (trace.status) {
        ActionTraceStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ActionTraceStatus.FAILURE -> MaterialTheme.colorScheme.error
        ActionTraceStatus.SKIPPED -> MaterialTheme.colorScheme.secondary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusPill(trace.status.readableName(), color)
        Column(Modifier.weight(1f)) {
            Text(
                "${trace.index + 1}. ${trace.label}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${trace.actionType} - ${trace.durationMs} ms - ${trace.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            trace.argumentSummary?.let { summary ->
                Text(
                    "Expanded: $summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trace.templateWarningCount > 0) {
                Spacer(Modifier.height(4.dp))
                StatusPill(
                    "${trace.templateWarningCount} template warning${plural(trace.templateWarningCount)}",
                    MaterialTheme.colorScheme.error,
                )
            }
            if (trace.templateExpressions.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    trace.templateExpressions.take(3).forEach { expression ->
                        Text(
                            "${expression.argName}: ${expression.expression} -> ${expression.value} (${expression.source})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        expression.warning?.let { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (trace.templateExpressions.size > 3) {
                        Text(
                            "${trace.templateExpressions.size - 3} more template expression(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
    actionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionEnabled: Boolean = true,
    tertiaryActionLabel: String? = null,
    onTertiaryAction: (() -> Unit)? = null,
    tertiaryActionEnabled: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        ) {
            Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, enabled = actionEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(actionLabel)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(secondaryActionLabel)
            }
        }
        if (tertiaryActionLabel != null && onTertiaryAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onTertiaryAction,
                enabled = tertiaryActionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tertiaryActionLabel)
            }
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    target: DeleteTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(target.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(target.body, style = MaterialTheme.typography.bodyMedium)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
                ) {
                    Text(
                        "This action cannot be undone.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(target.confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TaskerImportReviewDialog(
    state: TaskerImportReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = state.preview
    val migrationWarnings = (preview.warnings + preview.lossyWarnings).distinct()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Review Tasker import") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    Text(
                        "Imported profiles will be created disabled so actions, contexts, and permissions can be reviewed before use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.importTaskCount}", "Tasks", Modifier.weight(1f))
                        SummaryMetric("${preview.importProfileCount}", "Profiles", Modifier.weight(1f))
                        SummaryMetric("${preview.importVariableCount}", "Variables", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${preview.sourceTaskCount}", "Src tasks", Modifier.weight(1f))
                        SummaryMetric("${preview.sourceProfileCount}", "Src profiles", Modifier.weight(1f))
                        SummaryMetric("${preview.sourceSceneCount}", "Scenes", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("${preview.mappedActionCount} mapped", MaterialTheme.colorScheme.tertiary)
                        StatusPill("${preview.unsupportedActionCount} unsupported", MaterialTheme.colorScheme.error)
                    }
                }
                if (preview.capabilityWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Capability review",
                            values = preview.capabilityWarnings,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (migrationWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Migration warnings",
                            values = migrationWarnings,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.report.unsupportedActions.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Unsupported Tasker actions",
                            values = state.report.unsupportedActions.map {
                                "${it.taskName} step ${it.actionIndex + 1}: code ${it.taskerCode}"
                            },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.report.mappedActions.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Mapped actions",
                            values = state.report.mappedActions.map {
                                "${it.taskName}: ${it.taskerCode} -> ${it.openTaskerActionId}"
                            },
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = preview.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) "Importing..." else "Import for Review")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun TaskerImportListSection(
    title: String,
    values: List<String>,
    color: Color,
) {
    InlineNotice(
        title = title,
        body = values.take(5).joinToString("\n") + if (values.size > 5) "\n${values.size - 5} more" else "",
        color = color,
    )
}

@Composable
private fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ProfileTemplate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Starter templates") },
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
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.installable) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                            },
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(template.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                StatusPill(
                                    status,
                                    when (template.availability) {
                                        TemplateAvailability.Ready -> MaterialTheme.colorScheme.tertiary
                                        TemplateAvailability.RequiresSetup -> MaterialTheme.colorScheme.primary
                                        TemplateAvailability.Planned -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
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
                Text("Create for Review")
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
    val parsedPriority = priority.toIntOrNull()
    val canSave = name.isNotBlank() && parsedPriority != null && parsedPriority in 0..10

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Create Task" else "Edit Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Task name") },
                    placeholder = { Text("Morning focus mode") },
                    supportingText = { Text("Use a clear verb or outcome so profiles stay easy to scan.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter(Char::isDigit).take(2) },
                    label = { Text("Priority") },
                    supportingText = { Text(if (parsedPriority == null || parsedPriority !in 0..10) "Enter a value from 0 to 10." else "Higher priority tasks run first when queues compete.") },
                    isError = parsedPriority == null || parsedPriority !in 0..10,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, parsedPriority ?: 5) }) {
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
    var enterTaskId by remember(profile?.id, tasks) { mutableLongStateOf(initialTaskId) }
    var cooldown by remember(profile?.id) { mutableStateOf((profile?.cooldownSec ?: 0).toString()) }
    var automationMode by remember(profile?.id) { mutableStateOf(profile?.automationMode ?: AutomationMode.SINGLE) }
    val parsedCooldown = cooldown.toIntOrNull()
    val canSave = name.isNotBlank() && enterTaskId > 0 && (cooldown.isBlank() || parsedCooldown != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Create Profile" else "Edit Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile name") },
                    placeholder = { Text("Weekday work mode") },
                    supportingText = { Text("Profiles read best when they describe the situation they detect.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Enable after saving", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "Leave off until contexts and actions are reviewed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
                Text("Enter task", style = MaterialTheme.typography.labelLarge)
                tasks.forEach { task ->
                    SelectableOption(
                        title = task.name,
                        body = "${task.actions.size} action${plural(task.actions.size)}",
                        selected = task.id == enterTaskId,
                        onClick = { enterTaskId = task.id },
                    )
                }
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit).take(5) },
                    label = { Text("Cooldown seconds") },
                    supportingText = { Text(if (cooldown.isNotBlank() && parsedCooldown == null) "Enter seconds as a whole number." else "Prevents rapid re-triggering after a match.") },
                    isError = cooldown.isNotBlank() && parsedCooldown == null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Re-trigger behavior", style = MaterialTheme.typography.labelLarge)
                AutomationMode.entries.forEach { mode ->
                    val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                    SelectableOption(
                        title = label,
                        body = automationModeDescription(mode),
                        selected = mode == automationMode,
                        onClick = { automationMode = mode },
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, enabled, enterTaskId, parsedCooldown ?: 0, automationMode) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SelectableOption(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f) else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                StatusPill("Selected", MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    val actionGroups = remember {
        ActionMetadataRegistry.all()
            .groupBy { it.category }
            .toSortedMap()
            .map { (category, actions) -> category to actions.sortedBy { it.name } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add action") },
        text = {
            LazyColumn(
                modifier = Modifier.height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actionGroups.forEach { (category, actions) ->
                    item(key = "category-$category") {
                        Text(
                            category,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(actions, key = { it.id }) { metadata ->
                        val capability = ActionCapabilityRegistry.get(metadata.id)
                        Card(
                            onClick = { onSelect(metadata) },
                            enabled = capability.canAdd,
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (capability.canAdd) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                                },
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(metadata.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (capability.level != CapabilityLevel.Supported) {
                                        StatusPill(
                                            if (capability.level == CapabilityLevel.Unsupported) "Unsupported" else "Setup",
                                            if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(metadata.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (capability.level != CapabilityLevel.Supported) {
                                    Text(capability.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
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
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Action label") },
                        supportingText = { Text("Shown in task steps and run-log traces.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
        FieldType.CHECKBOX -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    field.hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = value.toBoolean(), onCheckedChange = { onChange(it.toString()) })
            }
        }

        FieldType.MULTILINE -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text("Required") }} else null,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '-' || ch == '.' }) },
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text("Required") }} else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.DROPDOWN,
        FieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text("Required") }} else null,
            singleLine = field.fieldType != FieldType.MULTILINE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DayScheduleInput(value: String, onChange: (String) -> Unit) {
    val selected = DaySchedule.parse(value)
    val canonical = DaySchedule.canonicalize(selected).orEmpty()
    val allDays = DaySchedule.orderedDays.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Day schedule", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DayPresetButton(
                label = "Daily",
                selected = selected == allDays,
                onClick = { onChange(DaySchedule.canonicalize(allDays).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
            DayPresetButton(
                label = "Weekdays",
                selected = selected == DaySchedule.weekdays,
                onClick = { onChange(DaySchedule.canonicalize(DaySchedule.weekdays).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
            DayPresetButton(
                label = "Weekend",
                selected = selected == DaySchedule.weekends,
                onClick = { onChange(DaySchedule.canonicalize(DaySchedule.weekends).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
        }
        listOf(
            listOf("MON", "TUE", "WED"),
            listOf("THU", "FRI", "SAT", "SUN"),
        ).forEach { rowDays ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowDays.forEach { day ->
                    DayPresetButton(
                        label = day,
                        selected = day in selected,
                        onClick = {
                            val next = if (day in selected) selected - day else selected + day
                            onChange(DaySchedule.canonicalize(next).orEmpty())
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it) },
            label = { Text("Days *") },
            placeholder = { Text("weekdays, weekends, MON-FRI") },
            supportingText = {
                Text(
                    when {
                        value.isBlank() -> "Select at least one day."
                        canonical.isBlank() -> "Use weekdays, weekends, every day, or day tokens/ranges."
                        else -> DaySchedule.displayLabel(value)
                    },
                )
            },
            isError = value.isNotBlank() && canonical.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DayPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f) else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.62f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContextTypePickerDialog(onDismiss: () -> Unit, onSelect: (ContextType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add context") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextType.entries.forEach { type ->
                    Card(
                        onClick = { onSelect(type) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    var nfcWriteMessage by remember { mutableStateOf<String?>(null) }
    val fields = contextFields(state.type)
    val saveConfig = contextConfigForSave(state.type, config)
    val missingRequired = fields.any { it.required && config[it.key].isNullOrBlank() } ||
        (state.type == ContextType.DAY && saveConfig["days"].isNullOrBlank())

    LaunchedEffect(Unit) {
        NfcTagWriteSession.results.collect { result ->
            nfcWriteMessage = result.message
        }
    }

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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Invert match", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "Run when this context is not true.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = invert, onCheckedChange = { invert = it })
                        }
                    }
                    HorizontalDivider()
                }
                if (state.type == ContextType.DAY) {
                    item("day-schedule") {
                        DayScheduleInput(
                            value = config["days"].orEmpty(),
                            onChange = { value -> config = config + ("days" to value) },
                        )
                    }
                } else {
                    items(fields, key = { it.key }) { field ->
                        ActionFieldInput(
                            field = field,
                            value = config[field.key].orEmpty(),
                            onChange = { value -> config = config + (field.key to value) },
                        )
                    }
                    if (state.type == ContextType.EVENT && config["event"].equals("nfc", ignoreCase = true)) {
                        item("nfc-write-helper") {
                            NfcWriteHelperCard(
                                tagId = config["tagId"].orEmpty(),
                                message = nfcWriteMessage,
                                onArm = { label ->
                                    nfcWriteMessage = NfcTagWriteSession.armTextRecord(label).message
                                },
                            )
                        }
                    }
                    val eventPresets = if (state.type == ContextType.EVENT) {
                        CalendarSunEventPresets.presetsFor(config["event"].orEmpty())
                    } else {
                        emptyList()
                    }
                    if (eventPresets.isNotEmpty()) {
                        item("event-presets") {
                            EventPresetRow(
                                presets = eventPresets,
                                onApply = { preset ->
                                    config = CalendarSunEventPresets.applyPreset(config, preset)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired,
                onClick = { onSave(ContextSpec(state.type, saveConfig, invert)) },
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
    ContextType.DAY -> listOf(ActionField("days", "Days", required = true, hint = "weekdays, weekends, MON-FRI"))
    ContextType.LOCATION -> listOf(
        ActionField("latitude", "Latitude", FieldType.NUMBER, required = true),
        ActionField("longitude", "Longitude", FieldType.NUMBER, required = true),
        ActionField("radiusMeters", "Radius meters", FieldType.NUMBER, required = true, hint = "100"),
        ActionField("maxAccuracyMeters", "Max accuracy meters", FieldType.NUMBER, hint = "50"),
        ActionField("dwellSeconds", "Dwell seconds", FieldType.NUMBER, hint = "300"),
    )
    ContextType.STATE -> listOf(
        ActionField("key", "State key", required = true, hint = "battery_level, charging, headphones, screen"),
        ActionField("operator", "Operator", hint = "=, >=, <=, >, <"),
        ActionField("value", "Expected value", required = true, hint = "true/false, connected/disconnected, on/off, 80"),
    )
    ContextType.EVENT -> listOf(
        ActionField("event", "Event type", required = true, hint = "boot_completed, notification, nfc, calendar, sunrise, sunset"),
        ActionField("state", "Event state", hint = "during, upcoming"),
        ActionField("calendar", "Calendar name", hint = "Work"),
        ActionField("beforeMinutes", "Before minutes", FieldType.NUMBER, hint = "15"),
        ActionField("package", "Package allowlist", hint = "com.example.app, com.chat.app"),
        ActionField("tagId", "NFC tag ID", hint = "04AABBCC"),
        ActionField("latitude", "Latitude", FieldType.NUMBER, hint = "40.7128"),
        ActionField("longitude", "Longitude", FieldType.NUMBER, hint = "-74.0060"),
        ActionField("offsetMinutes", "Sun offset minutes", FieldType.NUMBER, hint = "-30"),
        ActionField("windowMinutes", "Sun window minutes", FieldType.NUMBER, hint = "5"),
        ActionField("title", "Title contains", hint = "Optional notification title text"),
        ActionField("body", "Body contains", hint = "Optional notification body text"),
        ActionField("filter", "Any metadata filter", hint = "Optional text/package/action filter"),
        ActionField("regex", "Use regex matching", FieldType.CHECKBOX),
    )
}

private fun contextConfigForSave(type: ContextType, config: Map<String, String>): Map<String, String> {
    val nonBlank = config.filterValues { it.isNotBlank() }
    if (type != ContextType.DAY) return nonBlank
    val canonicalDays = DaySchedule.canonicalize(config["days"].orEmpty()).orEmpty()
    return if (canonicalDays.isBlank()) {
        nonBlank - "days"
    } else {
        nonBlank + ("days" to canonicalDays)
    }
}

private fun defaultContextConfig(type: ContextType): Map<String, String> = when (type) {
    ContextType.TIME -> mapOf("start" to "09:00", "end" to "17:00")
    ContextType.DAY -> mapOf("days" to "MON,TUE,WED,THU,FRI")
    ContextType.LOCATION -> mapOf("radiusMeters" to "100")
    else -> emptyMap()
}

@Composable
private fun NfcWriteHelperCard(
    tagId: String,
    message: String?,
    onArm: (String) -> Unit,
) {
    val label = if (tagId.isBlank()) {
        "OpenTasker NFC trigger"
    } else {
        "OpenTasker NFC trigger $tagId"
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("NFC write helper", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Arms a one-time NDEF text write for the next scanned tag.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onArm(label) }) {
                    Text("Arm")
                }
            }
            message?.takeIf { it.isNotBlank() }?.let { value ->
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EventPresetRow(
    presets: List<EventContextPreset>,
    onApply: (EventContextPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Presets", style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(presets, key = { it.id }) { preset ->
                OutlinedButton(onClick = { onApply(preset) }) {
                    Text(preset.label)
                }
            }
        }
    }
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
    ContextType.DAY -> "Matches on selected days, presets, or weekday/weekend ranges."
    ContextType.LOCATION -> "Matches near a latitude/longitude radius with optional accuracy and dwell checks."
    ContextType.STATE -> "Matches a device state such as battery level, charging, headphones, or screen."
    ContextType.EVENT -> "Matches a one-shot event such as boot, SMS, notification, NFC, calendar, sun, or intent."
}

private fun runLogTaskOptions(logs: List<RunLogEntry>, tasks: List<Task>): List<Pair<Long, String>> {
    val taskNames = tasks.associate { it.id to it.name }
    return logs
        .groupBy { it.taskId }
        .map { (taskId, entries) -> taskId to (taskNames[taskId] ?: entries.first().taskName) }
        .sortedWith(compareBy<Pair<Long, String>> { it.second.lowercase() }.thenBy { it.first })
}

private fun ActionTraceStatus.readableName(): String =
    name.lowercase().replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun plural(count: Int): String = if (count == 1) "" else "s"
