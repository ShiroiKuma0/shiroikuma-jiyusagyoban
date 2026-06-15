package com.opentasker.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentasker.ui.components.ReorderableRow
import com.opentasker.ui.components.RgbaColorPickerDialog
import com.opentasker.ui.components.rememberListReorderState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.opentasker.app.BuildConfig
import com.opentasker.ui.theme.ThemeStore
import kotlin.math.roundToInt
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.RETURN_VALUES_ACTION_ID
import com.opentasker.core.actions.RETURN_VALUE_PREFIX
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_PARAM_PREFIX
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.contexts.CalendarSunEventPresets
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.EventContextPreset
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.widget.TaskShortcutHelper
import com.opentasker.widget.WidgetEditor
import com.opentasker.core.engine.ActionTraceStatus
import com.opentasker.core.engine.RunLogActionDiagnostic
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.outcome
import com.opentasker.core.engine.RunLogSource
import com.opentasker.core.engine.toRunLogDiagnostics
import com.opentasker.core.flow.AutomationFlowTarget
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.ProjectFilter
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.RunLogRetentionOptions
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.ProjectSelectionStore
import com.opentasker.core.storage.ListSortStore
import com.opentasker.core.storage.SortMethod
import com.opentasker.core.storage.SortTab
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.displayLabel
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.toEntity
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.ProjectConflictStrategy
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
import kotlinx.coroutines.flow.combine
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
private const val OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES = 8 * 1024 * 1024
private val TASKER_XML_MIME_TYPES = arrayOf("application/xml", "text/xml", "text/*", "*/*")
private val OPEN_TASKER_BUNDLE_MIME_TYPES = arrayOf("application/json", "text/json", "text/*", "*/*")
private val DATABASE_BACKUP_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/x-sqlite3",
    "application/vnd.sqlite3",
    "*/*",
)

private fun databaseBackupExportName(): String =
    "opentasker_backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.db"

private fun openTaskerBundleExportName(): String =
    "opentasker_bundle_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.json"

private enum class OpenTaskerScreen(val label: String) {
    Profiles("Profiles"),
    Tasks("Tasks"),
    Vars("Vars"),
    Flow("Flow"),
    Scenes("Scenes"),
    Widgets("Widgets"),
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

internal data class OpenTaskerBundleReviewState(
    val bundle: OpenTaskerBundle,
    val plan: BundleImportPlan,
)

/** A pending selective export: exactly these items, plus the include-variables choice. */
private data class ExportRequest(
    val name: String,
    val fileName: String,
    val profileIds: Set<Long> = emptySet(),
    val taskIds: Set<Long> = emptySet(),
    val sceneIds: Set<Long> = emptySet(),
    val includeVariables: Boolean = false,
)

private fun exportFileName(label: String): String =
    label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "export" } + ".json"

private sealed interface MoveTarget {
    val currentProjectId: Long?

    data class ProfileMove(val profile: Profile) : MoveTarget {
        override val currentProjectId get() = profile.projectId
    }

    data class TaskMove(val task: Task) : MoveTarget {
        override val currentProjectId get() = task.projectId
    }

    data class SceneMove(val scene: Scene) : MoveTarget {
        override val currentProjectId get() = scene.projectId
    }
}

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
    private val runLogRetentionSettings = RunLogRetentionSettings(appContext)
    private val databaseBackupManager = DatabaseBackupManager(appContext, db)

    val profiles: StateFlow<List<Profile>> =
        combine(db.profileDao().getAllAsFlow(), ListSortStore.state) { entities, sort ->
            val items = entities.map { it.toDomain() }
            if (sort.profiles == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() } else items.sortedBy { it.position }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<Task>> =
        combine(db.taskDao().getAllAsFlow(), ListSortStore.state) { entities, sort ->
            val items = entities.map { it.toDomain() }
            if (sort.tasks == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() } else items.sortedBy { it.position }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scenes: StateFlow<List<Scene>> =
        combine(db.sceneDao().getAllAsFlow(), ListSortStore.state) { entities, sort ->
            val items = entities.map { it.toDomain() }
            if (sort.scenes == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() } else items.sortedBy { it.position }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val projects: StateFlow<List<Project>> = db.projectDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val projectSelectionStore = ProjectSelectionStore(appContext)
    var projectFilter by mutableStateOf<ProjectFilter>(projectSelectionStore.load())
        private set

    val runLogs: StateFlow<List<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val globalVariables: StateFlow<List<Variable>> = db.variableDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    var runLogRetentionPolicy by mutableStateOf(runLogRetentionSettings.load())
        private set

    var backupSetupState by mutableStateOf(loadBackupSetupState(busy = false))
        private set

    internal var taskerImportReview by mutableStateOf<TaskerImportReviewState?>(null)
        private set

    var taskerImportBusy by mutableStateOf(false)
        private set

    internal var openTaskerBundleReview by mutableStateOf<OpenTaskerBundleReviewState?>(null)
        private set

    var openTaskerBundleBusy by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            runCatching { pruneRunLogs(runLogRetentionPolicy) }
        }
    }

    fun createTask(name: String, priority: Int, projectId: Long? = null) = launchWithMessage("Task created") {
        db.taskDao().insert(Task(name = name.trim(), priority = priority.coerceIn(0, 10), projectId = projectId, position = db.taskDao().nextPosition()).toEntity())
    }

    /** Persist a manual reorder of the visible (filtered) tasks by reusing their own position slots. */
    fun reorderTasks(orderedVisible: List<Task>) = viewModelScope.launch {
        val slots = orderedVisible.map { it.position }.sorted()
        orderedVisible.forEachIndexed { index, task -> db.taskDao().setPosition(task.id, slots[index]) }
    }

    fun updateTask(task: Task, message: String = "Task updated") = launchWithMessage(message) {
        val previous = db.taskDao().getById(task.id)
        if (previous != null) {
            db.editHistoryDao().insert(
                EditHistoryEntity(
                    entityType = EditHistoryDao.TYPE_TASK,
                    entityId = task.id,
                    previousJson = previous.actionsJson,
                ),
            )
            db.editHistoryDao().pruneOld(EditHistoryDao.TYPE_TASK, task.id)
        }
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

    fun createScene(name: String, widthDp: Int, heightDp: Int, projectId: Long? = null) = launchWithMessage("Scene created") {
        db.sceneDao().insert(
            Scene(
                name = name.trim(),
                widthDp = widthDp.coerceIn(120, 1440),
                heightDp = heightDp.coerceIn(80, 2560),
                projectId = projectId,
                position = db.sceneDao().nextPosition(),
            ).toEntity()
        )
    }

    /** Persist a manual reorder of the visible (filtered) scenes by reusing their own position slots. */
    fun reorderScenes(orderedVisible: List<Scene>) = viewModelScope.launch {
        val slots = orderedVisible.map { it.position }.sorted()
        orderedVisible.forEachIndexed { index, scene -> db.sceneDao().setPosition(scene.id, slots[index]) }
    }

    fun updateScene(scene: Scene, message: String = "Scene updated") = launchWithMessage(message) {
        db.sceneDao().update(scene.toEntity())
    }

    fun deleteScene(scene: Scene) = launchWithMessage("Scene deleted") {
        db.sceneDao().delete(scene.toEntity())
    }

    // ---- Projects (organizational; the engine ignores projectId) ----

    fun selectProject(filter: ProjectFilter) {
        projectSelectionStore.save(filter)
        projectFilter = filter
    }

    fun createProject(name: String, color: Int?) = launchWithMessage("Project created") {
        val nextOrder = (db.projectDao().getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        db.projectDao().insert(Project(name = name.trim(), color = color, sortOrder = nextOrder).toEntity())
    }

    fun updateProject(project: Project) = launchWithMessage("Project updated") {
        db.projectDao().update(project.toEntity())
    }

    fun deleteProject(project: Project, deleteItems: Boolean) = launchWithMessage(
        if (deleteItems) "Project and its items deleted" else "Project deleted; items moved to Unfiled"
    ) {
        val pid = project.id
        db.withTransaction {
            val profileRows = db.profileDao().getAll().filter { it.projectId == pid }
            val taskRows = db.taskDao().getAll().filter { it.projectId == pid }
            val sceneRows = db.sceneDao().getAll().filter { it.projectId == pid }
            if (deleteItems) {
                profileRows.forEach { db.profileDao().delete(it) }
                taskRows.forEach { db.taskDao().delete(it) }
                sceneRows.forEach { db.sceneDao().delete(it) }
            } else {
                profileRows.forEach { db.profileDao().update(it.copy(projectId = null)) }
                taskRows.forEach { db.taskDao().update(it.copy(projectId = null)) }
                sceneRows.forEach { db.sceneDao().update(it.copy(projectId = null)) }
            }
            db.projectDao().delete(project.toEntity())
        }
        if ((projectFilter as? ProjectFilter.Of)?.projectId == pid) {
            selectProject(ProjectFilter.All)
        }
    }

    /** Reorder by reassigning contiguous sortOrder so the moved project shifts one slot. */
    fun moveProject(project: Project, up: Boolean) = launchWithMessage("Project reordered") {
        val ordered = db.projectDao().getAll()
            .sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
            .toMutableList()
        val index = ordered.indexOfFirst { it.id == project.id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in ordered.indices) return@launchWithMessage
        ordered.add(target, ordered.removeAt(index))
        db.withTransaction {
            ordered.forEachIndexed { position, row ->
                if (row.sortOrder != position) db.projectDao().update(row.copy(sortOrder = position))
            }
        }
    }

    fun moveProfileToProject(profile: Profile, projectId: Long?) = launchWithMessage("Profile moved") {
        db.profileDao().update(profile.copy(projectId = projectId).toEntity())
    }

    fun moveTaskToProject(task: Task, projectId: Long?) = launchWithMessage("Task moved") {
        db.taskDao().update(task.copy(projectId = projectId).toEntity())
    }

    fun moveSceneToProject(scene: Scene, projectId: Long?) = launchWithMessage("Scene moved") {
        db.sceneDao().update(scene.copy(projectId = projectId).toEntity())
    }

    fun createProfile(name: String, enabled: Boolean, enterTaskId: Long, cooldownSec: Int, automationMode: AutomationMode, projectId: Long? = null) =
        launchWithMessage("Profile created") {
            db.profileDao().insert(
                Profile(
                    name = name.trim(),
                    enabled = enabled,
                    enterTaskId = enterTaskId,
                    cooldownSec = cooldownSec.coerceAtLeast(0),
                    automationMode = automationMode,
                    projectId = projectId,
                    position = db.profileDao().nextPosition(),
                ).toEntity()
            )
        }

    /** Persist a manual reorder of the visible (filtered) profiles by reusing their own position slots. */
    fun reorderProfiles(orderedVisible: List<Profile>) = viewModelScope.launch {
        val slots = orderedVisible.map { it.position }.sorted()
        orderedVisible.forEachIndexed { index, profile -> db.profileDao().setPosition(profile.id, slots[index]) }
    }

    fun updateProfile(profile: Profile, message: String = "Profile updated") =
        launchWithMessage(message) {
            val previousEntity = profile.id.takeIf { it > 0L }
                ?.let { db.profileDao().getById(it) }
            val previous = previousEntity?.toDomain()
            if (previousEntity != null) {
                db.editHistoryDao().insert(
                    EditHistoryEntity(
                        entityType = EditHistoryDao.TYPE_PROFILE,
                        entityId = profile.id,
                        previousJson = previousEntity.contextsJson,
                    ),
                )
                db.editHistoryDao().pruneOld(EditHistoryDao.TYPE_PROFILE, profile.id)
            }
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

    fun exportOpenTaskerBundle(uri: Uri, appVersion: String) {
        viewModelScope.launch {
            if (openTaskerBundleBusy) return@launch
            openTaskerBundleBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = bundleRepository.exportBundle(
                        appVersion = appVersion,
                        name = "白い熊 自由作業盤 Workspace Export",
                        description = "Profiles, tasks, variables, and scenes exported from 白い熊 自由作業盤.",
                    )
                    val encoded = OpenTaskerBundleCodec.encode(bundle)
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open export destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(encoded) }
                    bundle
                }
            }
                .onSuccess { bundle ->
                    events.send(
                        "Exported ${bundle.tasks.size} task${plural(bundle.tasks.size)}, " +
                            "${bundle.profiles.size} profile${plural(bundle.profiles.size)}, " +
                            "${bundle.scenes.size} scene${plural(bundle.scenes.size)}"
                    )
                }
                .onFailure { events.send("Error: ${it.message ?: "白い熊 自由作業盤 bundle export failed"}") }
            openTaskerBundleBusy = false
        }
    }

    fun exportSelectionBundle(
        uri: Uri,
        appVersion: String,
        profileIds: Set<Long>,
        taskIds: Set<Long>,
        sceneIds: Set<Long>,
        includeVariables: Boolean,
        name: String,
    ) {
        viewModelScope.launch {
            if (openTaskerBundleBusy) return@launch
            openTaskerBundleBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = bundleRepository.exportSelection(
                        appVersion = appVersion,
                        profileIds = profileIds,
                        taskIds = taskIds,
                        sceneIds = sceneIds,
                        includeVariables = includeVariables,
                        name = name,
                    )
                    val encoded = OpenTaskerBundleCodec.encode(bundle)
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open export destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(encoded) }
                    bundle
                }
            }
                .onSuccess { bundle ->
                    val parts = buildList {
                        if (bundle.profiles.isNotEmpty()) add("${bundle.profiles.size} profile${plural(bundle.profiles.size)}")
                        if (bundle.tasks.isNotEmpty()) add("${bundle.tasks.size} task${plural(bundle.tasks.size)}")
                        if (bundle.scenes.isNotEmpty()) add("${bundle.scenes.size} scene${plural(bundle.scenes.size)}")
                        if (bundle.variables.isNotEmpty()) add("${bundle.variables.size} variable${plural(bundle.variables.size)}")
                    }
                    events.send("Exported ${parts.joinToString().ifEmpty { "nothing" }}")
                }
                .onFailure { events.send("Error: ${it.message ?: "Export failed"}") }
            openTaskerBundleBusy = false
        }
    }

    fun previewOpenTaskerBundle(uri: Uri) {
        viewModelScope.launch {
            if (openTaskerBundleBusy) return@launch
            openTaskerBundleBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val rawJson = readBoundedOpenTaskerBundle(appContext, uri)
                    val bundle = OpenTaskerBundleCodec.decode(rawJson)
                    OpenTaskerBundleReviewState(bundle = bundle, plan = OpenTaskerBundleCodec.validate(bundle))
                }
            }
                .onSuccess {
                    openTaskerBundleReview = it
                    events.send("白い熊 自由作業盤 bundle ready for review")
                }
                .onFailure { events.send("Error: ${it.message ?: "白い熊 自由作業盤 bundle preview failed"}") }
            openTaskerBundleBusy = false
        }
    }

    fun clearOpenTaskerBundleReview() {
        if (!openTaskerBundleBusy) {
            openTaskerBundleReview = null
        }
    }

    fun confirmOpenTaskerBundleImport(
        bundle: OpenTaskerBundle,
        projectConflictStrategy: ProjectConflictStrategy = ProjectConflictStrategy.MERGE,
    ) {
        viewModelScope.launch {
            if (openTaskerBundleBusy) return@launch
            openTaskerBundleBusy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bundleRepository.importBundle(bundle, projectConflictStrategy)
                }
            }
                .onSuccess { importReport ->
                    openTaskerBundleReview = null
                    events.send(
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}, " +
                            "${importReport.insertedScenes} scene${plural(importReport.insertedScenes)}" +
                            if (importReport.insertedProjects > 0) ", ${importReport.insertedProjects} project${plural(importReport.insertedProjects)}" else ""
                    )
                }
                .onFailure { events.send("Error: ${it.message ?: "白い熊 自由作業盤 bundle import failed"}") }
            openTaskerBundleBusy = false
        }
    }

    fun updateRunLogRetention(policy: RunLogRetentionPolicy) {
        viewModelScope.launch {
            val normalized = policy.normalized()
            runCatching {
                runLogRetentionSettings.save(normalized)
                runLogRetentionPolicy = normalized
                pruneRunLogs(normalized)
            }
                .onSuccess { deleted ->
                    val suffix = if (deleted > 0) "; pruned $deleted old entry${plural(deleted)}" else ""
                    events.send("Run log retention updated$suffix")
                }
                .onFailure { events.send("Error: ${it.message ?: "Run log retention update failed"}") }
        }
    }

    private suspend fun pruneRunLogs(policy: RunLogRetentionPolicy): Int =
        db.runLogDao().pruneRetention(
            maxEntries = policy.maxEntries,
            minimumTimestamp = policy.minimumTimestamp(System.currentTimeMillis()),
        )

    fun createDatabaseBackup() {
        viewModelScope.launch {
            setBackupBusy(true)
            databaseBackupManager.backup()
                .onSuccess { backup ->
                    events.send("Backup created: ${backup.name}")
                }
                .onFailure { events.send("Error: ${it.message ?: "Database backup failed"}") }
            setBackupBusy(false)
        }
    }

    fun exportDatabaseBackup(uri: Uri) {
        viewModelScope.launch {
            setBackupBusy(true)
            val backup = databaseBackupManager.backup().getOrElse {
                events.send("Error: ${it.message ?: "Database backup failed"}")
                setBackupBusy(false)
                return@launch
            }
            databaseBackupManager.exportBackup(backup, uri)
                .onSuccess { events.send("Backup exported: ${backup.name}") }
                .onFailure { events.send("Error: ${it.message ?: "Database backup export failed"}") }
            setBackupBusy(false)
        }
    }

    fun importDatabaseBackup(uri: Uri) {
        viewModelScope.launch {
            setBackupBusy(true)
            databaseBackupManager.stageRestore(uri)
                .onSuccess { events.send("Backup imported. Restart 白い熊 自由作業盤 to apply the restore.") }
                .onFailure { events.send("Error: ${it.message ?: "Database backup import failed"}") }
            setBackupBusy(false)
        }
    }

    private fun setBackupBusy(busy: Boolean) {
        backupSetupState = loadBackupSetupState(busy)
    }

    private fun loadBackupSetupState(busy: Boolean): BackupSetupState =
        BackupSetupState(
            busy = busy,
            latestBackupName = databaseBackupManager.listBackups().firstOrNull()?.name,
            pendingRestore = databaseBackupManager.hasPendingRestore(),
        )

    fun runTaskNow(task: Task) {
        viewModelScope.launch {
            val result = executeAndLogTask(
                appContext = appContext,
                db = db,
                task = task,
                source = "Manual run",
            )
            val status = if (result.report.success) "succeeded" else "failed"
            events.send("${task.name} $status (${result.report.durationMs}ms)")
        }
    }

    fun pinTaskShortcut(task: Task) {
        viewModelScope.launch {
            if (!TaskShortcutHelper.canPinShortcut(appContext)) {
                events.send("Launcher does not support pinned shortcuts")
                return@launch
            }
            val requested = TaskShortcutHelper.requestPinShortcut(appContext, task)
            if (requested) {
                events.send("Pinning \"${task.name}\" to home screen")
            } else {
                events.send("Failed to pin shortcut")
            }
        }
    }

    fun undoLastTaskEdit(taskId: Long) = launchWithMessage("Edit undone") {
        val snapshot = db.editHistoryDao().getLatest(EditHistoryDao.TYPE_TASK, taskId) ?: run {
            events.send("No edit history available")
            return@launchWithMessage
        }
        val current = db.taskDao().getById(taskId) ?: return@launchWithMessage
        db.taskDao().update(current.copy(actionsJson = snapshot.previousJson))
        db.editHistoryDao().deleteFor(EditHistoryDao.TYPE_TASK, taskId)
    }

    fun undoLastProfileEdit(profileId: Long) = launchWithMessage("Edit undone") {
        val snapshot = db.editHistoryDao().getLatest(EditHistoryDao.TYPE_PROFILE, profileId) ?: run {
            events.send("No edit history available")
            return@launchWithMessage
        }
        val current = db.profileDao().getById(profileId) ?: return@launchWithMessage
        db.profileDao().update(current.copy(contextsJson = snapshot.previousJson))
        db.editHistoryDao().deleteFor(EditHistoryDao.TYPE_PROFILE, profileId)
    }

    // Route Vars-screen edits through the cache (single source of truth) so it write-throughs to the
    // DB and the running engine sees them immediately; projectId 0 = super-global, >0 = project-global.
    fun updateVariable(projectId: Long, name: String, value: String) {
        com.opentasker.core.engine.variables.PersistentGlobalScope.set(projectId, name, value)
    }

    fun deleteVariable(projectId: Long, name: String) {
        com.opentasker.core.engine.variables.PersistentGlobalScope.unset(projectId, name)
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
    val projects by viewModel.projects.collectAsState()
    val projectFilter = viewModel.projectFilter
    val currentProjectId = (projectFilter as? ProjectFilter.Of)?.projectId
    val visibleProfiles = when (projectFilter) {
        ProjectFilter.All -> profiles
        ProjectFilter.Unfiled -> profiles.filter { it.projectId == null }
        is ProjectFilter.Of -> profiles.filter { it.projectId == projectFilter.projectId }
    }
    val visibleTasks = when (projectFilter) {
        ProjectFilter.All -> tasks
        ProjectFilter.Unfiled -> tasks.filter { it.projectId == null }
        is ProjectFilter.Of -> tasks.filter { it.projectId == projectFilter.projectId }
    }
    val visibleScenes = when (projectFilter) {
        ProjectFilter.All -> scenes
        ProjectFilter.Unfiled -> scenes.filter { it.projectId == null }
        is ProjectFilter.Of -> scenes.filter { it.projectId == projectFilter.projectId }
    }
    val runLogs by viewModel.runLogs.collectAsState()
    val globalVariables by viewModel.globalVariables.collectAsState()
    val widgetTemplates by com.opentasker.widget.TemplateStore.state.collectAsState()
    val runLogRetentionPolicy = viewModel.runLogRetentionPolicy
    val backupSetupState = viewModel.backupSetupState
    val themePrefs by ThemeStore.state.collectAsState()
    val sortPrefs by ListSortStore.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf(OpenTaskerScreen.Profiles) }
    var showUiCustomization by remember { mutableStateOf(false) }
    var showProjectManagement by remember { mutableStateOf(false) }
    var showTaskLibrary by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<MoveTarget?>(null) }
    var exportRequest by remember { mutableStateOf<ExportRequest?>(null) }
    var pendingExportWrite by remember { mutableStateOf<ExportRequest?>(null) }
    var importConflict by remember { mutableStateOf<OpenTaskerBundle?>(null) }
    // Fold state hoisted to the root so it survives full-screen overlays (e.g. the action picker).
    val expandedTasks = remember { mutableStateMapOf<Long, Boolean>() }
    val expandedActions = remember { mutableStateMapOf<String, Boolean>() }
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
    val openTaskerBundleReview = viewModel.openTaskerBundleReview
    val openTaskerBundleBusy = viewModel.openTaskerBundleBusy
    val taskerXmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.previewTaskerXml(it, BuildConfig.VERSION_NAME) }
    }
    val openTaskerBundleExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportOpenTaskerBundle(it, BuildConfig.VERSION_NAME) }
    }
    val openTaskerBundleImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.previewOpenTaskerBundle(it) }
    }
    val selectiveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val req = pendingExportWrite
        pendingExportWrite = null
        if (uri != null && req != null) {
            viewModel.exportSelectionBundle(
                uri = uri,
                appVersion = BuildConfig.VERSION_NAME,
                profileIds = req.profileIds,
                taskIds = req.taskIds,
                sceneIds = req.sceneIds,
                includeVariables = req.includeVariables,
                name = req.name,
            )
        }
    }
    val databaseBackupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.exportDatabaseBackup(it) }
    }
    val databaseBackupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDatabaseBackup(it) }
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
        OpenTaskerScreen.Vars -> "${globalVariables.size} global variables"
        OpenTaskerScreen.Flow -> "${profiles.size} profiles - ${tasks.size} tasks"
        OpenTaskerScreen.Scenes -> "${scenes.sumOf { it.elements.size }} elements - ${scenes.size} scenes"
        OpenTaskerScreen.Widgets -> "${widgetTemplates.size} widget templates"
        OpenTaskerScreen.Inspector -> "Live context health"
        OpenTaskerScreen.Setup -> "Permission and reliability checks"
        OpenTaskerScreen.RunLog -> "${runLogs.size} recent entries"
    }

    if (showUiCustomization) {
        UiCustomizationScreen(onBack = { showUiCustomization = false })
        return
    }

    if (showProjectManagement) {
        ProjectsManagementScreen(
            projects = projects,
            memberCount = { pid ->
                profiles.count { it.projectId == pid } +
                    tasks.count { it.projectId == pid } +
                    scenes.count { it.projectId == pid }
            },
            onBack = { showProjectManagement = false },
            onCreate = { name, color -> viewModel.createProject(name, color) },
            onUpdate = { viewModel.updateProject(it) },
            onDelete = { project, deleteItems -> viewModel.deleteProject(project, deleteItems) },
            onMoveUp = { viewModel.moveProject(it, up = true) },
            onMoveDown = { viewModel.moveProject(it, up = false) },
            onExportProject = { project ->
                showProjectManagement = false
                exportRequest = ExportRequest(
                    name = "Project: ${project.name}",
                    fileName = exportFileName(project.name),
                    profileIds = profiles.filter { it.projectId == project.id }.map { it.id }.toSet(),
                    taskIds = tasks.filter { it.projectId == project.id }.map { it.id }.toSet(),
                    sceneIds = scenes.filter { it.projectId == project.id }.map { it.id }.toSet(),
                )
            },
        )
        return
    }

    val pickerTask = actionPickerTask
    if (pickerTask != null && themePrefs.advancedActionPicker) {
        AdvancedActionPickerScreen(
            onDismiss = { actionPickerTask = null },
            onSelect = { metadata ->
                actionPickerTask = null
                actionEdit = ActionEditState(pickerTask, metadata)
            },
        )
        return
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                // Fully custom flash: fill + border live on the SAME Surface node so the box is
                // opaque (the default Snackbar overload inserts padding between the border and the
                // fill, leaving a see-through ring). All attributes are theme-configurable.
                val flashShape = RoundedCornerShape(themePrefs.flashCornerRadiusDp.dp)
                Box(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = flashShape,
                        color = Color(themePrefs.flashBackground),
                        contentColor = Color(themePrefs.flashText),
                        border = if (themePrefs.flashBorderWidthDp > 0) {
                            BorderStroke(themePrefs.flashBorderWidthDp.dp, Color(themePrefs.flashBorder))
                        } else null,
                    ) {
                        Text(
                            text = data.visuals.message,
                            color = Color(themePrefs.flashText),
                            fontSize = themePrefs.flashTextSizeSp.sp,
                            fontWeight = FontWeight(themePrefs.flashFontWeight),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("白い熊 自由作業盤", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${screen.label} - $headerDetail",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (screen == OpenTaskerScreen.Tasks) {
                        IconButton(onClick = { showTaskLibrary = true }) {
                            Icon(Icons.Filled.Info, contentDescription = "Task library")
                        }
                    }
                    val sortTab = when (screen) {
                        OpenTaskerScreen.Profiles -> SortTab.PROFILES
                        OpenTaskerScreen.Tasks -> SortTab.TASKS
                        OpenTaskerScreen.Scenes -> SortTab.SCENES
                        else -> null
                    }
                    if (sortTab != null) {
                        val method = sortPrefs.of(sortTab)
                        val alpha = method == SortMethod.ALPHABETICAL
                        IconButton(onClick = {
                            ListSortStore.set(sortTab, if (alpha) SortMethod.MANUAL else SortMethod.ALPHABETICAL)
                        }) {
                            Icon(
                                if (alpha) Icons.Filled.SortByAlpha else Icons.Filled.FormatListNumbered,
                                contentDescription = if (alpha) "Sorting: alphabetical (tap for manual)" else "Sorting: manual (tap for alphabetical)",
                            )
                        }
                    }
                    if (screen == OpenTaskerScreen.Profiles ||
                        screen == OpenTaskerScreen.Tasks ||
                        screen == OpenTaskerScreen.Scenes
                    ) {
                        ProjectSwitcher(
                            filter = projectFilter,
                            projects = projects,
                            onSelect = { viewModel.selectProject(it) },
                            onManage = { showProjectManagement = true },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
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
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = if (tasks.isEmpty()) "Create task first" else "Create profile")
                }

                OpenTaskerScreen.Tasks -> FloatingActionButton(
                    onClick = { showCreateTaskDialog = true },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create task")
                }

                OpenTaskerScreen.Vars,
                OpenTaskerScreen.Flow,
                OpenTaskerScreen.Scenes,
                OpenTaskerScreen.Widgets,
                OpenTaskerScreen.Inspector,
                OpenTaskerScreen.Setup,
                OpenTaskerScreen.RunLog -> Unit
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.primary)
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                ) {
                    val navItemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                    )
                    OpenTaskerScreen.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = screen == destination,
                            onClick = { screen = destination },
                            colors = navItemColors,
                            icon = {
                            val icon = when (destination) {
                                OpenTaskerScreen.Profiles -> Icons.Filled.CheckCircle
                                OpenTaskerScreen.Tasks -> Icons.Filled.Edit
                                OpenTaskerScreen.Vars -> Icons.Filled.Menu
                                OpenTaskerScreen.Flow -> Icons.Filled.Info
                                OpenTaskerScreen.Scenes -> Icons.Filled.Edit
                                OpenTaskerScreen.Widgets -> Icons.Filled.Menu
                                OpenTaskerScreen.Inspector -> Icons.Filled.Info
                                OpenTaskerScreen.Setup -> Icons.Filled.Settings
                                OpenTaskerScreen.RunLog -> Icons.Filled.Info
                            }
                            if (destination == OpenTaskerScreen.Setup) {
                                // Long-press the Setup cog jumps straight to the 白い熊 自由作業盤 UI page;
                                // a normal tap still selects the Setup tab.
                                Box(
                                    modifier = Modifier.pointerInput(Unit) {
                                        detectTapGestures(
                                            onTap = { screen = OpenTaskerScreen.Setup },
                                            onLongPress = { showUiCustomization = true },
                                        )
                                    },
                                ) {
                                    Icon(icon, contentDescription = null)
                                }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        },
                        label = { Text(destination.label) },
                        alwaysShowLabel = true,
                    )
                }
                }
            }
        },
    ) { innerPadding ->
        when (screen) {
            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = visibleProfiles,
                tasks = tasks,
                runLogs = runLogs,
                onCreateTaskFirst = {
                    screen = OpenTaskerScreen.Tasks
                    showCreateTaskDialog = true
                },
                onCreateProfile = { showCreateProfileDialog = true },
                onBrowseTemplates = { showTemplateDialog = true },
                onExportOpenTaskerBundle = { openTaskerBundleExportLauncher.launch(openTaskerBundleExportName()) },
                onImportOpenTaskerBundle = { openTaskerBundleImportLauncher.launch(OPEN_TASKER_BUNDLE_MIME_TYPES) },
                openTaskerBundleBusy = openTaskerBundleBusy,
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
                onMoveProfile = { moveTarget = MoveTarget.ProfileMove(it) },
                onExportProfile = { exportRequest = ExportRequest(name = "Profile: ${it.name}", fileName = exportFileName(it.name), profileIds = setOf(it.id)) },
                manualSort = sortPrefs.profiles == SortMethod.MANUAL,
                onReorder = { viewModel.reorderProfiles(it) },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = visibleTasks,
                expandedTasks = expandedTasks,
                expandedActions = expandedActions,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { taskDialog = it },
                onDeleteTask = { pendingDelete = DeleteTarget.TaskTarget(it) },
                onRunTask = { viewModel.runTaskNow(it) },
                onPinTask = { viewModel.pinTaskShortcut(it) },
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
                onMoveTask = { moveTarget = MoveTarget.TaskMove(it) },
                onExportTask = { exportRequest = ExportRequest(name = "Task: ${it.name}", fileName = exportFileName(it.name), taskIds = setOf(it.id)) },
                onReorderAction = { task, newOrder -> viewModel.updateTask(task.copy(actions = newOrder), "Actions reordered") },
                manualSort = sortPrefs.tasks == SortMethod.MANUAL,
                onReorder = { viewModel.reorderTasks(it) },
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

            OpenTaskerScreen.Vars -> VariablesScreen(
                variables = globalVariables,
                contentPadding = innerPadding,
                onUpdate = viewModel::updateVariable,
                onDelete = viewModel::deleteVariable,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            )

            OpenTaskerScreen.Scenes -> SceneLibraryScreen(
                scenes = visibleScenes,
                tasks = tasks,
                onCreateScene = { name, widthDp, heightDp -> viewModel.createScene(name, widthDp, heightDp, currentProjectId) },
                onUpdateScene = viewModel::updateScene,
                onDeleteScene = { pendingDelete = DeleteTarget.SceneTarget(it) },
                onMoveScene = { moveTarget = MoveTarget.SceneMove(it) },
                onExportScene = { exportRequest = ExportRequest(name = "Scene: ${it.name}", fileName = exportFileName(it.name), sceneIds = setOf(it.id)) },
                manualSort = sortPrefs.scenes == SortMethod.MANUAL,
                onReorder = { viewModel.reorderScenes(it) },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Widgets -> WidgetTemplatesScreen(
                templates = widgetTemplates,
                onSave = { name, layout -> com.opentasker.widget.TemplateStore.put(name, layout) },
                onDelete = { com.opentasker.widget.TemplateStore.delete(it) },
                onImport = { raw -> com.opentasker.widget.TemplateStore.importJson(raw) },
                onExportAll = { com.opentasker.widget.TemplateStore.exportJson() },
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Setup -> PermissionOnboardingScreen(
                contentPadding = innerPadding,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                onOpenUiCustomization = { showUiCustomization = true },
                backupState = backupSetupState,
                onCreateBackup = viewModel::createDatabaseBackup,
                onExportBackup = { databaseBackupExportLauncher.launch(databaseBackupExportName()) },
                onImportBackup = { databaseBackupImportLauncher.launch(DATABASE_BACKUP_MIME_TYPES) },
            )

            OpenTaskerScreen.Inspector -> ContextInspectorScreen(db = db, contentPadding = innerPadding)

            OpenTaskerScreen.RunLog -> RunLogScreenContent(
                logs = runLogs,
                tasks = tasks,
                retentionPolicy = runLogRetentionPolicy,
                onRetentionPolicyChange = viewModel::updateRunLogRetention,
                contentPadding = innerPadding,
            )
        }
    }

    moveTarget?.let { target ->
        ProjectPickerDialog(
            title = "Move to project",
            projects = projects,
            currentProjectId = target.currentProjectId,
            onPick = { projectId ->
                when (target) {
                    is MoveTarget.ProfileMove -> viewModel.moveProfileToProject(target.profile, projectId)
                    is MoveTarget.TaskMove -> viewModel.moveTaskToProject(target.task, projectId)
                    is MoveTarget.SceneMove -> viewModel.moveSceneToProject(target.scene, projectId)
                }
                moveTarget = null
            },
            onDismiss = { moveTarget = null },
        )
    }

    exportRequest?.let { req ->
        ExportOptionsDialog(
            request = req,
            onDismiss = { exportRequest = null },
            onExport = { includeVars ->
                val resolved = req.copy(includeVariables = includeVars)
                exportRequest = null
                pendingExportWrite = resolved
                selectiveExportLauncher.launch(resolved.fileName)
            },
        )
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

    openTaskerBundleReview?.let { state ->
        OpenTaskerBundleReviewDialog(
            state = state,
            busy = openTaskerBundleBusy,
            onDismiss = viewModel::clearOpenTaskerBundleReview,
            onConfirm = {
                val hasProjectCollision = state.bundle.projects.any { incoming ->
                    projects.any { it.name.equals(incoming.name, ignoreCase = true) }
                }
                if (hasProjectCollision) {
                    importConflict = state.bundle
                    viewModel.clearOpenTaskerBundleReview()
                } else {
                    viewModel.confirmOpenTaskerBundleImport(state.bundle, ProjectConflictStrategy.RENAME)
                }
            },
        )
    }

    importConflict?.let { bundle ->
        val conflictingNames = bundle.projects
            .filter { incoming -> projects.any { it.name.equals(incoming.name, ignoreCase = true) } }
            .map { it.name }
        ImportProjectConflictDialog(
            conflictingNames = conflictingNames,
            onOverwrite = {
                viewModel.confirmOpenTaskerBundleImport(bundle, ProjectConflictStrategy.MERGE)
                importConflict = null
            },
            onKeepBoth = {
                viewModel.confirmOpenTaskerBundleImport(bundle, ProjectConflictStrategy.RENAME)
                importConflict = null
            },
            onDismiss = { importConflict = null },
        )
    }

    if (showCreateTaskDialog) {
        TaskEditorDialog(
            task = null,
            onDismiss = { showCreateTaskDialog = false },
            onSave = { name, priority ->
                viewModel.createTask(name, priority, currentProjectId)
                showCreateTaskDialog = false
            },
        )
    }

    if (showTaskLibrary) {
        TaskLibraryDialog(tasks = tasks, onDismiss = { showTaskLibrary = false })
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
                viewModel.createProfile(name, enabled, enterTaskId, cooldown, automationMode, currentProjectId)
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
            allTasks = tasks,
            projects = projects,
            currentProjectId = currentProjectId,
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
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    openTaskerBundleBusy: Boolean,
    onImportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
    onEditProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onToggleProfile: (Profile, Boolean) -> Unit,
    onAddContext: (Profile) -> Unit,
    onEditContext: (Profile, Int, ContextSpec) -> Unit,
    onDeleteContext: (Profile, Int) -> Unit,
    onMoveProfile: (Profile) -> Unit,
    onExportProfile: (Profile) -> Unit,
    manualSort: Boolean,
    onReorder: (List<Profile>) -> Unit,
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = "Start with a template or task",
            body = "Import a 白い熊 自由作業盤 JSON bundle, migrate an existing Tasker XML export, start from a guided template, or create a blank task manually.",
            actionLabel = if (openTaskerBundleBusy) "Reading Bundle..." else "Import 白い熊 自由作業盤 JSON",
            onAction = onImportOpenTaskerBundle,
            actionEnabled = !openTaskerBundleBusy,
            secondaryActionLabel = "Browse Templates",
            onSecondaryAction = onBrowseTemplates,
            tertiaryActionLabel = if (taskerImportBusy) "Reading Tasker XML..." else "Import Tasker XML",
            onTertiaryAction = onImportTaskerXml,
            tertiaryActionEnabled = !taskerImportBusy,
            quaternaryActionLabel = "Create Blank Task",
            onQuaternaryAction = onCreateTaskFirst,
            contentPadding = contentPadding,
        )
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            title = "No profiles yet",
            body = "Profiles connect contexts to tasks. Import a 白い熊 自由作業盤 JSON bundle, migrate Tasker XML, start from a curated template, or create a blank profile.",
            actionLabel = if (openTaskerBundleBusy) "Reading Bundle..." else "Import 白い熊 自由作業盤 JSON",
            onAction = onImportOpenTaskerBundle,
            actionEnabled = !openTaskerBundleBusy,
            secondaryActionLabel = "Browse Templates",
            onSecondaryAction = onBrowseTemplates,
            tertiaryActionLabel = if (taskerImportBusy) "Reading Tasker XML..." else "Import Tasker XML",
            onTertiaryAction = onImportTaskerXml,
            tertiaryActionEnabled = !taskerImportBusy,
            quaternaryActionLabel = "Create Blank Profile",
            onQuaternaryAction = onCreateProfile,
            contentPadding = contentPadding,
        )
        return
    }

    val listState = rememberLazyListState()
    val reorder = rememberListReorderState()
    LazyColumn(
        state = listState,
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
                onExportOpenTaskerBundle = onExportOpenTaskerBundle,
                onImportOpenTaskerBundle = onImportOpenTaskerBundle,
                openTaskerBundleBusy = openTaskerBundleBusy,
                onImportTaskerXml = onImportTaskerXml,
                taskerImportBusy = taskerImportBusy,
            )
        }
        item {
            TemplatePromptCard(onBrowseTemplates)
        }
        items(profiles, key = { it.id }) { profile ->
            ReorderableRow(reorder, listState, profiles, profile, { it.id }, manualSort, onReorder) {
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
                    onMove = { onMoveProfile(profile) },
                    onExport = { onExportProfile(profile) },
                )
            }
        }
    }
}

private fun readBoundedTaskerXml(context: Context, uri: Uri): String {
    return readBoundedDocumentText(
        context = context,
        uri = uri,
        maxBytes = TASKER_XML_IMPORT_MAX_BYTES,
        label = "Tasker XML file",
    )
}

private fun readBoundedOpenTaskerBundle(context: Context, uri: Uri): String {
    return readBoundedDocumentText(
        context = context,
        uri = uri,
        maxBytes = OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES,
        label = "白い熊 自由作業盤 bundle",
    )
}

private fun readBoundedDocumentText(context: Context, uri: Uri, maxBytes: Int, label: String): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: error("Unable to open selected $label")
    ByteArrayOutputStream().use { output ->
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                require(totalBytes <= maxBytes) {
                    "$label is larger than ${maxBytes / (1024 * 1024)} MB"
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
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    openTaskerBundleBusy: Boolean,
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onExportOpenTaskerBundle,
                    enabled = !openTaskerBundleBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (openTaskerBundleBusy) "Working" else "Export JSON")
                }
                OutlinedButton(
                    onClick = onImportOpenTaskerBundle,
                    enabled = !openTaskerBundleBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (openTaskerBundleBusy) "Reading JSON" else "Import JSON")
                }
            }
        }
    }
}

/** The Task-library summary — description + counts — surfaced from the Tasks top-bar ⓘ, off the list. */
@Composable
private fun TaskLibraryDialog(tasks: List<Task>, onDismiss: () -> Unit) {
    val totalActions = tasks.sumOf { it.actions.size }
    val emptyTasks = tasks.count { it.actions.isEmpty() }
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Task library") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Build reusable action sequences, then attach them to profiles when the order and permissions are ready.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SummaryMetric("${tasks.size}", "Tasks", Modifier.weight(1f))
                    SummaryMetric("$totalActions", "Actions", Modifier.weight(1f))
                    SummaryMetric("$emptyTasks", "Need actions", Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SummaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
        color = Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color),
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
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
    onMove: () -> Unit,
    onExport: () -> Unit,
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
            if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Runs: $enterTaskName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onExport) {
                    Icon(Icons.Filled.Upload, contentDescription = "Export profile")
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onMove) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Move")
                }
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
    expandedTasks: SnapshotStateMap<Long, Boolean>,
    expandedActions: SnapshotStateMap<String, Boolean>,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onRunTask: (Task) -> Unit,
    onPinTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
    onMoveTask: (Task) -> Unit,
    onExportTask: (Task) -> Unit,
    onReorderAction: (Task, List<ActionSpec>) -> Unit,
    manualSort: Boolean,
    onReorder: (List<Task>) -> Unit,
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
    val listState = rememberLazyListState()
    val reorder = rememberListReorderState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(tasks, key = { it.id }) { task ->
            ReorderableRow(reorder, listState, tasks, task, { it.id }, manualSort, onReorder) {
                TaskCard(
                    task = task,
                    expanded = expandedTasks[task.id] == true,
                    onToggleExpanded = { expandedTasks[task.id] = expandedTasks[task.id] != true },
                    isActionExpanded = { index -> expandedActions["${task.id}:$index"] == true },
                    onToggleAction = { index ->
                        val key = "${task.id}:$index"
                        expandedActions[key] = expandedActions[key] != true
                    },
                    onEdit = { onEditTask(task) },
                    onDelete = { onDeleteTask(task) },
                    onRun = { onRunTask(task) },
                    onPin = { onPinTask(task) },
                    onAddAction = { onAddAction(task) },
                    onEditAction = { index, action -> onEditAction(task, index, action) },
                    onDeleteAction = { index -> onDeleteAction(task, index) },
                    onMove = { onMoveTask(task) },
                    onExport = { onExportTask(task) },
                    onReorderActions = { newOrder -> onReorderAction(task, newOrder) },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    isActionExpanded: (Int) -> Boolean,
    onToggleAction: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onPin: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onReorderActions: (List<ActionSpec>) -> Unit,
) {
    var draggingIndex by remember(task.id) { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember(task.id) { mutableFloatStateOf(0f) }
    var actionRowHeightPx by remember(task.id) { mutableIntStateOf(0) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (expanded) {
                        Text(
                            "Priority ${task.priority} - ${task.collisionMode.name.lowercase().replace('_', ' ')}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (expanded) {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Filled.Upload, contentDescription = "Export task")
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse task" else "Expand task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
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
                        val dragging = draggingIndex == index
                        ActionRow(
                            index = index,
                            action = action,
                            expanded = isActionExpanded(index),
                            onToggle = { onToggleAction(index) },
                            onEdit = { onEditAction(index, action) },
                            onDelete = { onDeleteAction(index) },
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) dragOffsetY else 0f }
                                .onSizeChanged { if (actionRowHeightPx == 0 && it.height > 0) actionRowHeightPx = it.height },
                            dragHandleModifier = Modifier.pointerInput(index, task.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { draggingIndex = index; dragOffsetY = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                    },
                                    onDragEnd = {
                                        val from = index
                                        val shift = if (actionRowHeightPx > 0) (dragOffsetY / actionRowHeightPx).roundToInt() else 0
                                        val to = (from + shift).coerceIn(0, task.actions.lastIndex)
                                        if (to != from) {
                                            val reordered = task.actions.toMutableList().apply { add(to, removeAt(from)) }
                                            onReorderActions(reordered)
                                        }
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffsetY = 0f
                                    },
                                )
                            },
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRun) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Run")
                        }
                        OutlinedButton(onClick = onPin) {
                            Icon(Icons.Filled.PushPin, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Pin")
                        }
                        OutlinedButton(onClick = onMove) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Move")
                        }
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Delete Task")
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    index: Int,
    action: ActionSpec,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
) {
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().animateContentSize(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier,
                )
                StatusPill("#${index + 1}", MaterialTheme.colorScheme.secondary)
                Text(
                    action.label ?: metadata?.name ?: action.type,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capability.level != CapabilityLevel.Supported) {
                    StatusPill(
                        if (capability.level == CapabilityLevel.Unsupported) "Unsupported" else "Needs setup",
                        if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse action" else "Expand action",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    metadata?.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (action.args.isEmpty()) {
                        Text("No arguments configured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        action.args.forEach { (key, value) ->
                            Text("$key = $value", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    action.condition?.takeIf { it.isNotBlank() }?.let {
                        Text("Condition: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (action.continueOnError) {
                        Text("Continues on error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (capability.level != CapabilityLevel.Supported) {
                        Text(capability.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Edit")
                        }
                        TextButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
private fun RunLogScreenContent(
    logs: List<RunLogEntry>,
    tasks: List<Task>,
    retentionPolicy: RunLogRetentionPolicy,
    onRetentionPolicyChange: (RunLogRetentionPolicy) -> Unit,
    contentPadding: PaddingValues,
) {
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
        if (logs.isEmpty()) {
            item {
                InlineNotice(
                    title = "No execution history yet",
                    body = "Run log entries appear here when enabled profiles execute tasks. Current retention: ${retentionPolicy.displayLabel()}.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            item {
                RunLogSummaryCard(logs)
            }
        }
        item {
            RunLogRetentionCard(
                policy = retentionPolicy,
                onPolicyChange = onRetentionPolicyChange,
            )
        }
        if (logs.isNotEmpty()) {
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
        }
        if (logs.isNotEmpty() && filteredLogs.isEmpty()) {
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
private fun RunLogRetentionCard(
    policy: RunLogRetentionPolicy,
    onPolicyChange: (RunLogRetentionPolicy) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Retention", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stored run history is pruned in the background. The Log tab loads the newest 100 entries for fast review.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(RunLogRetentionOptions.all, key = { it.label }) { option ->
                    val selected = option.policy == policy
                    OutlinedButton(
                        onClick = { onPolicyChange(option.policy) },
                        modifier = Modifier
                            .width(220.dp)
                            .animateContentSize(),
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
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (selected) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Text(option.label, style = MaterialTheme.typography.labelLarge)
                            }
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                RunLogOutcome.Succeeded -> MaterialTheme.colorScheme.outlineVariant
                RunLogOutcome.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.30f)
                RunLogOutcome.Skipped -> MaterialTheme.colorScheme.secondary
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
                // Prefer the typed source column (no regex); fall back to the parsed message for legacy rows.
                val sourceText = entry.source?.let { key ->
                    val name = RunLogSource.displayName(key)
                    entry.sourceLabel?.let { "$name: $it" } ?: name
                } ?: diagnostics.source
                sourceText?.let { source ->
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
        ActionTraceStatus.TIMEOUT -> MaterialTheme.colorScheme.error
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
    quaternaryActionLabel: String? = null,
    onQuaternaryAction: (() -> Unit)? = null,
    quaternaryActionEnabled: Boolean = true,
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
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
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
            OutlinedButton(onClick = onAction, enabled = actionEnabled, modifier = Modifier.fillMaxWidth()) {
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
        if (quaternaryActionLabel != null && onQuaternaryAction != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onQuaternaryAction,
                enabled = quaternaryActionEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(quaternaryActionLabel)
            }
        }
    }
}

@Composable
private fun ExportOptionsDialog(
    request: ExportRequest,
    onDismiss: () -> Unit,
    onExport: (Boolean) -> Unit,
) {
    var includeVariables by remember { mutableStateOf(false) }
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.name, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Checkbox(checked = includeVariables, onCheckedChange = { includeVariables = it })
                    Text("Include global variables", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onExport(includeVariables) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportProjectConflictDialog(
    conflictingNames: List<String>,
    onOverwrite: () -> Unit,
    onKeepBoth: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Project already exists") },
        text = {
            Text(
                if (conflictingNames.size == 1) {
                    "A project named \"${conflictingNames.first()}\" already exists. Import into it (file the imported items under the existing project), or create a separate new (renamed) project?"
                } else {
                    "These projects already exist: ${conflictingNames.joinToString()}. Import into them, or create separate new (renamed) projects?"
                },
            )
        },
        confirmButton = { TextButton(onClick = onOverwrite) { Text("Import into") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onKeepBoth) { Text("New project") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun DeleteConfirmationDialog(
    target: DeleteTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
            OutlinedButton(
                onClick = onConfirm,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Text(target.confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OpenTaskerBundleReviewDialog(
    state: OpenTaskerBundleReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bundle = state.bundle
    val plan = state.plan
    val reviewWarnings = (bundle.metadata.warnings + plan.warnings + plan.lossyWarnings).distinct()
    val capabilityRequirements = bundle.metadata.capabilityRequirements
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Review 白い熊 自由作業盤 bundle") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    Text(
                        "Imported profiles will be created disabled so contexts, actions, and permissions can be reviewed before use.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    InlineNotice(
                        title = bundle.metadata.name.ifBlank { "白い熊 自由作業盤 bundle" },
                        body = "Schema ${bundle.schemaVersion} - exported by app ${bundle.appVersion}",
                        color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.tasks.size}", "Tasks", Modifier.weight(1f))
                        SummaryMetric("${bundle.profiles.size}", "Profiles", Modifier.weight(1f))
                        SummaryMetric("${bundle.variables.size}", "Variables", Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        SummaryMetric("${bundle.scenes.size}", "Scenes", Modifier.weight(1f))
                        SummaryMetric("${capabilityRequirements.size}", "Setup notes", Modifier.weight(1f))
                        SummaryMetric("${reviewWarnings.size}", "Warnings", Modifier.weight(1f))
                    }
                }
                if (!plan.canImport) {
                    item {
                        TaskerImportListSection(
                            title = "Cannot import",
                            values = plan.warnings.ifEmpty { listOf("Bundle schema is not compatible with this build.") },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (capabilityRequirements.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Capability review",
                            values = capabilityRequirements.map {
                                "${it.actionId}: ${it.level.name.lowercase().replace('_', ' ')} - ${it.reason}"
                            },
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (reviewWarnings.isNotEmpty()) {
                    item {
                        TaskerImportListSection(
                            title = "Import warnings",
                            values = reviewWarnings,
                            color = if (plan.canImport) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(
                enabled = plan.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) "Importing..." else "Import")
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
private fun TaskerImportReviewDialog(
    state: TaskerImportReviewState,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val preview = state.preview
    val migrationWarnings = (preview.warnings + preview.lossyWarnings).distinct()
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
            OutlinedButton(
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
            OutlinedButton(
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
            OutlinedButton(enabled = canSave, onClick = { onSave(name, parsedPriority ?: 5) }) {
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            OutlinedButton(enabled = canSave, onClick = { onSave(name, enabled, enterTaskId, parsedCooldown ?: 0, automationMode) }) {
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
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
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
    var query by remember { mutableStateOf("") }
    val filteredGroups = remember(query) {
        if (query.isBlank()) {
            actionGroups
        } else {
            actionGroups.mapNotNull { (category, actions) ->
                val matches = actions.filter {
                    it.name.contains(query, ignoreCase = true) ||
                        category.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true)
                }
                if (matches.isEmpty()) null else category to matches
            }
        }
    }
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Add action") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search actions") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filteredGroups.forEach { (category, actions) ->
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
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ActionConfigDialog(
    state: ActionEditState,
    allTasks: List<Task>,
    projects: List<Project>,
    currentProjectId: Long?,
    onDismiss: () -> Unit,
    onSave: (ActionSpec) -> Unit,
) {
    var showTaskPicker by remember { mutableStateOf(false) }
    var label by remember(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.label ?: state.metadata.name)
    }
    var values by remember(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.metadata.fields.associate { field -> field.key to state.existing?.args?.get(field.key).orEmpty() })
    }
    // Run Task takes named parameters (param:<name>); Return Values takes named results (ret:<name>).
    val dynamicPrefix = when (state.metadata.id) {
        SUB_TASK_ACTION_ID -> SUB_TASK_PARAM_PREFIX
        RETURN_VALUES_ACTION_ID -> RETURN_VALUE_PREFIX
        else -> null
    }
    var dynamicPairs by remember(state.existing?.id, state.metadata.id) {
        val initial: List<Pair<String, String>> = dynamicPrefix?.let { prefix ->
            state.existing?.args.orEmpty()
                .filterKeys { it.startsWith(prefix) }
                .map { (key, value) -> key.removePrefix(prefix) to value }
        }.orEmpty()
        mutableStateOf(initial)
    }
    var continueOnError by remember(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.continueOnError ?: false)
    }
    val capability = remember(state.metadata.id) { ActionCapabilityRegistry.get(state.metadata.id) }
    val missingRequired = state.metadata.fields.any { it.required && values[it.key].isNullOrBlank() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                    Text(
                        state.metadata.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    OutlinedButton(
                        enabled = !missingRequired && capability.canAdd,
                        onClick = {
                            val dynamicArgs = if (dynamicPrefix != null) {
                                dynamicPairs.filter { it.first.isNotBlank() }
                                    .associate { (name, value) -> "$dynamicPrefix${name.trim()}" to value }
                            } else {
                                emptyMap()
                            }
                            onSave(
                                ActionSpec(
                                    id = state.existing?.id ?: 0,
                                    type = state.metadata.id,
                                    label = label.trim().ifBlank { state.metadata.name },
                                    args = values.filterValues { it.isNotBlank() } + dynamicArgs,
                                    continueOnError = continueOnError,
                                    condition = state.existing?.condition,
                                )
                            )
                        },
                    ) { Text("Save") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
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
                    if (state.metadata.id == SUB_TASK_ACTION_ID && field.key == "task") {
                        OutlinedTextField(
                            value = values[field.key].orEmpty(),
                            onValueChange = { newValue -> values = values + (field.key to newValue) },
                            label = { Text(field.label + if (field.required) " *" else "") },
                            placeholder = field.hint?.let { { Text(it) } },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { showTaskPicker = true }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Pick task")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ActionFieldInput(
                            field = field,
                            value = values[field.key].orEmpty(),
                            onChange = { newValue -> values = values + (field.key to newValue) },
                        )
                    }
                }
                if (dynamicPrefix != null) {
                    item {
                        DynamicArgsEditor(
                            title = if (state.metadata.id == RETURN_VALUES_ACTION_ID) "Return values" else "Parameters",
                            addLabel = if (state.metadata.id == RETURN_VALUES_ACTION_ID) "Add return value" else "Add parameter",
                            pairs = dynamicPairs,
                            onChange = { dynamicPairs = it },
                        )
                    }
                }
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Continue on error", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "If this action fails, run the rest of the task anyway (e.g. to branch on a Run Task's %ok/%error).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = continueOnError, onCheckedChange = { continueOnError = it })
                        }
                    }
                }
                }
            }
        }
    }

    if (showTaskPicker) {
        TaskPickerDialog(
            allTasks = allTasks,
            projects = projects,
            currentProjectId = currentProjectId,
            onPick = { task ->
                values = values + ("task" to task.name)
                showTaskPicker = false
            },
            onDismiss = { showTaskPicker = false },
        )
    }
}

@Composable
private fun TaskPickerDialog(
    allTasks: List<Task>,
    projects: List<Project>,
    currentProjectId: Long?,
    onPick: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    val tasksByProject = remember(allTasks) { allTasks.groupBy { it.projectId } }
    val expandedProjects = remember { mutableStateMapOf<Long, Boolean>() }
    var unfiledExpanded by remember { mutableStateOf(currentProjectId == null) }
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Pick a task") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                projects.forEach { project ->
                    val tasksHere = tasksByProject[project.id].orEmpty().sortedBy { it.name.lowercase() }
                    val expanded = expandedProjects[project.id] ?: (project.id == currentProjectId)
                    TaskPickerGroupHeader(project.name, tasksHere.size, expanded) {
                        expandedProjects[project.id] = !expanded
                    }
                    if (expanded) tasksHere.forEach { task -> TaskPickerRow(task.name) { onPick(task) } }
                }
                val unfiled = tasksByProject[null].orEmpty().sortedBy { it.name.lowercase() }
                if (unfiled.isNotEmpty() || projects.isEmpty()) {
                    TaskPickerGroupHeader("Unfiled", unfiled.size, unfiledExpanded) { unfiledExpanded = !unfiledExpanded }
                    if (unfiledExpanded) unfiled.forEach { task -> TaskPickerRow(task.name) { onPick(task) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TaskPickerGroupHeader(name: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskPickerRow(name: String, onClick: () -> Unit) {
    Text(
        name,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 32.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun DynamicArgsEditor(
    title: String,
    addLabel: String,
    pairs: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        pairs.forEachIndexed { index, (name, value) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { newName -> onChange(pairs.mapIndexed { i, p -> if (i == index) newName to p.second else p }) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { newValue -> onChange(pairs.mapIndexed { i, p -> if (i == index) p.first to newValue else p }) },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.weight(1.3f),
                )
                IconButton(onClick = { onChange(pairs.filterIndexed { i, _ -> i != index }) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
        OutlinedButton(onClick = { onChange(pairs + ("" to "")) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(addLabel)
        }
    }
}

@Composable
private fun ActionFieldInput(field: ActionField, value: String, onChange: (String) -> Unit) {
    val label = field.label + if (field.required) " *" else ""
    when (field.fieldType) {
        FieldType.CHECKBOX -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

        FieldType.COLOR -> {
            var showPicker by remember { mutableStateOf(false) }
            val parsed = remember(value) {
                runCatching { if (value.isBlank()) null else android.graphics.Color.parseColor(value) }.getOrNull()
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (parsed == null) "Default" else value.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (parsed == null) Color.Transparent else Color(parsed))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            if (showPicker) {
                RgbaColorPickerDialog(
                    initial = value,
                    onConfirm = { onChange(it); showPicker = false },
                    onClear = { onChange(""); showPicker = false },
                    onDismiss = { showPicker = false },
                )
            }
        }

        FieldType.WIDGET_LAYOUT -> {
            var editing by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (value.isBlank()) "Design layout (visual editor)" else "Edit layout visually")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text("Layout JSON (advanced)") },
                    placeholder = field.hint?.let { { Text(it) } },
                    supportingText = if (field.required) {{ Text("Required") }} else null,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (editing) {
                Dialog(
                    onDismissRequest = { editing = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    WidgetEditor(
                        initialJson = value,
                        onDone = { onChange(it); editing = false },
                        onCancel = { editing = false },
                    )
                }
            }
        }

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
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ContextTypePickerDialog(onDismiss: () -> Unit, onSelect: (ContextType) -> Unit) {
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Add context") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContextType.entries.forEach { type ->
                    Card(
                        onClick = { onSelect(type) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            OutlinedButton(
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
        ActionField("event", "Event type", required = true, hint = "minute, boot_completed, notification, nfc, bluetooth, calendar, sunrise, sunset, shake, package_added, package_removed, package_replaced"),
        ActionField("everyMinutes", "Every N minutes", FieldType.NUMBER, hint = "for event=minute; blank/1 = every minute"),
        ActionField("state", "Event state", hint = "during, upcoming, connected, disconnected"),
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
        "白い熊 自由作業盤 NFC trigger"
    } else {
        "白い熊 自由作業盤 NFC trigger $tagId"
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
    ContextType.EVENT -> "Matches a one-shot event such as boot, notification, NFC, Bluetooth connect/disconnect, calendar, sun, shake, or Locale plugin queries."
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
