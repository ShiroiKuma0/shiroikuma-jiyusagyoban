package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.core.capabilities.AutomationFeedbackRiskAnalyzer
import com.opentasker.core.capabilities.ImportedProfileEnablePolicy
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.diagnostics.DiagnosticExport
import com.opentasker.core.diagnostics.CrashLogHandler
import com.opentasker.core.diagnostics.CrashLogRecord
import com.opentasker.core.diagnostics.EngineHealthReader
import com.opentasker.core.diagnostics.EngineHealthStatus
import com.opentasker.core.diagnostics.RunLogExportFormat
import com.opentasker.core.diagnostics.RunLogExporter
import com.opentasker.core.engine.ActiveExecution
import com.opentasker.core.engine.ActiveExecutionRegistry
import com.opentasker.core.engine.ExecutionEnvelope
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Profile
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
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.RestoreCandidate
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.RunLogQuery
import com.opentasker.core.storage.RunLogSnapshot
import com.opentasker.core.storage.RunLogTaskOption
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.VariableRepository
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

internal data class OpenTaskerBundleReviewState(
    val bundle: OpenTaskerBundle,
    val plan: BundleImportPlan,
    val variableResolutions: Map<String, VariableConflictResolution> = emptyMap(),
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
            events.send(if (cancelled) "Cancelling automation" else "That automation already finished")
        }
    }

    val globalVariables: StateFlow<ImmutableList<Variable>> = variableRepository
        .observeGlobals()
        .map { variables -> variables.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    private val events = Channel<String>(Channel.BUFFERED)
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

    init {
        refreshRunLogPage()
        viewModelScope.launch {
            runCatching { pruneRunLogs(_runLogRetentionPolicy.value) }
        }
        viewModelScope.launch {
            runCatching { refreshBackupSetupState(busy = false) }
        }
        refreshDiagnostics()
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
                events.send("Error: ${error.message ?: "Diagnostics could not be refreshed"}")
            }
        }
    }

    fun createTask(name: String, priority: Int, collisionMode: CollisionMode) = launchWithMessage("Task created") {
        db.taskDao().insert(
            Task(
                name = name.trim(),
                priority = priority.coerceIn(0, 10),
                collisionMode = collisionMode,
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
                db.editHistoryDao().insert(
                    EditHistoryEntity(
                        entityType = EditHistoryDao.TYPE_TASK,
                        entityId = task.id,
                        previousJson = previous.actionsJson,
                    ),
                )
                db.editHistoryDao().pruneOld(EditHistoryDao.TYPE_TASK, task.id)

                // A rename breaks every reference that still names this task ("task.run" targets,
                // legacy notification bindings). Pin those to the stable id in the same
                // transaction so they cannot dangle or be captured by a future task that takes the
                // old name.
                val previousTask = previous.toDomain()
                if (!previousTask.name.equals(task.name, ignoreCase = true)) {
                    val rewrite = AutomationReferenceRewriter.stabilizeNameReferences(
                        target = previousTask,
                        profiles = db.profileDao().getAll().map { it.toDomain() },
                        tasks = db.taskDao().getAll().map { it.toDomain() },
                        scenes = db.sceneDao().getAll().map { it.toDomain() },
                    )
                    rewrite.profiles.forEach { db.profileDao().update(it.toEntity()) }
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
            db.editHistoryDao().insert(
                EditHistoryEntity(
                    entityType = EditHistoryDao.TYPE_TASK,
                    entityId = taskId,
                    previousJson = entity.actionsJson,
                ),
            )
            db.editHistoryDao().pruneOld(EditHistoryDao.TYPE_TASK, taskId)
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
                    rewrite.profiles.forEach { db.profileDao().update(it.toEntity()) }
                    rewrite.tasks.forEach { db.taskDao().update(it.toEntity()) }
                    rewrite.scenes.forEach { db.sceneDao().update(it.toEntity()) }
                    db.taskDao().delete(task.toEntity())
                }
                blockedCount
            }
                .onSuccess { blocked ->
                    if (blocked > 0) {
                        events.send("Task is still used by $blocked automation(s). Reassign or clear those references first.")
                    } else {
                        LocaleGrantStore(appContext).revokeAllForTask(task.id)
                        events.send("Task deleted")
                    }
                }
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
        db.withTransaction {
            val previous = scene.id.takeIf { it > 0L }?.let { db.sceneDao().getById(it) }
            if (previous != null) {
                previous.toDomainDecodeResult().issue?.let { issue ->
                    throw CorruptRecordOverwriteException(issue)
                }
                db.editHistoryDao().insert(
                    EditHistoryEntity(
                        entityType = EditHistoryDao.TYPE_SCENE,
                        entityId = scene.id,
                        previousJson = previous.elementsJson,
                    ),
                )
                db.editHistoryDao().pruneOld(EditHistoryDao.TYPE_SCENE, scene.id)
            }
            db.sceneDao().update(scene.toEntity())
        }
    }

    fun deleteScene(scene: Scene) = launchWithMessage("Scene deleted") {
        db.sceneDao().delete(scene.toEntity())
    }

    fun createProfile(name: String, enabled: Boolean, enterTaskId: Long, exitTaskId: Long?, cooldownSec: Int, automationMode: AutomationMode, group: String? = null) =
        launchWithMessage("Profile created") {
            val profile = Profile(
                name = name.trim(),
                enabled = enabled,
                enterTaskId = enterTaskId,
                exitTaskId = exitTaskId,
                cooldownSec = cooldownSec.coerceAtLeast(0),
                automationMode = automationMode,
                group = group,
            )
            requireValidProfileFieldLimits(profile)
            db.profileDao().insert(reviewFeedbackRisk(profile).toEntity())
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
                db.profileDao().update(reviewedProfile.toEntity())
            }
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
            db.profileDao().update(
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
                    OpenTaskerBundleReviewState(bundle = bundle, plan = bundleRepository.planImport(bundle))
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
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}, " +
                            "${importReport.insertedScenes} scene${plural(importReport.insertedScenes)}"
                    )
                }
                .onFailure { events.send("Error: ${it.message ?: "OpenTasker bundle import failed"}") }
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
                events.send("Error: ${error.message ?: "Run logs could not be loaded"}")
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
                events.send("Error: ${error.message ?: "More run logs could not be loaded"}")
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
                events.send("Exported $exported run log entr${if (exported == 1) "y" else "ies"}")
            } catch (error: Exception) {
                events.send("Error: ${error.message ?: "Run log export failed"}")
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
            }.onFailure { events.send("Error: ${it.message ?: "Retention preview failed"}") }
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
                    val suffix = if (deleted > 0) "; pruned $deleted old entry${plural(deleted)}" else ""
                    events.send("Run log retention updated$suffix")
                    refreshRunLogPage()
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
        launchBackupOperation {
            databaseBackupManager.backup()
                .onSuccess { backup ->
                    events.send("Backup created: ${backup.name}")
                }
                .onFailure { events.send("Error: ${it.message ?: "Database backup failed"}") }
        }
    }

    fun exportDatabaseBackup(uri: Uri) {
        launchBackupOperation {
            val backup = databaseBackupManager.backup().getOrElse {
                events.send("Error: ${it.message ?: "Database backup failed"}")
                return@launchBackupOperation
            }
            databaseBackupManager.exportBackup(backup, uri)
                .onSuccess { events.send("Backup exported: ${backup.name}") }
                .onFailure { events.send("Error: ${it.message ?: "Database backup export failed"}") }
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
                .onFailure { events.send("Error: ${it.message ?: "Database backup import failed"}") }
        }
    }

    fun confirmStageRestore() {
        launchBackupOperation {
            databaseBackupManager.stageInspectedRestore()
                .onSuccess {
                    _restoreReview.value = null
                    events.send("Restore staged. Restart OpenTasker to apply it.")
                }
                .onFailure { events.send("Error: ${it.message ?: "Staging the restore failed"}") }
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
            events.send(
                if (cancelled) "Staged restore cancelled" else "There is no staged restore to cancel",
            )
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
                result.skippedReason != null -> "skipped"
                result.report.success -> "succeeded"
                else -> "failed"
            }
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

    fun undoLastTaskEdit(taskId: Long) {
        viewModelScope.launch {
            runCatching {
                val snapshot = db.editHistoryDao().getLatest(EditHistoryDao.TYPE_TASK, taskId)
                    ?: return@runCatching false
                val current = db.taskDao().getById(taskId) ?: return@runCatching false
                db.taskDao().update(current.copy(actionsJson = snapshot.previousJson))
                db.editHistoryDao().deleteFor(EditHistoryDao.TYPE_TASK, taskId)
                true
            }.onSuccess { undone ->
                events.send(if (undone) "Edit undone" else "No edit history available")
            }.onFailure { events.send("Error: ${it.message ?: "Undo failed"}") }
        }
    }

    fun undoLastProfileEdit(profileId: Long) {
        viewModelScope.launch {
            runCatching {
                val snapshot = db.editHistoryDao().getLatest(EditHistoryDao.TYPE_PROFILE, profileId)
                    ?: return@runCatching false
                val current = db.profileDao().getById(profileId) ?: return@runCatching false
                db.profileDao().update(current.copy(contextsJson = snapshot.previousJson))
                db.editHistoryDao().deleteFor(EditHistoryDao.TYPE_PROFILE, profileId)
                true
            }.onSuccess { undone ->
                events.send(if (undone) "Edit undone" else "No edit history available")
            }.onFailure { events.send("Error: ${it.message ?: "Undo failed"}") }
        }
    }

    fun updateVariable(name: String, value: String, isSecret: Boolean, successMessage: String) {
        viewModelScope.launch {
            runCatching {
                val globalName = requireNotNull(VariableNamePolicy.promoteToGlobal(name)) {
                    "Invalid variable name"
                }
                variableRepository.upsert(Variable(globalName, value, isGlobal = true, isSecret = isSecret))
                events.send(successMessage)
            }.onFailure { error ->
                events.send("Error: ${error.message ?: "Variable could not be saved"}")
            }
        }
    }

    fun deleteVariable(name: String, successMessage: String) {
        viewModelScope.launch {
            runCatching { variableRepository.delete(name) }
                .onSuccess { events.send(successMessage) }
                .onFailure { events.send("Error: ${it.message ?: "Variable could not be deleted"}") }
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
                .onSuccess { events.send(successMessage) }
                .onFailure { events.send("Error: ${it.message ?: "Operation failed"}") }
        }
    }
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
