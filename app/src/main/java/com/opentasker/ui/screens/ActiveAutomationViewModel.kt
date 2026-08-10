package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.app.R
import com.opentasker.core.capabilities.AutomationFeedbackRiskAnalyzer
import com.opentasker.core.capabilities.ImportedProfileEnablePolicy
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.diagnostics.DiagnosticExport
import com.opentasker.core.diagnostics.AdvancedProtectionReader
import com.opentasker.core.diagnostics.CrashLogHandler
import com.opentasker.core.diagnostics.CrashLogRecord
import com.opentasker.core.diagnostics.EngineHealthReader
import com.opentasker.core.diagnostics.EngineHealthStatus
import com.opentasker.core.diagnostics.RunLogExportFormat
import com.opentasker.core.diagnostics.RunLogExporter
import com.opentasker.core.engine.ActiveExecution
import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.engine.ExecutionEnvelope
import com.opentasker.core.engine.PreflightInputs
import com.opentasker.core.engine.PreflightReport
import com.opentasker.core.engine.PreflightRunner
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.replayHeldExecution
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.DEFAULT_PROJECT_ID
import com.opentasker.core.validation.InputValidation
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.logging.AppLogEntry
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.references.AutomationReferenceRewriter
import com.opentasker.core.references.ReferenceResolution
import com.opentasker.core.references.TaskReference
import com.opentasker.core.references.describe
import com.opentasker.core.sharing.ProfileShareDraft
import com.opentasker.core.sharing.ProfileShareLibrary
import com.opentasker.core.sharing.ProfileShareManifest
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.RestoreCandidate
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.EditHistorySnapshotDecoder
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.RunLogQuery
import com.opentasker.core.storage.RunLogSnapshot
import com.opentasker.core.storage.RunLogTaskOption
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.ProjectEntity
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.loadPage
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.openSnapshot
import com.opentasker.core.storage.toEntity
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import com.opentasker.core.transfer.OpenTaskerBundleTextImport
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.core.transfer.VariableConflictResolution
import com.opentasker.widget.TaskShortcutHelper
import androidx.room.withTransaction
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val TASKER_XML_IMPORT_MAX_BYTES = 4 * 1024 * 1024
internal const val OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES = 8 * 1024 * 1024
internal val TASKER_XML_MIME_TYPES = arrayOf("application/xml", "text/xml", "text/*", "*/*")
internal val OPEN_TASKER_BUNDLE_MIME_TYPES = arrayOf("application/json", "text/json", "text/*", "*/*")
internal val DATABASE_BACKUP_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/x-sqlite3",
    "application/vnd.sqlite3",
    "*/*",
)

internal fun databaseBackupExportName(): String =
    "opentasker_backup_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.db"

internal fun openTaskerBundleExportName(): String =
    "opentasker_bundle_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.json"

internal fun runLogExportName(format: RunLogExportFormat): String {
    val extension = if (format == RunLogExportFormat.JSON) "json" else "csv"
    return "opentasker_run_log_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.$extension"
}

internal data class TaskerImportReviewState(
    val report: TaskerXmlImportReport,
    val preview: TaskerImportPreview,
)

/** Snackbar payloads stay as resource IDs until the Compose collector resolves them. */
data class UiMessage(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
) {
    fun resolve(context: Context): String = context.getString(resId, *args.toTypedArray())
}

internal data class OpenTaskerBundleReviewState(
    val bundle: OpenTaskerBundle,
    val plan: BundleImportPlan,
    val variableResolutions: Map<String, VariableConflictResolution> = emptyMap(),
)

internal data class ProfileShareReviewState(
    val draft: ProfileShareDraft,
    val manifest: ProfileShareManifest,
    val plan: BundleImportPlan,
    val draftError: String? = null,
)

internal sealed interface PreflightTarget {
    data class TaskTarget(val task: Task) : PreflightTarget
    data class ProfileTarget(val profile: Profile) : PreflightTarget
}

internal data class PreflightReviewState(
    val target: PreflightTarget,
    val inputs: PreflightInputs,
    val report: PreflightReport,
)

/**
 * What a task delete would break: every dependent object, plus whether any of them holds a
 * reference that cannot legally be cleared (a profile's enter task).
 */
data class TaskDeletionPreview(
    val task: Task,
    val references: List<TaskReference> = emptyList(),
    val requiresReassignment: Boolean = false,
) {
    val hasDependents: Boolean get() = references.isNotEmpty()
}

/**
 * A validated restore candidate awaiting an explicit Stage decision, plus whatever restore it
 * would replace, so the user is never silently overwriting an earlier staged restore.
 */
data class RestoreReviewState(
    val candidate: RestoreCandidate,
    val replacesPending: RestoreCandidate? = null,
)

data class DiagnosticsUiState(
    val health: EngineHealthStatus? = null,
    val crashLogs: List<CrashLogRecord> = emptyList(),
    val appLogs: List<AppLogEntry> = emptyList(),
    val loadedAtMillis: Long = 0L,
)

data class RunLogPageUiState(
    val entries: ImmutableList<RunLogEntry> = persistentListOf(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    internal val snapshot: RunLogSnapshot? = null,
)

data class RunLogRetentionPreview(
    val policy: RunLogRetentionPolicy,
    val storedCount: Int,
    val prunableCount: Int,
    val oldestTimestamp: Long?,
)

/**
 * Thrown when a normal editor save would overwrite a record whose stored payload currently fails
 * to decode. Blocking the write keeps the corrupt bytes intact for recovery instead of clobbering
 * them with an empty fallback (fail closed).
 */
internal class CorruptRecordOverwriteException(issue: StorageDecodeIssue) : IllegalStateException(
    "Can't save ${issue.recordType.label.lowercase()} \"${issue.recordName}\": its stored " +
        "${issue.fieldName} is corrupt. Recover it (undo or restore a backup) or delete it first.",
)

class ActiveAutomationViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {
    private val locationDwellStateStore = LocationDwellStateStore(appContext)
    private val variableRepository = VariableRepository(db.variableDao())
    private val bundleRepository = OpenTaskerBundleRepository(db, variableRepository)
    private val runLogRetentionSettings = RunLogRetentionSettings(appContext)
    private val databaseBackupManager = DatabaseBackupManager(appContext, db)

    private fun message(@StringRes resId: Int, vararg args: Any): UiMessage =
        UiMessage(resId, args.toList())

    private fun errorMessage(error: Throwable, fallbackRes: Int): UiMessage =
        message(R.string.ui_error_message, error.message ?: appContext.getString(fallbackRes))

    private fun legacyMessage(value: String): UiMessage = when {
        value == "Task created" -> message(R.string.ui_message_task_created)
        value == "Task updated" -> message(R.string.ui_message_task_updated)
        value == "Action moved" -> message(R.string.ui_message_action_moved)
        value == "Scene created" -> message(R.string.ui_message_scene_created)
        value == "Scene updated" -> message(R.string.ui_message_scene_updated)
        value == "Scene deleted" -> message(R.string.ui_message_scene_deleted)
        value == "Profile created" -> message(R.string.ui_message_profile_created)
        value == "Profile updated" -> message(R.string.ui_message_profile_updated)
        value == "Profile deleted" -> message(R.string.ui_message_profile_deleted)
        value == "Project created" -> message(R.string.ui_message_project_created)
        value == "Project renamed" -> message(R.string.ui_message_project_renamed)
        value == "Project reordered" -> message(R.string.ui_message_project_reordered)
        value == "Project deleted" -> message(R.string.ui_message_project_deleted)
        value == "Imported profile reviewed and enabled" -> message(R.string.ui_message_profile_reviewed)
        value == "Template installed as a disabled profile" -> message(R.string.ui_message_template_installed)
        value == "Edit undone" -> message(R.string.ui_message_edit_undone)
        value == "No edit history available" -> message(R.string.ui_message_no_edit_history)
        value == "Elements moved" -> message(R.string.ui_message_elements_moved)
        value == "Element added" -> message(R.string.ui_message_element_added)
        value == "Element updated" -> message(R.string.ui_message_element_updated)
        value == "Action added" -> message(R.string.ui_message_action_added)
        value == "Action updated" -> message(R.string.ui_message_action_updated)
        value == "Context added" -> message(R.string.ui_message_context_added)
        value == "Context updated" -> message(R.string.ui_message_context_updated)
        value == "Variable created" -> message(R.string.ui_message_variable_created)
        value.startsWith("Updated ") -> message(R.string.variables_updated, value.removePrefix("Updated "))
        value.startsWith("Deleted ") -> message(R.string.variables_deleted, value.removePrefix("Deleted "))
        else -> message(R.string.ui_error_message, value)
    }

    private suspend fun recordEdit(
        entityType: String,
        entityId: Long,
        previousJson: String,
        nextJson: String,
    ) {
        db.editHistoryDao().deleteRedoBranch(entityType, entityId)
        db.editHistoryDao().insert(
            EditHistoryEntity(
                entityType = entityType,
                entityId = entityId,
                previousJson = previousJson,
                nextJson = nextJson,
            ),
        )
        db.editHistoryDao().pruneOld(entityType, entityId)
    }

    private val profileDecodeResults = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val taskDecodeResults = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles: StateFlow<ImmutableList<Profile>> = profileDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase() }
                .toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val tasks: StateFlow<ImmutableList<Task>> = taskDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase() }
                .toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val sceneDecodeResults = db.sceneDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val storageDecodeIssues: StateFlow<ImmutableList<StorageDecodeIssue>> = combine(
        profileDecodeResults,
        taskDecodeResults,
        sceneDecodeResults,
    ) { profileResults, taskResults, sceneResults ->
        (profileResults.mapNotNull { it.issue } + taskResults.mapNotNull { it.issue } + sceneResults.mapNotNull { it.issue })
            .sortedWith(compareBy<StorageDecodeIssue> { it.recordType.label }.thenBy { it.recordName.lowercase() })
            .toImmutableList()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val scenes: StateFlow<ImmutableList<Scene>> = sceneDecodeResults
        .map { results ->
            results.mapNotNull { result -> result.value.takeIf { result.issue == null } }
                .sortedBy { it.name.lowercase() }
                .toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val projects: StateFlow<ImmutableList<Project>> = db.projectDao()
        .getAllAsFlow()
        .map { entities ->
            entities.map(ProjectEntity::toDomain).sortedWith(compareBy<Project> { it.position }.thenBy { it.id }).toImmutableList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val runLogs: StateFlow<ImmutableList<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val _runLogFilters = MutableStateFlow(RunLogFilterState())
    val runLogFilters: StateFlow<RunLogFilterState> = _runLogFilters.asStateFlow()

    private val _runLogPage = MutableStateFlow(RunLogPageUiState())
    val runLogPage: StateFlow<RunLogPageUiState> = _runLogPage.asStateFlow()

    val runLogTaskOptions: StateFlow<ImmutableList<RunLogTaskOption>> = db.runLogDao()
        .getTaskOptionsFlow()
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private var runLogPageJob: Job? = null

    /** Runs in flight right now, so the Run Log can show and stop them. */
    val activeExecutions: StateFlow<ImmutableList<ActiveExecution>> = ActiveExecutionRegistry.active
        .map { it.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    fun cancelExecution(executionId: Long) {
        viewModelScope.launch {
            val cancelled = ActiveExecutionRegistry.cancel(executionId)
            events.send(message(if (cancelled) R.string.ui_message_cancelling_automation else R.string.ui_message_automation_finished))
        }
    }

    val globalVariables: StateFlow<ImmutableList<Variable>> = variableRepository
        .observeGlobals(null)
        .map { variables -> variables.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val events = Channel<UiMessage>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    private val _runLogRetentionPolicy = MutableStateFlow(runLogRetentionSettings.load())
    val runLogRetentionPolicy: StateFlow<RunLogRetentionPolicy> = _runLogRetentionPolicy.asStateFlow()

    private val _runLogRetentionPreview = MutableStateFlow<RunLogRetentionPreview?>(null)
    val runLogRetentionPreview: StateFlow<RunLogRetentionPreview?> = _runLogRetentionPreview.asStateFlow()

    // Starts with a cheap placeholder; the real state (which enumerates the filesystem) is
    // loaded off the main thread in init and refreshed after each backup operation.
    private val _backupSetupState = MutableStateFlow(BackupSetupState(busy = false))
    val backupSetupState: StateFlow<BackupSetupState> = _backupSetupState.asStateFlow()

    private val _diagnosticsState = MutableStateFlow(DiagnosticsUiState())
    val diagnosticsState: StateFlow<DiagnosticsUiState> = _diagnosticsState.asStateFlow()
    private var diagnosticsRefreshJob: Job? = null

    private val _taskerImportReview = MutableStateFlow<TaskerImportReviewState?>(null)
    internal val taskerImportReview: StateFlow<TaskerImportReviewState?> = _taskerImportReview.asStateFlow()

    private val _taskerImportBusy = MutableStateFlow(false)
    val taskerImportBusy: StateFlow<Boolean> = _taskerImportBusy.asStateFlow()

    private val _openTaskerBundleReview = MutableStateFlow<OpenTaskerBundleReviewState?>(null)
    internal val openTaskerBundleReview: StateFlow<OpenTaskerBundleReviewState?> = _openTaskerBundleReview.asStateFlow()

    private val _openTaskerBundleBusy = MutableStateFlow(false)
    val openTaskerBundleBusy: StateFlow<Boolean> = _openTaskerBundleBusy.asStateFlow()

    private val _profileShareReview = MutableStateFlow<ProfileShareReviewState?>(null)
    internal val profileShareReview: StateFlow<ProfileShareReviewState?> = _profileShareReview.asStateFlow()

    private val _preflightReview = MutableStateFlow<PreflightReviewState?>(null)
    internal val preflightReview: StateFlow<PreflightReviewState?> = _preflightReview.asStateFlow()

    private val _preflightBusy = MutableStateFlow(false)
    val preflightBusy: StateFlow<Boolean> = _preflightBusy.asStateFlow()

    init {
        refreshRunLogPage()
        viewModelScope.launch {
            runCatching { pruneRunLogs(_runLogRetentionPolicy.value) }
        }
        viewModelScope.launch {
            runCatching { refreshBackupSetupState(busy = false) }
        }
        refreshDiagnostics()
        viewModelScope.launch {
            AdvancedProtectionReader.changes.collect {
                refreshDiagnostics()
            }
        }
    }

    fun refreshDiagnostics() {
        if (diagnosticsRefreshJob?.isActive == true) return
        diagnosticsRefreshJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    DiagnosticsUiState(
                        health = EngineHealthReader.read(appContext),
                        crashLogs = CrashLogHandler.listCrashLogs(appContext),
                        appLogs = AppLogger.snapshot().takeLast(100).map { entry ->
                            entry.copy(message = DiagnosticExport.redactSensitive(entry.message))
                        },
                        loadedAtMillis = System.currentTimeMillis(),
                    )
                }
            }.onSuccess { state ->
                _diagnosticsState.value = state
            }.onFailure { error ->
                events.send(errorMessage(error, R.string.ui_error_diagnostics_refresh))
            }
        }
    }

    fun createTask(name: String, priority: Int, collisionMode: CollisionMode, projectId: Long = DEFAULT_PROJECT_ID) = launchWithMessage("Task created") {
        db.taskDao().insert(
            Task(
                name = name.trim(),
                priority = priority.coerceIn(0, 10),
                collisionMode = collisionMode,
                projectId = projectId,
            ).toEntity(),
        )
    }

    fun updateTask(task: Task, message: String = "Task updated") = launchWithMessage(message) {
        // Wrapped like updateScene: the corrupt-record check, history snapshot, prune, and
        // update must be atomic so a concurrent writer can't interleave and lose a revision.
        db.withTransaction {
            val previous = db.taskDao().getById(task.id)
            if (previous != null) {
                previous.toDomainDecodeResult().issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                val previousTask = previous.toDomain()
                recordEdit(
                    entityType = EditHistoryDao.TYPE_TASK,
                    entityId = task.id,
                    previousJson = StorageJson.encodeToString(previousTask),
                    nextJson = StorageJson.encodeToString(task),
                )

                // A rename breaks every reference that still names this task ("task.run" targets,
                // legacy notification bindings). Pin those to the stable id in the same
                // transaction so they cannot dangle or be captured by a future task that takes the
                // old name.
                if (!previousTask.name.equals(task.name, ignoreCase = true)) {
                    val rewrite = AutomationReferenceRewriter.stabilizeNameReferences(
                        target = previousTask,
                        profiles = db.profileDao().getAll().map { it.toDomain() },
                        tasks = db.taskDao().getAll().map { it.toDomain() },
                        scenes = db.sceneDao().getAll().map { it.toDomain() },
                    )
                    rewrite.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
                    rewrite.tasks.filterNot { it.id == task.id }.forEach { db.taskDao().update(it.toEntity()) }
                    rewrite.scenes.forEach { db.sceneDao().update(it.toEntity()) }
                }
            }
            db.taskDao().update(task.toEntity())
        }
    }

    fun moveTaskAction(taskId: Long, fromIndex: Int, toIndex: Int) = launchWithMessage("Action moved") {
        db.withTransaction {
            val entity = db.taskDao().getById(taskId) ?: error("Task no longer exists.")
            val decoded = entity.toDomainDecodeResult()
            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
            val updated = decoded.value.copy(
                actions = reorderActions(decoded.value.actions, fromIndex, toIndex),
            )
            recordEdit(
                entityType = EditHistoryDao.TYPE_TASK,
                entityId = taskId,
                previousJson = StorageJson.encodeToString(decoded.value),
                nextJson = StorageJson.encodeToString(updated),
            )
            db.taskDao().update(updated.toEntity())
        }
    }

    /**
     * Every object that still points at [task], resolved through the shared reference index so
     * `task.run` arguments, notification buttons, and scene gestures are surfaced alongside the
     * profile columns that used to be the only thing checked.
     */
    suspend fun taskDeletionPreview(task: Task): TaskDeletionPreview = withContext(Dispatchers.IO) {
        val references = runCatching {
            AutomationReferenceIndex.referencesTo(
                task = task,
                profiles = db.profileDao().getAll().map { it.toDomain() },
                tasks = db.taskDao().getAll().map { it.toDomain() },
                scenes = db.sceneDao().getAll().map { it.toDomain() },
            )
        }.getOrElse { emptyList() }
        TaskDeletionPreview(
            task = task,
            references = references,
            requiresReassignment = references.any { it.isRequired },
        )
    }

    /**
     * Deletes [task] and applies [resolution] to every dependent reference in one transaction, so
     * the workspace can never be observed with a dangling or half-rewritten reference.
     */
    fun deleteTask(task: Task, resolution: ReferenceResolution = ReferenceResolution.Block) {
        viewModelScope.launch {
            runCatching {
                var blockedCount = 0
                db.withTransaction {
                    val profiles = db.profileDao().getAll().map { it.toDomain() }
                    val tasks = db.taskDao().getAll().map { it.toDomain() }
                    val scenes = db.sceneDao().getAll().map { it.toDomain() }
                    val rewrite = AutomationReferenceRewriter.retarget(
                        target = task,
                        resolution = resolution,
                        profiles = profiles,
                        tasks = tasks,
                        scenes = scenes,
                    )
                    if (!rewrite.canCommit) {
                        blockedCount = rewrite.blocked.size
                        return@withTransaction
                    }
                    rewrite.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
                    rewrite.tasks.forEach { db.taskDao().update(it.toEntity()) }
                    rewrite.scenes.forEach { db.sceneDao().update(it.toEntity()) }
                    db.taskDao().delete(task.toEntity())
                }
                blockedCount
            }
                .onSuccess { blocked ->
                    if (blocked > 0) {
                        events.send(message(R.string.ui_task_still_used, blocked))
                    } else {
                        LocaleGrantStore(appContext).revokeAllForTask(task.id)
                        events.send(message(R.string.ui_message_task_deleted))
                    }
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_task_delete)) }
        }
    }

    fun createScene(name: String, widthDp: Int, heightDp: Int, projectId: Long = DEFAULT_PROJECT_ID) = launchWithMessage("Scene created") {
        db.sceneDao().insert(
            Scene(
                name = name.trim(),
                widthDp = widthDp.coerceIn(120, 1440),
                heightDp = heightDp.coerceIn(80, 2560),
                projectId = projectId,
            ).toEntity()
        )
    }

    fun updateScene(scene: Scene, message: String = "Scene updated") = launchWithMessage(message) {
        db.withTransaction {
            val previous = scene.id.takeIf { it > 0L }?.let { db.sceneDao().getById(it) }
            if (previous != null) {
                previous.toDomainDecodeResult().issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                recordEdit(
                    entityType = EditHistoryDao.TYPE_SCENE,
                    entityId = scene.id,
                    previousJson = StorageJson.encodeToString(previous.toDomain()),
                    nextJson = StorageJson.encodeToString(scene),
                )
            }
            db.sceneDao().update(scene.toEntity())
        }
    }

    fun deleteScene(scene: Scene) = launchWithMessage("Scene deleted") {
        db.sceneDao().delete(scene.toEntity())
    }

    fun createProfile(name: String, enabled: Boolean, enterTaskId: Long, exitTaskId: Long?, cooldownSec: Int, automationMode: AutomationMode, group: String? = null, projectId: Long = DEFAULT_PROJECT_ID) =
        launchWithMessage("Profile created") {
            val profile = Profile(
                name = name.trim(),
                enabled = enabled,
                enterTaskId = enterTaskId,
                exitTaskId = exitTaskId,
                cooldownSec = cooldownSec.coerceAtLeast(0),
                automationMode = automationMode,
                group = group,
                projectId = projectId,
            )
            requireValidProfileFieldLimits(profile)
            db.profileDao().upsert(reviewFeedbackRisk(profile).toEntity())
        }

    fun updateProfile(profile: Profile, message: String = "Profile updated") =
        launchWithMessage(message) {
            val reviewedProfile = reviewFeedbackRisk(profile)
            requireValidProfileFieldLimits(reviewedProfile)
            // Atomic read-check-snapshot-update, matching updateScene, so racing writers
            // (dialog save vs. notification/external-intent path) can't lose a revision.
            db.withTransaction {
                val previousEntity = profile.id.takeIf { it > 0L }
                    ?.let { db.profileDao().getById(it) }
                previousEntity?.toDomainDecodeResult()?.issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                val previous = previousEntity?.toDomain()
                if (
                    previous?.requiresRiskAcknowledgement == true &&
                    (reviewedProfile.enabled || !reviewedProfile.requiresRiskAcknowledgement)
                ) {
                    throw IllegalStateException("Review imported automation powers before enabling this profile.")
                }
                if (previousEntity != null) {
                    recordEdit(
                        entityType = EditHistoryDao.TYPE_PROFILE,
                        entityId = profile.id,
                        previousJson = StorageJson.encodeToString(previous),
                        nextJson = StorageJson.encodeToString(reviewedProfile),
                    )
                }
                if (previous != null && previous.contexts != profile.contexts) {
                    locationDwellStateStore.clearProfile(profile.id)
                }
                    db.profileDao().upsert(reviewedProfile.toEntity())
            }
        }

    fun createProject(name: String) = launchWithMessage("Project created") {
        val normalized = validateProjectName(name)
        require(db.projectDao().getAll().none { it.name.equals(normalized, ignoreCase = true) }) {
            "A project with that name already exists."
        }
        val nextPosition = (db.projectDao().getAll().maxOfOrNull { it.position } ?: -1) + 1
        db.projectDao().insert(ProjectEntity(name = normalized, position = nextPosition))
    }

    fun renameProject(project: Project, name: String) = launchWithMessage("Project renamed") {
        require(project.id != DEFAULT_PROJECT_ID) { "The Default project cannot be renamed." }
        val normalized = validateProjectName(name)
        require(db.projectDao().getAll().none { it.id != project.id && it.name.equals(normalized, ignoreCase = true) }) {
            "A project with that name already exists."
        }
        db.projectDao().update(ProjectEntity(project.id, normalized, project.position))
    }

    fun reorderProject(project: Project, direction: Int) = launchWithMessage("Project reordered") {
        val ordered = db.projectDao().getAll().sortedWith(compareBy<ProjectEntity> { it.position }.thenBy { it.id })
        val index = ordered.indexOfFirst { it.id == project.id }
        val targetIndex = (index + direction.coerceIn(-1, 1)).coerceIn(0, ordered.lastIndex)
        if (index < 0 || targetIndex == index) return@launchWithMessage
        val other = ordered[targetIndex]
        db.projectDao().update(other.copy(position = project.position))
        db.projectDao().update(ProjectEntity(project.id, project.name, other.position))
    }

    fun deleteProject(project: Project, targetProject: Project) = launchWithMessage("Project deleted") {
        require(project.id != DEFAULT_PROJECT_ID) { "The Default project cannot be deleted." }
        require(project.id != targetProject.id) { "Choose a different destination project." }
        db.withTransaction {
            val sourceVariables = db.variableDao().getAllInProject(project.id)
            val targetNames = db.variableDao().getAllInProject(targetProject.id).map { it.name }.toSet()
            val collisions = sourceVariables.map { it.name }.filter { it in targetNames }
            require(collisions.isEmpty()) {
                "Reassignment would overwrite variables: ${collisions.joinToString()}. Rename or remove them first."
            }
            db.taskDao().reassignProject(project.id, targetProject.id)
            db.profileDao().reassignProject(project.id, targetProject.id)
            db.sceneDao().reassignProject(project.id, targetProject.id)
            sourceVariables.forEach { db.variableDao().insert(it.copy(projectId = targetProject.id)) }
            db.variableDao().deleteAllInProject(project.id)
            check(db.projectDao().deleteIfNotDefault(project.id) == 1) { "Project no longer exists." }
        }
    }

    private fun validateProjectName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Project name cannot be empty." }
        require(normalized.length <= 64) { "Project names must be 64 characters or fewer." }
        return normalized
    }

    private suspend fun reviewFeedbackRisk(profile: Profile): Profile {
        if (!profile.enabled || profile.requiresRiskAcknowledgement) return profile
        val tasks = db.taskDao().getAll().map { it.toDomain() }
        return if (AutomationFeedbackRiskAnalyzer.analyze(profile, tasks).isEmpty()) {
            profile
        } else {
            profile.copy(enabled = false, requiresRiskAcknowledgement = true)
        }
    }

    fun acknowledgeAndEnableImportedProfile(profileId: Long) =
        launchWithMessage("Imported profile reviewed and enabled") {
            val current = db.profileDao().getById(profileId)?.toDomain()
                ?: throw IllegalStateException("Profile no longer exists.")
            check(current.requiresRiskAcknowledgement) { "Profile review is no longer required." }
            val tasks = db.taskDao().getAll().map { it.toDomain() }
            val review = ImportedProfileEnablePolicy.review(current, tasks)
            check(review.canAcknowledge) {
                "Remove unsupported or unknown actions before enabling this imported profile."
            }
            db.profileDao().upsert(
                current.copy(
                    enabled = true,
                    requiresRiskAcknowledgement = false,
                ).toEntity(),
            )
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
                db.profileDao().upsert(applied.profile.copy(enterTaskId = taskId).toEntity())
            }
        }

    fun previewLocalProfileShare(appVersion: String) {
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = bundleRepository.exportBundle(
                        appVersion = appVersion,
                        name = "OpenTasker Community Share",
                        description = "A local OpenTasker profile share draft.",
                    )
                    buildProfileShareReview(bundle)
                }
            }
                .onSuccess { _profileShareReview.value = it }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_share_preview)) }
            _openTaskerBundleBusy.value = false
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
                    events.send(message(R.string.ui_message_tasker_xml_ready))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_tasker_xml_preview)) }
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
                        message(
                            R.string.ui_message_tasker_imported,
                            importReport.insertedTasks,
                            importReport.insertedProfiles,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_tasker_xml_import)) }
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
                        message(
                            R.string.ui_message_bundle_exported,
                            bundle.tasks.size,
                            bundle.profiles.size,
                            bundle.scenes.size,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_bundle_export)) }
            _openTaskerBundleBusy.value = false
        }
    }

    fun previewOpenTaskerBundle(uri: Uri) {
        previewOpenTaskerBundleSource {
            OpenTaskerBundleCodec.decode(readBoundedOpenTaskerBundle(appContext, uri))
        }
    }

    fun previewOpenTaskerBundleText(rawText: String) {
        previewOpenTaskerBundleSource {
            OpenTaskerBundleTextImport.decode(rawText)
        }
    }

    private fun previewOpenTaskerBundleSource(load: suspend () -> OpenTaskerBundle) {
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    buildProfileShareReview(load())
                }
            }
                .onSuccess {
                    _profileShareReview.value = it
                    events.send(message(R.string.ui_message_bundle_ready))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_bundle_preview)) }
            _openTaskerBundleBusy.value = false
        }
    }

    fun updateProfileShareDraft(draft: ProfileShareDraft) {
        val current = _profileShareReview.value ?: return
        runCatching { ProfileShareLibrary.buildManifest(draft) }
            .onSuccess { manifest ->
                _profileShareReview.value = current.copy(
                    draft = draft,
                    manifest = manifest,
                    draftError = null,
                )
            }
            .onFailure { error ->
                _profileShareReview.value = current.copy(
                    draft = draft,
                    draftError = error.message ?: "Invalid share details.",
                )
            }
    }

    fun addProfileShareScreenshots(uris: List<Uri>) {
        val current = _profileShareReview.value ?: return
        val screenshots = (current.draft.screenshots + uris.map(Uri::toString))
            .distinct()
            .take(PROFILE_SHARE_MAX_SCREENSHOTS)
        updateProfileShareDraft(current.draft.copy(screenshots = screenshots))
    }

    fun removeProfileShareScreenshot(uri: String) {
        val current = _profileShareReview.value ?: return
        updateProfileShareDraft(current.draft.copy(screenshots = current.draft.screenshots - uri))
    }

    fun clearProfileShareReview() {
        if (!_openTaskerBundleBusy.value) {
            _profileShareReview.value = null
        }
    }

    fun continueProfileShareImportReview() {
        val share = _profileShareReview.value ?: return
        if (share.draftError != null || share.manifest.hasBlockingFindings || !share.plan.canImport) return
        _openTaskerBundleReview.value = OpenTaskerBundleReviewState(
            bundle = share.draft.bundle,
            plan = share.plan,
        )
        _profileShareReview.value = null
    }

    private suspend fun buildProfileShareReview(bundle: OpenTaskerBundle): ProfileShareReviewState {
        val draft = ProfileShareDraft(
            slug = defaultProfileShareSlug(bundle.metadata.name),
            title = bundle.metadata.name.ifBlank { "OpenTasker Share" },
            summary = bundle.metadata.description.ifBlank {
                "A local OpenTasker profile share draft."
            },
            bundle = bundle,
        )
        return ProfileShareReviewState(
            draft = draft,
            manifest = ProfileShareLibrary.buildManifest(draft),
            plan = bundleRepository.planImport(bundle),
        )
    }

    fun clearOpenTaskerBundleReview() {
        if (!_openTaskerBundleBusy.value) {
            _openTaskerBundleReview.value = null
        }
    }

    fun resolveOpenTaskerVariableConflict(name: String, resolution: VariableConflictResolution) {
        if (_openTaskerBundleBusy.value) return
        val review = _openTaskerBundleReview.value ?: return
        if (review.plan.variableConflicts.none { it.name == name }) return
        _openTaskerBundleReview.value = review.copy(
            variableResolutions = review.variableResolutions + (name to resolution),
        )
    }

    fun confirmOpenTaskerBundleImport() {
        val review = _openTaskerBundleReview.value ?: return
        if (review.plan.variableConflicts.any { it.name !in review.variableResolutions }) return
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bundleRepository.importBundle(review.bundle, review.variableResolutions)
                }
            }
                .onSuccess { importReport ->
                    _openTaskerBundleReview.value = null
                    events.send(
                        message(
                            R.string.ui_message_bundle_imported,
                            importReport.insertedTasks,
                            importReport.insertedProfiles,
                            importReport.insertedScenes,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_bundle_import)) }
            _openTaskerBundleBusy.value = false
        }
    }

    fun updateRunLogFilters(filters: RunLogFilterState) {
        if (_runLogFilters.value == filters) return
        _runLogFilters.value = filters
        refreshRunLogPage()
    }

    fun refreshRunLogPage() {
        runLogPageJob?.cancel()
        _runLogPage.value = RunLogPageUiState(loading = true)
        val filters = _runLogFilters.value
        runLogPageJob = viewModelScope.launch {
            try {
                val (snapshot, page) = withContext(Dispatchers.IO) {
                    val opened = db.runLogDao().openSnapshot(filters.toStorageQuery())
                    opened to db.runLogDao().loadPage(opened)
                }
                _runLogPage.value = RunLogPageUiState(
                    entries = page.entries.map { it.toDomain() }.toImmutableList(),
                    totalCount = snapshot.totalCount,
                    hasMore = page.hasMore,
                    loading = false,
                    snapshot = snapshot,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _runLogPage.value = _runLogPage.value.copy(loading = false)
                events.send(errorMessage(error, R.string.ui_error_run_logs_load))
            }
        }
    }

    fun loadNextRunLogPage() {
        val current = _runLogPage.value
        val snapshot = current.snapshot ?: return
        if (current.loading || !current.hasMore) return
        val cursor = current.entries.lastOrNull()?.let { com.opentasker.core.storage.RunLogKey(it.timestamp, it.id) }
            ?: return
        _runLogPage.value = current.copy(loading = true)
        runLogPageJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { db.runLogDao().loadPage(snapshot, cursor) }
                val existingIds = current.entries.mapTo(mutableSetOf()) { it.id }
                val appended = page.entries.map { it.toDomain() }.filterNot { it.id in existingIds }
                _runLogPage.value = current.copy(
                    entries = (current.entries + appended).toImmutableList(),
                    hasMore = page.hasMore,
                    loading = false,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _runLogPage.value = current.copy(loading = false)
                events.send(errorMessage(error, R.string.ui_error_run_logs_more))
            }
        }
    }

    fun exportRunLogs(uri: Uri, format: RunLogExportFormat, allRetained: Boolean = false) {
        viewModelScope.launch {
            try {
                val exported = withContext(Dispatchers.IO) {
                    val snapshot = if (allRetained) {
                        db.runLogDao().openSnapshot(RunLogQuery())
                    } else {
                        _runLogPage.value.snapshot ?: db.runLogDao().openSnapshot(_runLogFilters.value.toStorageQuery())
                    }
                    val output = appContext.contentResolver.openOutputStream(uri, "w")
                        ?: error("Could not open the export destination")
                    output.use { RunLogExporter(db.runLogDao()).export(snapshot, format, it) }
                }
                events.send(message(R.string.ui_message_run_logs_exported, exported, if (exported == 1) "y" else "ies"))
            } catch (error: Exception) {
                events.send(errorMessage(error, R.string.ui_error_run_log_export))
            }
        }
    }

    fun requestRunLogRetention(policy: RunLogRetentionPolicy) {
        viewModelScope.launch {
            val normalized = policy.normalized()
            runCatching {
                withContext(Dispatchers.IO) {
                    val dao = db.runLogDao()
                    RunLogRetentionPreview(
                        policy = normalized,
                        storedCount = dao.count(),
                        prunableCount = dao.countPrunable(
                            maxEntries = normalized.maxEntries,
                            minimumTimestamp = normalized.minimumTimestamp(System.currentTimeMillis()),
                        ),
                        oldestTimestamp = dao.oldestTimestamp(),
                    )
                }
            }.onSuccess { preview ->
                if (preview.prunableCount == 0) updateRunLogRetention(preview.policy)
                else _runLogRetentionPreview.value = preview
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_retention_preview)) }
        }
    }

    fun dismissRunLogRetentionPreview() {
        _runLogRetentionPreview.value = null
    }

    fun confirmRunLogRetention() {
        val preview = _runLogRetentionPreview.value ?: return
        _runLogRetentionPreview.value = null
        updateRunLogRetention(preview.policy)
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
                    val suffix = if (deleted > 0) {
                        appContext.getString(R.string.ui_message_retention_pruned, deleted, plural(deleted))
                    } else {
                        ""
                    }
                    events.send(message(R.string.ui_message_retention_updated, suffix))
                    refreshRunLogPage()
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_retention_update)) }
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
                events.send(errorMessage(ex, R.string.ui_error_share_diagnostics))
            }
        }
    }

    fun createDatabaseBackup() {
        launchBackupOperation {
            databaseBackupManager.backup()
                .onSuccess { backup ->
                    events.send(message(R.string.ui_message_backup_created, backup.name))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_backup)) }
        }
    }

    fun exportDatabaseBackup(uri: Uri) {
        launchBackupOperation {
            val backup = databaseBackupManager.backup().getOrElse {
                events.send(errorMessage(it, R.string.ui_error_backup))
                return@launchBackupOperation
            }
            databaseBackupManager.exportBackup(backup, uri)
                .onSuccess { events.send(message(R.string.ui_message_backup_exported, backup.name)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_backup_export)) }
        }
    }

    private val _restoreReview = MutableStateFlow<RestoreReviewState?>(null)
    val restoreReview: StateFlow<RestoreReviewState?> = _restoreReview.asStateFlow()

    /**
     * Validates and summarizes the selected database, then waits for an explicit Stage decision.
     * Nothing is staged here: selection used to replace the pending journal outright, so a user
     * could not inspect the candidate, tell it apart from an earlier staged restore, or back out.
     */
    fun importDatabaseBackup(uri: Uri) {
        launchBackupOperation {
            databaseBackupManager.inspectRestore(uri)
                .onSuccess { candidate ->
                    _restoreReview.value = RestoreReviewState(
                        candidate = candidate,
                        replacesPending = databaseBackupManager.pendingRestoreSummary(),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_backup_import)) }
        }
    }

    fun confirmStageRestore() {
        launchBackupOperation {
            databaseBackupManager.stageInspectedRestore()
                .onSuccess {
                    _restoreReview.value = null
                    events.send(message(R.string.ui_message_restore_staged))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_restore_stage)) }
        }
    }

    fun dismissRestoreReview() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { databaseBackupManager.discardInspectedRestore() }
            _restoreReview.value = null
        }
    }

    /** Removes only the validated pending journal; backups and the live database are untouched. */
    fun cancelPendingRestore() {
        launchBackupOperation {
            val cancelled = withContext(Dispatchers.IO) { databaseBackupManager.cancelPendingRestore() }
            events.send(message(if (cancelled) R.string.ui_message_restore_cancelled else R.string.ui_message_no_staged_restore))
        }
    }

    private fun launchBackupOperation(block: suspend () -> Unit) {
        viewModelScope.launch {
            _backupSetupState.value = _backupSetupState.value.copy(busy = true)
            try {
                block()
            } finally {
                refreshBackupSetupState(busy = false)
            }
        }
    }

    private suspend fun refreshBackupSetupState(busy: Boolean) {
        // Backup enumeration and pending-restore checks hit the filesystem; keep them off
        // the main thread (debug StrictMode flags them otherwise).
        val loaded = withContext(Dispatchers.IO) {
            BackupSetupState(
                busy = busy,
                latestBackupName = databaseBackupManager.listBackups().firstOrNull()?.name,
                pendingRestore = databaseBackupManager.hasPendingRestore(),
                pendingRestoreSummary = databaseBackupManager.pendingRestoreSummary(),
            )
        }
        _backupSetupState.value = loaded
    }

    fun runTaskNow(task: Task) {
        viewModelScope.launch {
            val result = executeAndLogTask(
                appContext = appContext,
                db = db,
                task = task,
                source = "Manual run",
                execution = ExecutionEnvelope.create(task, "Manual run"),
            )
            val status = when {
                result.held -> "held"
                result.skippedReason != null -> "skipped"
                result.report.success -> "succeeded"
                else -> "failed"
            }
            events.send(message(R.string.ui_message_run_status, task.name, status, result.report.durationMs))
        }
    }

    fun replayHeldRun(entry: RunLogEntry) {
        viewModelScope.launch {
            runCatching {
                replayHeldExecution(
                    appContext = appContext,
                    db = db,
                    heldEntry = entry,
                )
            }.onSuccess { result ->
                val status = when {
                    result.held -> "held"
                    result.report.success -> "succeeded"
                    else -> "failed"
                }
                events.send(message(R.string.ui_message_run_replayed, entry.taskName, status, result.report.durationMs))
                refreshRunLogPage()
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_run_log_replay)) }
        }
    }

    fun setRunLogStarred(entry: RunLogEntry, starred: Boolean = !entry.starred) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { db.runLogDao().setStarred(entry.id, starred) }
            }.onSuccess { refreshRunLogPage() }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun previewTaskPreflight(task: Task) {
        startPreflight(PreflightTarget.TaskTarget(task), PreflightInputs())
    }

    fun previewProfilePreflight(profile: Profile) {
        startPreflight(PreflightTarget.ProfileTarget(profile), PreflightInputs())
    }

    fun rerunPreflight(eventVariables: Map<String, String>) {
        val current = _preflightReview.value ?: return
        startPreflight(
            target = current.target,
            inputs = current.inputs.copy(eventVariables = eventVariables),
        )
    }

    fun clearPreflightReview() {
        if (!_preflightBusy.value) _preflightReview.value = null
    }

    private fun startPreflight(target: PreflightTarget, inputs: PreflightInputs) {
        viewModelScope.launch {
            if (_preflightBusy.value) return@launch
            _preflightBusy.value = true
            runCatching {
                withContext(Dispatchers.Default) {
                    val availableTasks = tasks.value.toList()
                    val report = when (target) {
                        is PreflightTarget.TaskTarget -> PreflightRunner.preflightTask(
                            task = target.task,
                            tasks = availableTasks,
                            inputs = inputs,
                        )
                        is PreflightTarget.ProfileTarget -> PreflightRunner.preflightProfile(
                            profile = target.profile,
                            tasks = availableTasks,
                            inputs = inputs,
                        )
                    }
                    PreflightReviewState(target, inputs, report)
                }
            }.onSuccess { _preflightReview.value = it }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_preflight)) }
            _preflightBusy.value = false
        }
    }

    fun pinTaskShortcut(task: Task) {
        viewModelScope.launch {
            if (!TaskShortcutHelper.canPinShortcut(appContext)) {
                events.send(message(R.string.ui_message_shortcut_unsupported))
                return@launch
            }
            val requested = TaskShortcutHelper.requestPinShortcut(appContext, task)
            if (requested) {
                events.send(message(R.string.ui_message_shortcut_pinning, task.name))
            } else {
                events.send(message(R.string.ui_message_shortcut_failed))
            }
        }
    }

    private suspend fun transitionEdit(entityType: String, entityId: Long, redo: Boolean): Boolean = db.withTransaction {
        val history = db.editHistoryDao()
        val snapshot = if (redo) {
            history.getRedoCandidate(entityType, entityId)
        } else {
            history.getUndoCandidate(entityType, entityId)
        } ?: return@withTransaction false
        val targetJson = if (redo) snapshot.nextJson else snapshot.previousJson
        if (targetJson.isBlank()) return@withTransaction false

        when (entityType) {
            EditHistoryDao.TYPE_TASK -> {
                val current = db.taskDao().getById(entityId) ?: return@withTransaction false
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.actionsJson
                }
                db.taskDao().update(EditHistorySnapshotDecoder.task(targetJson, entityId).toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
            }

            EditHistoryDao.TYPE_PROFILE -> {
                val current = db.profileDao().getById(entityId) ?: return@withTransaction false
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.contextsJson
                }
                db.profileDao().upsert(EditHistorySnapshotDecoder.profile(targetJson, entityId).toEntity())
                locationDwellStateStore.clearProfile(entityId)
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
            }

            EditHistoryDao.TYPE_SCENE -> {
                val current = db.sceneDao().getById(entityId) ?: return@withTransaction false
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.elementsJson
                }
                db.sceneDao().update(EditHistorySnapshotDecoder.scene(targetJson, entityId).toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
            }

            else -> return@withTransaction false
        }
        true
    }

    private fun transitionEditAsync(entityType: String, entityId: Long, redo: Boolean) {
        viewModelScope.launch {
            runCatching { transitionEdit(entityType, entityId, redo) }
                .onSuccess { changed ->
                    val messageRes = when {
                        changed && redo -> R.string.ui_message_edit_redone
                        changed -> R.string.ui_message_edit_undone
                        redo -> R.string.ui_message_no_redo_history
                        else -> R.string.ui_message_no_edit_history
                    }
                    events.send(message(messageRes))
                }
                .onFailure { events.send(errorMessage(it, if (redo) R.string.ui_error_redo else R.string.ui_error_undo)) }
        }
    }

    fun undoLastTaskEdit(taskId: Long) = transitionEditAsync(EditHistoryDao.TYPE_TASK, taskId, redo = false)
    fun redoLastTaskEdit(taskId: Long) = transitionEditAsync(EditHistoryDao.TYPE_TASK, taskId, redo = true)
    fun undoLastProfileEdit(profileId: Long) = transitionEditAsync(EditHistoryDao.TYPE_PROFILE, profileId, redo = false)
    fun redoLastProfileEdit(profileId: Long) = transitionEditAsync(EditHistoryDao.TYPE_PROFILE, profileId, redo = true)
    fun undoLastSceneEdit(sceneId: Long) = transitionEditAsync(EditHistoryDao.TYPE_SCENE, sceneId, redo = false)
    fun redoLastSceneEdit(sceneId: Long) = transitionEditAsync(EditHistoryDao.TYPE_SCENE, sceneId, redo = true)

    fun updateVariable(
        previousName: String?,
        name: String,
        value: String,
        isSecret: Boolean,
        successMessage: String,
        projectId: Long = DEFAULT_PROJECT_ID,
    ) {
        viewModelScope.launch {
            runCatching {
                val globalName = requireNotNull(VariableNamePolicy.promoteToGlobal(name)) {
                    appContext.getString(R.string.ui_error_invalid_variable_name)
                }
                val updated = Variable(
                    globalName,
                    value,
                    isGlobal = true,
                    isSecret = isSecret,
                    projectId = projectId,
                )
                val previous = previousName?.let {
                    variableRepository.get(it, projectId)
                        ?: throw IllegalStateException("Variable '%$it' no longer exists.")
                }
                if (previous == null || previous.name == globalName) {
                    variableRepository.upsert(updated)
                } else {
                    db.withTransaction {
                        val profiles = db.profileDao().getAll().map { entity ->
                            val decoded = entity.toDomainDecodeResult()
                            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
                            decoded.value
                        }
                        val tasks = db.taskDao().getAll().map { entity ->
                            val decoded = entity.toDomainDecodeResult()
                            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
                            decoded.value
                        }
                        val scenes = db.sceneDao().getAll().map { entity ->
                            val decoded = entity.toDomainDecodeResult()
                            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
                            decoded.value
                        }
                        val rewrite = AutomationReferenceRewriter.renameVariable(
                            target = previous,
                            replacementName = globalName,
                            profiles = profiles,
                            tasks = tasks,
                            scenes = scenes,
                        )
                        rewrite.profiles.forEach { rewritten ->
                            val current = profiles.first { it.id == rewritten.id }
                            recordEdit(
                                entityType = EditHistoryDao.TYPE_PROFILE,
                                entityId = rewritten.id,
                                previousJson = StorageJson.encodeToString(current),
                                nextJson = StorageJson.encodeToString(rewritten),
                            )
                            db.profileDao().upsert(rewritten.toEntity())
                        }
                        rewrite.tasks.forEach { rewritten ->
                            val current = tasks.first { it.id == rewritten.id }
                            recordEdit(
                                entityType = EditHistoryDao.TYPE_TASK,
                                entityId = rewritten.id,
                                previousJson = StorageJson.encodeToString(current),
                                nextJson = StorageJson.encodeToString(rewritten),
                            )
                            db.taskDao().update(rewritten.toEntity())
                        }
                        rewrite.scenes.forEach { rewritten ->
                            val current = scenes.first { it.id == rewritten.id }
                            recordEdit(
                                entityType = EditHistoryDao.TYPE_SCENE,
                                entityId = rewritten.id,
                                previousJson = StorageJson.encodeToString(current),
                                nextJson = StorageJson.encodeToString(rewritten),
                            )
                            db.sceneDao().update(rewritten.toEntity())
                        }
                        variableRepository.rename(previous.name, updated)
                    }
                }
                events.send(legacyMessage(successMessage))
            }.onFailure { error ->
                events.send(errorMessage(error, R.string.ui_error_variable_save))
            }
        }
    }

    fun deleteVariable(name: String, successMessage: String, projectId: Long = DEFAULT_PROJECT_ID) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val variable = variableRepository.get(name, projectId)
                        ?: throw IllegalStateException("Variable '%$name' no longer exists.")
                    val profiles = db.profileDao().getAll().map { entity ->
                        val decoded = entity.toDomainDecodeResult()
                        decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
                        decoded.value
                    }
                    val tasks = db.taskDao().getAll().map { entity ->
                        val decoded = entity.toDomainDecodeResult()
                        decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
                        decoded.value
                    }
                    val scenes = db.sceneDao().getAll().map { entity ->
                        val decoded = entity.toDomainDecodeResult()
                        decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
                        decoded.value
                    }
                    val guard = AutomationReferenceRewriter.guardVariableDeletion(
                        target = variable,
                        profiles = profiles,
                        tasks = tasks,
                        scenes = scenes,
                    )
                    if (!guard.canCommit) {
                        val sites = guard.blocked.map { it.describe() }.distinct().joinToString("; ")
                        throw IllegalStateException("Cannot delete %${variable.name}; it is referenced by: $sites")
                    }
                    variableRepository.delete(variable.name, projectId)
                }
            }
                .onSuccess { events.send(legacyMessage(successMessage)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_variable_delete)) }
        }
    }

    /**
     * Rejects a profile whose per-field limits (name length, cooldown range) are out of bounds
     * before it is written. Structural completeness (enter task, contexts) is intentionally not
     * gated here so incremental editing can save partial profiles; the engine no-ops on those.
     */
    private fun requireValidProfileFieldLimits(profile: Profile) {
        val violation = InputValidation.validateProfile(profile)
            .firstOrNull { it.field == "name" || it.field == "cooldownSec" }
        if (violation != null) {
            throw IllegalArgumentException(violation.message)
        }
    }

    private fun launchWithMessage(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { events.send(legacyMessage(successMessage)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }
}

internal const val PROFILE_SHARE_MAX_SCREENSHOTS = 6

internal fun defaultProfileShareSlug(name: String): String {
    val slug = name
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)
    return slug.takeIf { it.length >= 3 } ?: "opentasker-share"
}

internal fun reorderActions(actions: List<ActionSpec>, fromIndex: Int, toIndex: Int): List<ActionSpec> {
    require(fromIndex in actions.indices) { "Source action index is out of range." }
    require(toIndex in actions.indices) { "Destination action index is out of range." }
    if (fromIndex == toIndex) return actions
    return actions.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
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

internal fun readBoundedTaskerXml(context: Context, uri: Uri): String {
    return readBoundedDocumentText(
        context = context,
        uri = uri,
        maxBytes = TASKER_XML_IMPORT_MAX_BYTES,
        label = "Tasker XML file",
    )
}

internal fun readBoundedOpenTaskerBundle(context: Context, uri: Uri): String {
    return readBoundedDocumentText(
        context = context,
        uri = uri,
        maxBytes = OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES,
        label = "OpenTasker bundle",
    )
}

internal fun readBoundedDocumentText(context: Context, uri: Uri, maxBytes: Int, label: String): String {
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

private fun plural(count: Int): String = if (count == 1) "" else "s"
