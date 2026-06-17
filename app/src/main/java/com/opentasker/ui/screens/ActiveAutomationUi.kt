package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.opentasker.app.BuildConfig
import com.opentasker.core.actions.ActionField
import com.opentasker.core.diagnostics.DiagnosticExport
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
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.widget.TaskShortcutHelper
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
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.displayLabel
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.toEntity
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
private const val NO_DIALOG_ENTITY_ID = 0L
private const val NO_DIALOG_INDEX = -1
private const val DELETE_TARGET_PROFILE = "profile"
private const val DELETE_TARGET_TASK = "task"
private const val DELETE_TARGET_SCENE = "scene"
private const val DELETE_TARGET_ACTION = "action"
private const val DELETE_TARGET_CONTEXT = "context"

private fun databaseBackupExportName(): String =
    "opentasker_backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.db"

private fun openTaskerBundleExportName(): String =
    "opentasker_bundle_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.json"

private enum class OpenTaskerScreen(val label: String) {
    Profiles("Profiles"),
    Tasks("Tasks"),
    Vars("Variables"),
    Flow("Flow"),
    Scenes("Scenes"),
    Inspector("Inspector"),
    Setup("Setup"),
    RunLog("Run Log"),
}

private val primaryNavigationScreens = listOf(
    OpenTaskerScreen.Profiles,
    OpenTaskerScreen.Tasks,
    OpenTaskerScreen.Setup,
    OpenTaskerScreen.RunLog,
)

private val secondaryNavigationScreens = OpenTaskerScreen.entries.filterNot { it in primaryNavigationScreens }

private fun OpenTaskerScreen.icon(): ImageVector = when (this) {
    OpenTaskerScreen.Profiles -> Icons.Filled.CheckCircle
    OpenTaskerScreen.Tasks -> Icons.Filled.Edit
    OpenTaskerScreen.Vars -> Icons.Filled.Menu
    OpenTaskerScreen.Flow -> Icons.Filled.Info
    OpenTaskerScreen.Scenes -> Icons.Filled.Edit
    OpenTaskerScreen.Inspector -> Icons.Filled.Info
    OpenTaskerScreen.Setup -> Icons.Filled.Settings
    OpenTaskerScreen.RunLog -> Icons.Filled.Info
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

    private val profileDecodeResults = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val taskDecodeResults = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<List<Profile>> = profileDecodeResults
        .map { results -> results.map { it.value }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<Task>> = taskDecodeResults
        .map { results -> results.map { it.value }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storageDecodeIssues: StateFlow<List<StorageDecodeIssue>> = combine(
        profileDecodeResults,
        taskDecodeResults,
    ) { profileResults, taskResults ->
        (profileResults.mapNotNull { it.issue } + taskResults.mapNotNull { it.issue })
            .sortedWith(compareBy<StorageDecodeIssue> { it.recordType.label }.thenBy { it.recordName.lowercase() })
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scenes: StateFlow<List<Scene>> = db.sceneDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.sortedBy { it.name.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val runLogs: StateFlow<List<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val globalVariables: StateFlow<List<Variable>> = db.variableDao()
        .getAllGlobalAsFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val events = Channel<String>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    private val _runLogRetentionPolicy = MutableStateFlow(runLogRetentionSettings.load())
    val runLogRetentionPolicy: StateFlow<RunLogRetentionPolicy> = _runLogRetentionPolicy.asStateFlow()

    private val _backupSetupState = MutableStateFlow(loadBackupSetupState(busy = false))
    val backupSetupState: StateFlow<BackupSetupState> = _backupSetupState.asStateFlow()

    private val _taskerImportReview = MutableStateFlow<TaskerImportReviewState?>(null)
    internal val taskerImportReview: StateFlow<TaskerImportReviewState?> = _taskerImportReview.asStateFlow()

    private val _taskerImportBusy = MutableStateFlow(false)
    val taskerImportBusy: StateFlow<Boolean> = _taskerImportBusy.asStateFlow()

    private val _openTaskerBundleReview = MutableStateFlow<OpenTaskerBundleReviewState?>(null)
    internal val openTaskerBundleReview: StateFlow<OpenTaskerBundleReviewState?> = _openTaskerBundleReview.asStateFlow()

    private val _openTaskerBundleBusy = MutableStateFlow(false)
    val openTaskerBundleBusy: StateFlow<Boolean> = _openTaskerBundleBusy.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { pruneRunLogs(_runLogRetentionPolicy.value) }
        }
    }

    fun createTask(name: String, priority: Int) = launchWithMessage("Task created") {
        db.taskDao().insert(Task(name = name.trim(), priority = priority.coerceIn(0, 10)).toEntity())
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

    fun createProfile(name: String, enabled: Boolean, enterTaskId: Long, cooldownSec: Int, automationMode: AutomationMode, group: String? = null) =
        launchWithMessage("Profile created") {
            db.profileDao().insert(
                Profile(
                    name = name.trim(),
                    enabled = enabled,
                    enterTaskId = enterTaskId,
                    cooldownSec = cooldownSec.coerceAtLeast(0),
                    automationMode = automationMode,
                    group = group,
                ).toEntity()
            )
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
            if (_taskerImportBusy.value) return@launch
            _taskerImportBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val rawXml = readBoundedTaskerXml(appContext, uri)
                    val report = TaskerXmlImporter.parse(rawXml = rawXml, appVersion = appVersion)
                    TaskerImportReviewState(report = report, preview = TaskerImportPlanner.preview(report))
                }
            }
                .onSuccess {
                    _taskerImportReview.value = it
                    events.send("Tasker XML ready for review")
                }
                .onFailure { events.send("Error: ${it.message ?: "Tasker XML import preview failed"}") }
            _taskerImportBusy.value = false
        }
    }

    fun clearTaskerImportReview() {
        if (!_taskerImportBusy.value) {
            _taskerImportReview.value = null
        }
    }

    fun confirmTaskerImport(report: TaskerXmlImportReport) {
        viewModelScope.launch {
            if (_taskerImportBusy.value) return@launch
            _taskerImportBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bundleRepository.importBundle(TaskerImportPlanner.confirmedBundle(report))
                }
            }
                .onSuccess { importReport ->
                    _taskerImportReview.value = null
                    events.send(
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}"
                    )
                }
                .onFailure { events.send("Error: ${it.message ?: "Tasker XML import failed"}") }
            _taskerImportBusy.value = false
        }
    }

    fun exportOpenTaskerBundle(uri: Uri, appVersion: String) {
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = bundleRepository.exportBundle(
                        appVersion = appVersion,
                        name = "OpenTasker Workspace Export",
                        description = "Profiles, tasks, variables, and scenes exported from OpenTasker.",
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
                .onFailure { events.send("Error: ${it.message ?: "OpenTasker bundle export failed"}") }
            _openTaskerBundleBusy.value = false
        }
    }

    fun previewOpenTaskerBundle(uri: Uri) {
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val rawJson = readBoundedOpenTaskerBundle(appContext, uri)
                    val bundle = OpenTaskerBundleCodec.decode(rawJson)
                    OpenTaskerBundleReviewState(bundle = bundle, plan = OpenTaskerBundleCodec.validate(bundle))
                }
            }
                .onSuccess {
                    _openTaskerBundleReview.value = it
                    events.send("OpenTasker bundle ready for review")
                }
                .onFailure { events.send("Error: ${it.message ?: "OpenTasker bundle preview failed"}") }
            _openTaskerBundleBusy.value = false
        }
    }

    fun clearOpenTaskerBundleReview() {
        if (!_openTaskerBundleBusy.value) {
            _openTaskerBundleReview.value = null
        }
    }

    fun confirmOpenTaskerBundleImport(bundle: OpenTaskerBundle) {
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bundleRepository.importBundle(bundle)
                }
            }
                .onSuccess { importReport ->
                    _openTaskerBundleReview.value = null
                    events.send(
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}, " +
                            "${importReport.insertedScenes} scene${plural(importReport.insertedScenes)}"
                    )
                }
                .onFailure { events.send("Error: ${it.message ?: "OpenTasker bundle import failed"}") }
            _openTaskerBundleBusy.value = false
        }
    }

    fun updateRunLogRetention(policy: RunLogRetentionPolicy) {
        viewModelScope.launch {
            val normalized = policy.normalized()
            runCatching {
                runLogRetentionSettings.save(normalized)
                _runLogRetentionPolicy.value = normalized
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

    fun shareDiagnosticReport() {
        viewModelScope.launch {
            try {
                val report = DiagnosticExport.buildReport(appContext, db)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OpenTasker Diagnostic Report")
                    putExtra(Intent.EXTRA_TEXT, report)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(Intent.createChooser(intent, "Share diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ex: Exception) {
                events.send("Error: ${ex.message ?: "Failed to share diagnostic report"}")
            }
        }
    }

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
                .onSuccess { events.send("Backup imported. Restart OpenTasker to apply the restore.") }
                .onFailure { events.send("Error: ${it.message ?: "Database backup import failed"}") }
            setBackupBusy(false)
        }
    }

    private fun setBackupBusy(busy: Boolean) {
        _backupSetupState.value = loadBackupSetupState(busy)
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

    fun updateVariable(name: String, value: String) {
        viewModelScope.launch {
            db.variableDao().insert(VariableEntity(name, value, isGlobal = true))
        }
    }

    fun deleteVariable(name: String) {
        viewModelScope.launch {
            db.variableDao().delete(VariableEntity(name, "", isGlobal = true))
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
    val globalVariables by viewModel.globalVariables.collectAsState()
    val runLogRetentionPolicy by viewModel.runLogRetentionPolicy.collectAsState()
    val backupSetupState by viewModel.backupSetupState.collectAsState()
    val storageDecodeIssues by viewModel.storageDecodeIssues.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var screenOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val screen = OpenTaskerScreen.entries.getOrElse(screenOrdinal) { OpenTaskerScreen.Profiles }
    var taskDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateTaskDialog by rememberSaveable { mutableStateOf(false) }
    var profileDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionPickerTaskId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var actionEditTaskId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var actionEditActionId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionEditIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var contextPickerProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var contextEditProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var contextEditTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var contextEditIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var pendingDeleteKind by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteOwnerId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var pendingDeleteIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    val taskerImportReview by viewModel.taskerImportReview.collectAsState()
    val taskerImportBusy by viewModel.taskerImportBusy.collectAsState()
    val openTaskerBundleReview by viewModel.openTaskerBundleReview.collectAsState()
    val openTaskerBundleBusy by viewModel.openTaskerBundleBusy.collectAsState()
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
    val databaseBackupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.exportDatabaseBackup(it) }
    }
    val databaseBackupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDatabaseBackup(it) }
    }
    val taskDialog = taskDialogId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { taskId -> tasks.firstOrNull { it.id == taskId } }
    val profileDialog = profileDialogId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }
    val selectedTemplate = selectedTemplateId
        ?.let { templateId -> ProfileTemplateCatalog.all.firstOrNull { it.id == templateId } }
    val actionPickerTask = actionPickerTaskId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { taskId -> tasks.firstOrNull { it.id == taskId } }
    val actionEdit = actionEditTaskId.takeIf { it != NO_DIALOG_ENTITY_ID }?.let { taskId ->
        val task = tasks.firstOrNull { it.id == taskId } ?: return@let null
        val actionId = actionEditActionId ?: return@let null
        val metadata = ActionMetadataRegistry.get(actionId) ?: return@let null
        val index = actionEditIndex.takeIf { it != NO_DIALOG_INDEX }
        val existing = index?.let { task.actions.getOrNull(it) }?.takeIf { it.type == actionId }
        if (index != null && existing == null) {
            null
        } else {
            ActionEditState(task = task, metadata = metadata, index = index, existing = existing)
        }
    }
    val contextPickerProfile = contextPickerProfileId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }
    val contextEdit = contextEditProfileId.takeIf { it != NO_DIALOG_ENTITY_ID }?.let { profileId ->
        val profile = profiles.firstOrNull { it.id == profileId } ?: return@let null
        val type = contextEditTypeName
            ?.let { typeName -> runCatching { ContextType.valueOf(typeName) }.getOrNull() }
            ?: return@let null
        val index = contextEditIndex.takeIf { it != NO_DIALOG_INDEX }
        val existing = index?.let { profile.contexts.getOrNull(it) }?.takeIf { it.type == type }
        if (index != null && existing == null) {
            null
        } else {
            ContextEditState(profile = profile, type = type, index = index, existing = existing)
        }
    }
    val pendingDelete = when (pendingDeleteKind) {
        DELETE_TARGET_PROFILE -> profiles.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { DeleteTarget.ProfileTarget(it) }
        DELETE_TARGET_TASK -> tasks.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { DeleteTarget.TaskTarget(it) }
        DELETE_TARGET_SCENE -> scenes.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { DeleteTarget.SceneTarget(it) }
        DELETE_TARGET_ACTION -> tasks.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { task -> task.actions.getOrNull(pendingDeleteIndex)?.let { DeleteTarget.ActionTarget(task, pendingDeleteIndex, it) } }
        DELETE_TARGET_CONTEXT -> profiles.firstOrNull { it.id == pendingDeleteOwnerId }
            ?.let { profile -> profile.contexts.getOrNull(pendingDeleteIndex)?.let { DeleteTarget.ContextTarget(profile, pendingDeleteIndex, it) } }
        else -> null
    }
    fun clearPendingDelete() {
        pendingDeleteKind = null
        pendingDeleteOwnerId = NO_DIALOG_ENTITY_ID
        pendingDeleteIndex = NO_DIALOG_INDEX
    }
    fun openTaskDialog(task: Task) {
        taskDialogId = task.id
    }
    fun clearTaskDialog() {
        taskDialogId = NO_DIALOG_ENTITY_ID
    }
    fun openProfileDialog(profile: Profile) {
        profileDialogId = profile.id
    }
    fun clearProfileDialog() {
        profileDialogId = NO_DIALOG_ENTITY_ID
    }
    fun openActionPicker(task: Task) {
        actionPickerTaskId = task.id
    }
    fun clearActionPicker() {
        actionPickerTaskId = NO_DIALOG_ENTITY_ID
    }
    fun openActionEdit(task: Task, metadata: ActionMetadata, index: Int? = null) {
        actionEditTaskId = task.id
        actionEditActionId = metadata.id
        actionEditIndex = index ?: NO_DIALOG_INDEX
    }
    fun clearActionEdit() {
        actionEditTaskId = NO_DIALOG_ENTITY_ID
        actionEditActionId = null
        actionEditIndex = NO_DIALOG_INDEX
    }
    fun openContextPicker(profile: Profile) {
        contextPickerProfileId = profile.id
    }
    fun clearContextPicker() {
        contextPickerProfileId = NO_DIALOG_ENTITY_ID
    }
    fun openContextEdit(profile: Profile, type: ContextType, index: Int? = null) {
        contextEditProfileId = profile.id
        contextEditTypeName = type.name
        contextEditIndex = index ?: NO_DIALOG_INDEX
    }
    fun clearContextEdit() {
        contextEditProfileId = NO_DIALOG_ENTITY_ID
        contextEditTypeName = null
        contextEditIndex = NO_DIALOG_INDEX
    }
    fun openDeleteProfile(profile: Profile) {
        pendingDeleteKind = DELETE_TARGET_PROFILE
        pendingDeleteOwnerId = profile.id
        pendingDeleteIndex = NO_DIALOG_INDEX
    }
    fun openDeleteTask(task: Task) {
        pendingDeleteKind = DELETE_TARGET_TASK
        pendingDeleteOwnerId = task.id
        pendingDeleteIndex = NO_DIALOG_INDEX
    }
    fun openDeleteScene(scene: Scene) {
        pendingDeleteKind = DELETE_TARGET_SCENE
        pendingDeleteOwnerId = scene.id
        pendingDeleteIndex = NO_DIALOG_INDEX
    }
    fun openDeleteAction(task: Task, index: Int) {
        pendingDeleteKind = DELETE_TARGET_ACTION
        pendingDeleteOwnerId = task.id
        pendingDeleteIndex = index
    }
    fun openDeleteContext(profile: Profile, index: Int) {
        pendingDeleteKind = DELETE_TARGET_CONTEXT
        pendingDeleteOwnerId = profile.id
        pendingDeleteIndex = index
    }
    val openFlowTarget: (AutomationFlowTarget) -> Unit = { target ->
        var opened = true
        when (target) {
            is AutomationFlowTarget.Profile -> {
                profiles.firstOrNull { it.id == target.profileId }?.let { profile ->
                    screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                    openProfileDialog(profile)
                } ?: run { opened = false }
            }

            is AutomationFlowTarget.Context -> {
                val profile = profiles.firstOrNull { it.id == target.profileId }
                val contextSpec = profile?.contexts?.getOrNull(target.index)
                if (profile != null && contextSpec != null) {
                    screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                    openContextEdit(profile, contextSpec.type, target.index)
                } else {
                    opened = false
                }
            }

            is AutomationFlowTarget.Task -> {
                tasks.firstOrNull { it.id == target.taskId }?.let { task ->
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    openTaskDialog(task)
                } ?: run { opened = false }
            }

            is AutomationFlowTarget.Action -> {
                val task = tasks.firstOrNull { it.id == target.taskId }
                val action = task?.actions?.getOrNull(target.index)
                val metadata = action?.let { ActionMetadataRegistry.get(it.type) }
                if (task != null && action != null && metadata != null) {
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    openActionEdit(task, metadata, target.index)
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

    var showMoreDestinations by rememberSaveable { mutableStateOf(false) }
    val headerDetail = when (screen) {
        OpenTaskerScreen.Profiles -> "${profiles.count { it.enabled }} enabled - ${profiles.size} total"
        OpenTaskerScreen.Tasks -> "${tasks.sumOf { it.actions.size }} actions - ${tasks.size} tasks"
        OpenTaskerScreen.Vars -> "${globalVariables.size} global variables"
        OpenTaskerScreen.Flow -> "${profiles.size} profiles - ${tasks.size} tasks"
        OpenTaskerScreen.Scenes -> "${scenes.sumOf { it.elements.size }} elements - ${scenes.size} scenes"
        OpenTaskerScreen.Inspector -> "Live context health"
        OpenTaskerScreen.Setup -> "Permission and reliability checks"
        OpenTaskerScreen.RunLog -> "${runLogs.size} recent entries"
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
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
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = if (tasks.isEmpty()) "Create task" else "Create profile")
                }

                OpenTaskerScreen.Tasks -> FloatingActionButton(
                    onClick = { showCreateTaskDialog = true },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create task")
                }

                OpenTaskerScreen.Vars,
                OpenTaskerScreen.Flow,
                OpenTaskerScreen.Scenes,
                OpenTaskerScreen.Inspector,
                OpenTaskerScreen.Setup,
                OpenTaskerScreen.RunLog -> Unit
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 0.dp,
            ) {
                primaryNavigationScreens.forEach { destination ->
                    OpenTaskerNavigationItem(
                        selected = screen == destination,
                        onClick = {
                            screenOrdinal = destination.ordinal
                            showMoreDestinations = false
                        },
                        icon = destination.icon(),
                        label = destination.label,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.weight(1f)) {
                    OpenTaskerNavigationItem(
                        selected = screen in secondaryNavigationScreens,
                        onClick = { showMoreDestinations = true },
                        icon = Icons.Filled.Menu,
                        label = "More",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = showMoreDestinations,
                        onDismissRequest = { showMoreDestinations = false },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        secondaryNavigationScreens.forEach { destination ->
                            DropdownMenuItem(
                                text = { Text(destination.label) },
                                leadingIcon = { Icon(destination.icon(), contentDescription = destination.label) },
                                onClick = {
                                    screenOrdinal = destination.ordinal
                                    showMoreDestinations = false
                                },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when (screen) {
            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = profiles,
                tasks = tasks,
                runLogs = runLogs,
                storageDecodeIssues = storageDecodeIssues,
                onCreateTaskFirst = {
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    showCreateTaskDialog = true
                },
                onCreateProfile = { showCreateProfileDialog = true },
                onBrowseTemplates = { showTemplateDialog = true },
                onExportOpenTaskerBundle = { openTaskerBundleExportLauncher.launch(openTaskerBundleExportName()) },
                onImportOpenTaskerBundle = { openTaskerBundleImportLauncher.launch(OPEN_TASKER_BUNDLE_MIME_TYPES) },
                openTaskerBundleBusy = openTaskerBundleBusy,
                onImportTaskerXml = { taskerXmlLauncher.launch(TASKER_XML_MIME_TYPES) },
                taskerImportBusy = taskerImportBusy,
                onEditProfile = { openProfileDialog(it) },
                onDeleteProfile = { openDeleteProfile(it) },
                onToggleProfile = { profile, enabled ->
                    viewModel.updateProfile(profile.copy(enabled = enabled), "Profile ${if (enabled) "enabled" else "disabled"}")
                },
                onAddContext = { openContextPicker(it) },
                onEditContext = { profile, index, context ->
                    openContextEdit(profile, context.type, index)
                },
                onDeleteContext = { profile, index ->
                    if (profile.contexts.getOrNull(index) != null) openDeleteContext(profile, index)
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = tasks,
                storageDecodeIssues = storageDecodeIssues,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { openTaskDialog(it) },
                onDeleteTask = { openDeleteTask(it) },
                onRunTask = { viewModel.runTaskNow(it) },
                onPinTask = { viewModel.pinTaskShortcut(it) },
                onAddAction = { openActionPicker(it) },
                onEditAction = { task, index, action ->
                    ActionMetadataRegistry.get(action.type)?.let { metadata ->
                        openActionEdit(task, metadata, index)
                    }
                },
                onDeleteAction = { task, index ->
                    if (task.actions.getOrNull(index) != null) openDeleteAction(task, index)
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
                        screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                        openContextPicker(profile)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar("Flow target no longer exists") }
                    }
                },
                onAddAction = { taskId ->
                    val task = tasks.firstOrNull { it.id == taskId }
                    if (task != null) {
                        screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                        openActionPicker(task)
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
                scenes = scenes,
                tasks = tasks,
                onCreateScene = viewModel::createScene,
                onUpdateScene = viewModel::updateScene,
                onDeleteScene = { openDeleteScene(it) },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Setup -> PermissionOnboardingScreen(
                contentPadding = innerPadding,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
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
                onShareDiagnostic = viewModel::shareDiagnosticReport,
                contentPadding = innerPadding,
            )
        }
    }

    pendingDelete?.let { target ->
        DeleteConfirmationDialog(
            target = target,
            onDismiss = { clearPendingDelete() },
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
                clearPendingDelete()
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
            onConfirm = { viewModel.confirmOpenTaskerBundleImport(state.bundle) },
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
            onDismiss = { clearTaskDialog() },
            onSave = { name, priority ->
                viewModel.updateTask(task.copy(name = name.trim(), priority = priority.coerceIn(0, 10)))
                clearTaskDialog()
            },
        )
    }

    if (showCreateProfileDialog) {
        ProfileEditorDialog(
            profile = null,
            tasks = tasks,
            onDismiss = { showCreateProfileDialog = false },
            onSave = { name, enabled, enterTaskId, cooldown, automationMode, group ->
                viewModel.createProfile(name, enabled, enterTaskId, cooldown, automationMode, group)
                showCreateProfileDialog = false
            },
        )
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            onDismiss = { showTemplateDialog = false },
            onSelect = { template ->
                showTemplateDialog = false
                selectedTemplateId = template.id
            },
        )
    }

    selectedTemplate?.let { template ->
        TemplateSlotDialog(
            template = template,
            onDismiss = { selectedTemplateId = null },
            onInstall = { values ->
                viewModel.installProfileTemplate(template, values)
                selectedTemplateId = null
                screenOrdinal = OpenTaskerScreen.Profiles.ordinal
            },
        )
    }

    profileDialog?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            tasks = tasks,
            onDismiss = { clearProfileDialog() },
            onSave = { name, enabled, enterTaskId, cooldown, automationMode, group ->
                viewModel.updateProfile(
                    profile.copy(
                        name = name.trim(),
                        enabled = enabled,
                        enterTaskId = enterTaskId,
                        cooldownSec = cooldown.coerceAtLeast(0),
                        automationMode = automationMode,
                        group = group,
                    )
                )
                clearProfileDialog()
            },
        )
    }

    actionPickerTask?.let { task ->
        ActionPickerDialog(
            onDismiss = { clearActionPicker() },
            onSelect = { metadata ->
                clearActionPicker()
                openActionEdit(task, metadata)
            },
        )
    }

    actionEdit?.let { state ->
        ActionConfigDialog(
            state = state,
            onDismiss = { clearActionEdit() },
            onSave = { action ->
                val updatedActions = state.index?.let { index ->
                    state.task.actions.mapIndexed { i, existing -> if (i == index) action else existing }
                } ?: (state.task.actions + action)
                viewModel.updateTask(state.task.copy(actions = updatedActions), if (state.index == null) "Action added" else "Action updated")
                clearActionEdit()
            },
        )
    }

    contextPickerProfile?.let { profile ->
        ContextTypePickerDialog(
            onDismiss = { clearContextPicker() },
            onSelect = { type ->
                clearContextPicker()
                openContextEdit(profile, type)
            },
        )
    }

    contextEdit?.let { state ->
        ContextConfigDialog(
            state = state,
            onDismiss = { clearContextEdit() },
            onSave = { context ->
                val updatedContexts = state.index?.let { index ->
                    state.profile.contexts.mapIndexed { i, existing -> if (i == index) context else existing }
                } ?: (state.profile.contexts + context)
                viewModel.updateProfile(
                    state.profile.copy(contexts = updatedContexts),
                    if (state.index == null) "Context added" else "Context updated",
                )
                clearContextEdit()
            },
        )
    }
}

@Composable
private fun OpenTaskerNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f) else Color.Transparent,
            shape = RoundedCornerShape(6.dp),
            border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)) else null,
        ) {
            Box(
                modifier = Modifier.size(width = 48.dp, height = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfilesScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    runLogs: List<RunLogEntry>,
    storageDecodeIssues: List<StorageDecodeIssue>,
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
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = "Start with a template or task",
            body = "Import an OpenTasker JSON bundle, migrate an existing Tasker XML export, start from a guided template, or create a blank task manually.",
            actionLabel = if (openTaskerBundleBusy) "Reading Bundle..." else "Import OpenTasker JSON",
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
            body = "Profiles connect contexts to tasks. Import an OpenTasker JSON bundle, migrate Tasker XML, start from a curated template, or create a blank profile.",
            actionLabel = if (openTaskerBundleBusy) "Reading Bundle..." else "Import OpenTasker JSON",
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

    var profileSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(profiles) {
        profiles.mapNotNull { it.group }.distinct().sorted()
    }
    val filteredProfiles = profiles
        .filter { selectedGroup == null || it.group == selectedGroup }
        .filter { profileSearchQuery.isBlank() || it.name.contains(profileSearchQuery, ignoreCase = true) }

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
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        item {
            OutlinedTextField(
                value = profileSearchQuery,
                onValueChange = { profileSearchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search profiles...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = if (profileSearchQuery.isNotEmpty()) {
                    { IconButton(onClick = { profileSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (groups.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { selectedGroup = null },
                            label = { Text("All") },
                        )
                    }
                    items(groups, key = { it }) { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = if (selectedGroup == group) null else group },
                            label = { Text(group) },
                        )
                    }
                }
            }
        }
        if (filteredProfiles.isEmpty()) {
            item {
                InlineNotice(
                    title = "No matching profiles",
                    body = "Clear search or switch groups to see more automations.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredProfiles, key = { it.id }) { profile ->
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
        label = "OpenTasker bundle",
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
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

@Composable
private fun TaskLibrarySummaryCard(tasks: List<Task>, onCreateTask: () -> Unit) {
    val totalActions = tasks.sumOf { it.actions.size }
    val emptyTasks = tasks.count { it.actions.isEmpty() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
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
                    Icon(Icons.Filled.Add, contentDescription = "Add task")
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
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                contentDescription = if (color == MaterialTheme.colorScheme.error) "Error" else "Info",
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
private fun StorageDecodeWarningCard(issues: List<StorageDecodeIssue>) {
    val issueSummary = issues.take(3).joinToString(separator = "; ") { issue ->
        "${issue.recordType.label} \"${issue.recordName}\" #${issue.recordId}: ${issue.fieldName}"
    }
    val remaining = issues.size - 3
    val suffix = if (remaining > 0) "; $remaining more" else ""
    InlineNotice(
        title = "Stored data needs review",
        body = "OpenTasker loaded affected records with safe fallbacks. $issueSummary$suffix.",
        color = MaterialTheme.colorScheme.error,
    )
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    StatusPill(
                        label = if (profile.enabled) "Enabled" else "Paused",
                        color = if (profile.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { StatusPill("${profile.contexts.size} context${plural(profile.contexts.size)}", MaterialTheme.colorScheme.primary) }
                item { StatusPill("${profile.cooldownSec}s cooldown", MaterialTheme.colorScheme.secondary) }
                item { StatusPill(profile.automationMode.name.lowercase(), MaterialTheme.colorScheme.onSurfaceVariant) }
                profile.group?.let { group ->
                    item { StatusPill(group, MaterialTheme.colorScheme.inversePrimary) }
                }
            }
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
                    Icon(Icons.Filled.Edit, contentDescription = "Edit profile")
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onAddContext, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add context")
                    Spacer(Modifier.width(6.dp))
                    Text("Add Context")
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete profile")
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
    storageDecodeIssues: List<StorageDecodeIssue>,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onRunTask: (Task) -> Unit,
    onPinTask: (Task) -> Unit,
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
    var taskSearchQuery by rememberSaveable { mutableStateOf("") }
    val filteredTasks = if (taskSearchQuery.isBlank()) tasks
        else tasks.filter { it.name.contains(taskSearchQuery, ignoreCase = true) }

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
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        item {
            OutlinedTextField(
                value = taskSearchQuery,
                onValueChange = { taskSearchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search tasks...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = if (taskSearchQuery.isNotEmpty()) {
                    { IconButton(onClick = { taskSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
        }
        if (filteredTasks.isEmpty()) {
            item {
                InlineNotice(
                    title = "No matching tasks",
                    body = "Clear search to return to the full task library.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredTasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onEdit = { onEditTask(task) },
                onDelete = { onDeleteTask(task) },
                onRun = { onRunTask(task) },
                onPin = { onPinTask(task) },
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
    onRun: () -> Unit,
    onPin: () -> Unit,
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item { StatusPill("${task.actions.size} action${plural(task.actions.size)}", MaterialTheme.colorScheme.primary) }
                item { StatusPill("Priority ${task.priority}", MaterialTheme.colorScheme.secondary) }
                item { StatusPill(task.collisionMode.name.lowercase().replace('_', ' '), MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    Icon(Icons.Filled.Edit, contentDescription = "Edit task")
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = onAddAction, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = "Add action")
                    Spacer(Modifier.width(6.dp))
                    Text("Add Action")
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedButton(onClick = onRun) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Run task")
                        Spacer(Modifier.width(6.dp))
                        Text("Run")
                    }
                }
                item {
                    OutlinedButton(onClick = onPin) {
                        Icon(Icons.Filled.PushPin, contentDescription = "Pin task")
                        Spacer(Modifier.width(6.dp))
                        Text("Pin")
                    }
                }
                item {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete task")
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
private fun RunLogScreenContent(
    logs: List<RunLogEntry>,
    tasks: List<Task>,
    retentionPolicy: RunLogRetentionPolicy,
    onRetentionPolicyChange: (RunLogRetentionPolicy) -> Unit,
    onShareDiagnostic: () -> Unit,
    contentPadding: PaddingValues,
) {
    var statusFilterOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val statusFilter = RunLogStatusFilter.entries.getOrElse(statusFilterOrdinal) { RunLogStatusFilter.All }
    var taskIdFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
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
                RunLogSummaryCard(logs, onShareDiagnostic)
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
                    onStatusFilterChange = { statusFilterOrdinal = it.ordinal },
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                RunLogRetentionOptions.all.forEach { option ->
                    val selected = option.policy == policy
                    OutlinedButton(
                        onClick = { onPolicyChange(option.policy) },
                        modifier = Modifier
                            .fillMaxWidth()
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
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
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
                                        contentDescription = "Selected",
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Spacer(Modifier.size(16.dp))
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
private fun RunLogSummaryCard(logs: List<RunLogEntry>, onShareDiagnostic: () -> Unit = {}) {
    val outcomes = remember(logs) { logs.map { it.outcome() } }
    val failures = outcomes.count { it == RunLogOutcome.Failed }
    val skipped = outcomes.count { it == RunLogOutcome.Skipped }
    val latest = logs.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
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
            OutlinedButton(
                onClick = onShareDiagnostic,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Share diagnostic report")
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
    val sourceText = entry.source?.let { key ->
        val name = RunLogSource.displayName(key)
        entry.sourceLabel?.let { "$name: $it" } ?: name
    } ?: diagnostics.source
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
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    when (outcome) {
                        RunLogOutcome.Succeeded -> Icons.Filled.CheckCircle
                        RunLogOutcome.Failed -> Icons.Filled.Error
                        RunLogOutcome.Skipped -> Icons.Filled.Info
                    },
                    contentDescription = when (outcome) {
                        RunLogOutcome.Succeeded -> "Succeeded"
                        RunLogOutcome.Failed -> "Failed"
                        RunLogOutcome.Skipped -> "Skipped"
                    },
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.taskName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    sourceText?.let { source ->
                        Text(
                            "Source: $source",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                item { StatusPill(outcome.label, accent) }
                item { StatusPill("${entry.durationMs} ms", accent) }
            }
            Column(Modifier.fillMaxWidth()) {
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
            shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        ) {
            Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Info",
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
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(actionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(secondaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (tertiaryActionLabel != null && onTertiaryAction != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onTertiaryAction,
                enabled = tertiaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(tertiaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (quaternaryActionLabel != null && onQuaternaryAction != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onQuaternaryAction,
                enabled = quaternaryActionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(quaternaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
                contentDescription = "Delete",
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
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Review OpenTasker bundle") },
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
                        title = bundle.metadata.name.ifBlank { "OpenTasker bundle" },
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
            Button(
                enabled = plan.canImport && !busy,
                onClick = onConfirm,
            ) {
                Text(if (busy) "Importing..." else "Import Disabled")
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
                modifier = Modifier.heightIn(max = 460.dp),
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
    var values by rememberSaveable(template.id) { mutableStateOf(template.defaults()) }
    val missingRequired = template.slots.any { it.required && values[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
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
    var name by rememberSaveable(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var priority by rememberSaveable(task?.id) { mutableStateOf((task?.priority ?: 5).toString()) }
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
    onSave: (String, Boolean, Long, Int, AutomationMode, String?) -> Unit,
) {
    val initialTaskId = profile?.enterTaskId ?: tasks.firstOrNull()?.id ?: 0L
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var enabled by rememberSaveable(profile?.id) { mutableStateOf(profile?.enabled ?: true) }
    var enterTaskId by rememberSaveable(profile?.id, tasks) { mutableLongStateOf(initialTaskId) }
    var cooldown by rememberSaveable(profile?.id) { mutableStateOf((profile?.cooldownSec ?: 0).toString()) }
    var automationMode by rememberSaveable(profile?.id) { mutableStateOf(profile?.automationMode ?: AutomationMode.SINGLE) }
    var group by rememberSaveable(profile?.id) { mutableStateOf(profile?.group.orEmpty()) }
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
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Group") },
                    placeholder = { Text("Work, Home, Travel") },
                    supportingText = { Text("Optional. Groups profiles for filtering.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = enabled,
                            role = Role.Switch,
                            onValueChange = { enabled = it },
                        )
                        .semantics {
                            stateDescription = if (enabled) "On" else "Off"
                        },
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
                        Switch(checked = enabled, onCheckedChange = null)
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
            Button(enabled = canSave, onClick = { onSave(name, enabled, enterTaskId, parsedCooldown ?: 0, automationMode, group.trim().ifBlank { null }) }) {
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
                modifier = Modifier.heightIn(max = 420.dp),
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

private fun existingActionArgValue(
    actionId: String,
    key: String,
    args: Map<String, String>,
): String = args[key] ?: when (actionId to key) {
    "brightness.set" to "brightness" -> args["level"]
    "screenshot.take" to "path" -> args["filename"]
    "file.read" to "var" -> args["variable"]
    "file.write" to "text" -> args["content"]
    "file.append" to "text" -> args["content"]
    "file.list" to "var" -> args["variable"]
    "http.get" to "var" -> args["variable"]
    "http.post" to "data" -> args["body"]
    "http.post" to "var" -> args["variable"]
    else -> null
}.orEmpty()

@Composable
private fun ActionConfigDialog(
    state: ActionEditState,
    onDismiss: () -> Unit,
    onSave: (ActionSpec) -> Unit,
) {
    var label by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.label ?: state.metadata.name)
    }
    var values by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(
            state.metadata.fields.associate { field ->
                field.key to existingActionArgValue(
                    actionId = state.metadata.id,
                    key = field.key,
                    args = state.existing?.args.orEmpty(),
                )
            }
        )
    }
    val capability = remember(state.metadata.id) { ActionCapabilityRegistry.get(state.metadata.id) }
    val missingRequired = state.metadata.fields.any { it.required && values[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.metadata.name) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
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
        FieldType.CHECKBOX -> {
            val checked = value.toBoolean()
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = { onChange(it.toString()) },
                    )
                    .semantics {
                        stateDescription = if (checked) "On" else "Off"
                    },
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
                Switch(checked = checked, onCheckedChange = null)
            }
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
        modifier = modifier.heightIn(min = 48.dp),
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
    var invert by rememberSaveable(state.profile.id, state.index, state.type) { mutableStateOf(state.existing?.invert ?: false) }
    var config by rememberSaveable(state.profile.id, state.index, state.type) {
        mutableStateOf(defaultContextConfig(state.type) + (state.existing?.config ?: emptyMap()))
    }
    var nfcWriteMessage by rememberSaveable(state.profile.id, state.index, state.type) { mutableStateOf<String?>(null) }
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
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(contextDescription(state.type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = invert,
                                role = Role.Switch,
                                onValueChange = { invert = it },
                            )
                            .semantics {
                                stateDescription = if (invert) "On" else "Off"
                            },
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
                            Switch(checked = invert, onCheckedChange = null)
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
    ContextType.APPLICATION -> listOf(ActionField("package", "Package name", required = true, hint = "com.example.app (personal profile only)"))
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
        ActionField("event", "Event type", required = true, hint = "boot_completed, notification, nfc, bluetooth, calendar, sunrise, sunset, shake, package_added, package_removed, package_replaced"),
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
    ContextType.PLUGIN -> listOf(
        ActionField("package", "Plugin package", required = true, hint = "com.example.plugin"),
        ActionField("bundleJson", "Plugin config JSON", hint = "{\"key\":\"value\"}"),
        ActionField("blurb", "Description", hint = "Optional label from plugin config"),
        ActionField("timeoutMs", "Query timeout ms", FieldType.NUMBER, hint = "5000"),
    )
}

private fun contextConfigForSave(type: ContextType, config: Map<String, String>): Map<String, String> {
    val nonBlank = config.filterValues { it.isNotBlank() }
    if (type == ContextType.DAY) {
        val canonicalDays = DaySchedule.canonicalize(config["days"].orEmpty()).orEmpty()
        return if (canonicalDays.isBlank()) {
            nonBlank - "days"
        } else {
            nonBlank + ("days" to canonicalDays)
        }
    }
    if (type == ContextType.PLUGIN) {
        val result = nonBlank.toMutableMap()
        val bundle = result["bundleJson"]?.trim().orEmpty()
        if (bundle.isBlank() || bundle == "{}") {
            result.remove("bundleJson")
        }
        val timeout = result["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000)
        if (timeout != null) {
            result["timeoutMs"] = timeout.toString()
        } else {
            result.remove("timeoutMs")
        }
        return result
    }
    return nonBlank
}

private fun defaultContextConfig(type: ContextType): Map<String, String> = when (type) {
    ContextType.TIME -> mapOf("start" to "09:00", "end" to "17:00")
    ContextType.DAY -> mapOf("days" to "MON,TUE,WED,THU,FRI")
    ContextType.LOCATION -> mapOf("radiusMeters" to "100")
    ContextType.PLUGIN -> mapOf("timeoutMs" to "5000")
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
                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.secondary)
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
    ContextType.PLUGIN -> "Matches when a Locale/Tasker condition plugin reports satisfied. The plugin is polled periodically and its last known state is cached."
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
