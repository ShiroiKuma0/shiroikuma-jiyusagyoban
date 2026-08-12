package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.annotation.SuppressLint
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.app.R
import com.opentasker.core.capabilities.AutomationFeedbackRiskAnalyzer
import com.opentasker.core.capabilities.AutomationLint
import com.opentasker.core.capabilities.AutomationLintReport
import com.opentasker.core.capabilities.AutomationLintSeverity
import com.opentasker.core.capabilities.AutomationLintStrings
import com.opentasker.core.capabilities.AutomationInvariantStore
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
import com.opentasker.core.engine.ExecutionAdmissionRegistry
import com.opentasker.core.engine.ExecutionAdmissionSnapshot
import com.opentasker.core.engine.PreflightInputs
import com.opentasker.core.engine.PreflightReport
import com.opentasker.core.engine.PreflightRunner
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.engine.replayHeldExecution
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileOverflowPolicy
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
import kotlinx.serialization.SerializationException
import com.opentasker.core.plugins.locale.LocaleConditionGrantStore
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.diff.AutomationSemanticDiff
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.diff.SemanticDiffEntry
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.references.AutomationDuplicator
import com.opentasker.core.references.AutomationDuplicateStrings
import com.opentasker.core.references.AutomationReferenceRewriter
import com.opentasker.core.references.ReferenceResolution
import com.opentasker.core.references.TaskReference
import com.opentasker.core.references.describe
import com.opentasker.core.sharing.ProfileShareDraft
import com.opentasker.core.sharing.ProfileShareLibrary
import com.opentasker.core.sharing.ProfileShareManifest
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.ConfigurationSnapshotPolicy
import com.opentasker.core.storage.ConfigurationSnapshotSettings
import com.opentasker.core.storage.ConfigurationSnapshotWorker
import com.opentasker.core.storage.configureConfigurationSnapshotDestination
import com.opentasker.core.storage.CorruptStoredRecordException
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.StorageDecodeResult
import com.opentasker.core.storage.applyRetention
import com.opentasker.core.storage.FallbackTaskSettings
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
import com.opentasker.core.templates.BlueprintCatalogStore
import com.opentasker.core.templates.BlueprintInstallation
import com.opentasker.core.templates.BlueprintInstallationStore
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import com.opentasker.core.transfer.OpenTaskerBundleTextImport
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerXmlExporter
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.core.transfer.VariableConflictResolution
import com.opentasker.widget.TaskShortcutHelper
import com.opentasker.widget.TaskWidgetProvider
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
import kotlinx.coroutines.delay
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
sealed interface UiMessageAction {
    data class Undo(
        val entityType: String,
        val entityId: Long,
    ) : UiMessageAction
}

internal data class OpenTaskerBundleReviewState(
    val bundle: OpenTaskerBundle,
    val plan: BundleImportPlan,
    val variableResolutions: Map<String, VariableConflictResolution> = emptyMap(),
)

internal data class SemanticDiffReviewState(
    val document: SemanticDiffDocument,
)

private fun documentOf(entry: SemanticDiffEntry): SemanticDiffDocument = SemanticDiffDocument(listOf(entry))

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
    val admission: ExecutionAdmissionSnapshot? = null,
    val crashLogs: List<CrashLogRecord> = emptyList(),
    val appLogs: List<AppLogEntry> = emptyList(),
    val loadedAtMillis: Long = 0L,
    /** Resolves admission rows to profile names; they previously showed raw Room ids. */
    val profileNames: Map<Long, String> = emptyMap(),
)

class ActiveAutomationViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {
    private val locationDwellStateStore = LocationDwellStateStore(appContext)
    private val variableRepository = VariableRepository(db.variableDao())
    private val blueprintCatalogStore = BlueprintCatalogStore(appContext)
    private val blueprintInstallationStore = BlueprintInstallationStore(appContext)
    private val invariantStore = AutomationInvariantStore(appContext)
    private val bundleRepository = OpenTaskerBundleRepository(
        db = db,
        variableRepository = variableRepository,
        blueprintCatalogStore = blueprintCatalogStore,
        blueprintInstallationStore = blueprintInstallationStore,
        invariantStore = invariantStore,
    )
    private val runLogRetentionSettings = RunLogRetentionSettings(appContext)
    private val fallbackTaskSettings = FallbackTaskSettings(appContext)
    private val databaseBackupManager = DatabaseBackupManager(appContext, db)

    private fun message(@StringRes resId: Int, vararg args: Any): UiMessage =
        UiMessage(resId, args.toList())

    private fun pluralMessage(@PluralsRes resId: Int, quantity: Int, vararg args: Any): UiMessage =
        UiMessage(resId, args.toList(), quantity)

    private fun errorMessage(error: Throwable, fallbackRes: Int): UiMessage {
        AppLogger.error("OpenTasker.UI", "Operation failed", error)
        return uiErrorMessage(error, fallbackRes)
    }

    private suspend fun recordEdit(entityType: String, entityId: Long, previousJson: String, nextJson: String) =
        db.editHistoryDao().recordEdit(entityType, entityId, previousJson, nextJson)

    private suspend fun recordCreation(entityType: String, entityId: Long, nextJson: String) =
        db.editHistoryDao().recordCreation(entityType, entityId, nextJson)

    private suspend fun recordDeletion(entityType: String, entityId: Long, previousJson: String) =
        db.editHistoryDao().recordDeletion(entityType, entityId, previousJson)

    /** See [contentLoadedSignal]: screens gate first-run empty states on this. */
    val contentLoaded: StateFlow<Boolean> = contentLoadedSignal(db, viewModelScope)

    /** See [editHistoryAvailability]: Undo/Redo are enabled only where there is history. */
    val historyAvailability: StateFlow<EditHistoryAvailabilityState> = editHistoryAvailability(db, viewModelScope)

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

    private val _invariants = MutableStateFlow(invariantStore.load())
    val invariants: StateFlow<List<AutomationInvariant>> = _invariants.asStateFlow()

    fun updateAutomationInvariants(value: List<AutomationInvariant>) {
        _invariants.value = invariantStore.save(value)
    }

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

    private val _globalFallbackTaskId = MutableStateFlow(fallbackTaskSettings.loadTaskId())
    val globalFallbackTaskId: StateFlow<Long?> = _globalFallbackTaskId.asStateFlow()

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

    private val _semanticDiffReview = MutableStateFlow<SemanticDiffReviewState?>(null)
    internal val semanticDiffReview: StateFlow<SemanticDiffReviewState?> = _semanticDiffReview.asStateFlow()

    /**
     * Nodes the last reviewed undo/redo touched, highlighted on the Flow tab.
     *
     * This deliberately outlives [semanticDiffReview]: the diff dialog's scrim covers Flow and
     * closing it is the only way to reach the tab, so keys tied to the dialog's lifetime made the
     * highlight - and the dialog's own "highlighted in Flow" note - unreachable. The next edit
     * replaces them.
     */
    private val _highlightedFlowNodeKeys = MutableStateFlow<Set<String>>(emptySet())
    internal val highlightedFlowNodeKeys: StateFlow<Set<String>> = _highlightedFlowNodeKeys.asStateFlow()

    /** The profile a synthetic-trigger simulation is running against; survives rotation. */
    private val _simulationProfile = MutableStateFlow<Profile?>(null)
    internal val simulationProfile: StateFlow<Profile?> = _simulationProfile.asStateFlow()

    fun openSimulation(profile: Profile) {
        _simulationProfile.value = profile
    }

    fun clearSimulation() {
        _simulationProfile.value = null
    }

    private val _profileShareReview = MutableStateFlow<ProfileShareReviewState?>(null)
    internal val profileShareReview: StateFlow<ProfileShareReviewState?> = _profileShareReview.asStateFlow()

    private val _preflightReview = MutableStateFlow<PreflightReviewState?>(null)
    internal val preflightReview: StateFlow<PreflightReviewState?> = _preflightReview.asStateFlow()

    /**
     * Guards the one-shot run actions. Their buttons stay enabled while the coroutine is in
     * flight, so a double tap ran the task - or replayed a held execution - twice, with real
     * side effects each time.
     */
    private val _runActionBusy = MutableStateFlow(false)
    val runActionBusy: StateFlow<Boolean> = _runActionBusy.asStateFlow()

    private val _preflightBusy = MutableStateFlow(false)
    val preflightBusy: StateFlow<Boolean> = _preflightBusy.asStateFlow()

    init {
        refreshRunLogPage()
        viewModelScope.launch {
            runCatching { pruneRunLogs(_runLogRetentionPolicy.value) }
        }
        viewModelScope.launch {
            runCatching { reconcileGlobalFallbackTask() }
        }
        viewModelScope.launch {
            ConfigurationSnapshotSettings(appContext).changes().collect {
                runCatching { refreshBackupSetupState(busy = false) }
            }
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
                        admission = ExecutionAdmissionRegistry.snapshot(appContext),
                        crashLogs = CrashLogHandler.listCrashLogs(appContext),
                        appLogs = AppLogger.snapshot().takeLast(100).map { entry ->
                            entry.copy(message = DiagnosticExport.redactSensitive(entry.message))
                        },
                        loadedAtMillis = System.currentTimeMillis(),
                        profileNames = db.profileDao().getAll().associate { it.id to it.name },
                    )
                }
            }.onSuccess { state ->
                _diagnosticsState.value = state
            }.onFailure { error ->
                events.send(errorMessage(error, R.string.ui_error_diagnostics_refresh))
            }
        }
    }

    fun createTask(
        name: String,
        priority: Int,
        collisionMode: CollisionMode,
        projectId: Long = DEFAULT_PROJECT_ID,
        onSaved: () -> Unit = {},
    ) = launchWithMessage(R.string.ui_message_task_created, onSaved = onSaved) {
        db.taskDao().insert(
            Task(
                name = name.trim(),
                priority = priority.coerceIn(0, 10),
                collisionMode = collisionMode,
                projectId = projectId,
            ).toEntity(),
        )
    }

    fun duplicateTask(task: Task) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val source = db.taskDao().getById(task.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Task no longer exists.")
                    val name = AutomationDuplicator.copyName(
                        source.name,
                        db.taskDao().getAll().map { it.name },
                        AutomationDuplicateStrings.from(appContext.resources),
                    )
                    val staged = AutomationDuplicator.taskPayload(source, name)
                    val newId = db.taskDao().insert(staged.toEntity())
                    val duplicate = AutomationReferenceRewriter.remapDuplicateSelfReferences(
                        original = source,
                        duplicate = staged.copy(id = newId),
                    )
                    db.taskDao().update(duplicate.toEntity())
                    recordCreation(EditHistoryDao.TYPE_TASK, newId, StorageJson.encodeToString(duplicate))
                }
            }.onSuccess { events.send(message(R.string.ui_message_task_duplicated)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun updateTask(
        task: Task,
        @StringRes successMessageRes: Int = R.string.ui_message_task_updated,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
    ) = launchWithMessage(
        successMessageRes,
        successAction,
        // A rename leaves every task widget showing the old label until something asks them to
        // re-read it; nothing else does.
        onSaved = { TaskWidgetProvider.requestRefresh(appContext); onSaved() },
    ) {
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

    fun moveTaskAction(taskId: Long, fromIndex: Int, toIndex: Int) = launchWithMessage(R.string.ui_message_action_moved) {
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

    fun removeTaskAction(task: Task, index: Int) {
        require(index in task.actions.indices) { "Action index is out of range." }
        updateTask(
            task.copy(actions = task.actions.filterIndexed { actionIndex, _ -> actionIndex != index }),
            R.string.ui_message_action_removed,
            UiMessageAction.Undo(EditHistoryDao.TYPE_TASK, task.id),
        )
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
                globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
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
                var rewrittenGlobalFallbackTaskId: Long? = null
                var globalFallbackChanged = false
                db.withTransaction {
                    val currentTask = db.taskDao().getById(task.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Task no longer exists.")
                    val profiles = db.profileDao().getAll().map { it.toDomain() }
                    val tasks = db.taskDao().getAll().map { it.toDomain() }
                    val scenes = db.sceneDao().getAll().map { it.toDomain() }
                    val rewrite = AutomationReferenceRewriter.retarget(
                        target = currentTask,
                        resolution = resolution,
                        profiles = profiles,
                        tasks = tasks,
                        scenes = scenes,
                        globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                    )
                    if (!rewrite.canCommit) {
                        blockedCount = rewrite.blocked.size
                        return@withTransaction
                    }
                    recordDeletion(
                        entityType = EditHistoryDao.TYPE_TASK,
                        entityId = currentTask.id,
                        previousJson = StorageJson.encodeToString(currentTask),
                    )
                    rewrite.profiles.forEach { db.profileDao().upsert(it.toEntity()) }
                    rewrite.tasks.forEach { db.taskDao().update(it.toEntity()) }
                    rewrite.scenes.forEach { db.sceneDao().update(it.toEntity()) }
                    db.taskDao().delete(currentTask.toEntity())
                    rewrittenGlobalFallbackTaskId = rewrite.globalFallbackTaskId
                    globalFallbackChanged = rewrite.globalFallbackChanged
                }
                if (blockedCount == 0 && globalFallbackChanged) {
                    fallbackTaskSettings.saveTaskId(rewrittenGlobalFallbackTaskId)
                    _globalFallbackTaskId.value = rewrittenGlobalFallbackTaskId
                }
                blockedCount
            }
                .onSuccess { blocked ->
                    if (blocked > 0) {
                        events.send(message(R.string.ui_task_still_used, blocked))
                    } else {
                        LocaleGrantStore(appContext).revokeAllForTask(task.id)
                        // Otherwise a widget bound to this task keeps looking runnable and only
                        // answers a tap with "Task not found".
                        TaskWidgetProvider.requestRefresh(appContext)
                        events.send(
                            UiMessage(
                                resId = R.string.ui_message_task_deleted,
                                action = UiMessageAction.Undo(EditHistoryDao.TYPE_TASK, task.id),
                            ),
                        )
                    }
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_task_delete)) }
        }
    }

    fun createScene(name: String, widthDp: Int, heightDp: Int, projectId: Long = DEFAULT_PROJECT_ID) = launchWithMessage(R.string.ui_message_scene_created) {
        db.sceneDao().insert(
            Scene(
                name = name.trim(),
                widthDp = widthDp.coerceIn(120, 1440),
                heightDp = heightDp.coerceIn(80, 2560),
                projectId = projectId,
            ).toEntity()
        )
    }

    fun duplicateScene(scene: Scene) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val source = db.sceneDao().getById(scene.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Scene no longer exists.")
                    val name = AutomationDuplicator.copyName(
                        source.name,
                        db.sceneDao().getAll().map { it.name },
                        AutomationDuplicateStrings.from(appContext.resources),
                    )
                    val duplicate = AutomationDuplicator.scenePayload(source, name)
                    val newId = db.sceneDao().insert(duplicate.toEntity())
                    val persisted = duplicate.copy(id = newId)
                    db.sceneDao().update(persisted.toEntity())
                    recordCreation(EditHistoryDao.TYPE_SCENE, newId, StorageJson.encodeToString(persisted))
                }
            }.onSuccess { events.send(message(R.string.ui_message_scene_duplicated)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun removeSceneElement(scene: Scene, index: Int) {
        require(index in scene.elements.indices) { "Scene element index is out of range." }
        updateScene(
            scene.copy(elements = scene.elements.filterIndexed { elementIndex, _ -> elementIndex != index }),
            R.string.ui_message_element_removed,
            UiMessageAction.Undo(EditHistoryDao.TYPE_SCENE, scene.id),
        )
    }

    fun updateScene(
        scene: Scene,
        @StringRes successMessageRes: Int = R.string.ui_message_scene_updated,
        successAction: UiMessageAction? = null,
    ) = launchWithMessage(successMessageRes, successAction) {
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

    fun deleteScene(scene: Scene) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val current = db.sceneDao().getById(scene.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Scene no longer exists.")
                    recordDeletion(
                        entityType = EditHistoryDao.TYPE_SCENE,
                        entityId = current.id,
                        previousJson = StorageJson.encodeToString(current),
                    )
                    db.sceneDao().delete(current.toEntity())
                }
            }.onSuccess {
                events.send(
                    UiMessage(
                        resId = R.string.ui_message_scene_deleted,
                        action = UiMessageAction.Undo(EditHistoryDao.TYPE_SCENE, scene.id),
                    ),
                )
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun createProfile(
        name: String,
        enabled: Boolean,
        enterTaskId: Long,
        exitTaskId: Long?,
        cooldownSec: Int,
        automationMode: AutomationMode,
        group: String? = null,
        projectId: Long = DEFAULT_PROJECT_ID,
        priority: Int = 0,
        gracePeriodSec: Int = 0,
        lifetime: ProfileLifetime = ProfileLifetime.NEVER,
        expiresAtMs: Long? = null,
        maxActiveExecutions: Int? = null,
        burstLimit: Int? = null,
        overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
        fallbackTaskId: Long? = null,
        onSaved: () -> Unit = {},
    ) =
        launchWithMessage(R.string.ui_message_profile_created, onSaved = onSaved) {
            val profile = ProfileLifecyclePolicy.normalize(
                Profile(
                name = name.trim(),
                enabled = enabled,
                enterTaskId = enterTaskId,
                exitTaskId = exitTaskId,
                cooldownSec = cooldownSec.coerceAtLeast(0),
                automationMode = automationMode,
                group = group,
                projectId = projectId,
                priority = priority,
                gracePeriodSec = gracePeriodSec,
                lifetime = lifetime,
                expiresAtMs = expiresAtMs,
                maxActiveExecutions = maxActiveExecutions,
                burstLimit = burstLimit,
                overflowPolicy = overflowPolicy,
                fallbackTaskId = fallbackTaskId,
                ),
            )
            requireValidProfileFieldLimits(profile)
            val lint = requireAutomationLint(profile)
            db.profileDao().upsert(reviewFeedbackRisk(profile).toEntity())
            emitLintWarnings(profile, lint)
        }

    fun duplicateProfile(profile: Profile) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val source = db.profileDao().getById(profile.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Profile no longer exists.")
                    val name = AutomationDuplicator.copyName(
                        source.name,
                        db.profileDao().getAll().map { it.name },
                        AutomationDuplicateStrings.from(appContext.resources),
                    )
                    val duplicate = AutomationDuplicator.profilePayload(source, name)
                    requireValidProfileFieldLimits(duplicate)
                    val newId = db.profileDao().insert(duplicate.toEntity())
                    val persisted = duplicate.copy(id = newId)
                    recordCreation(EditHistoryDao.TYPE_PROFILE, newId, StorageJson.encodeToString(persisted))
                }
            }.onSuccess { events.send(message(R.string.ui_message_profile_duplicated)) }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun updateProfile(
        profile: Profile,
        @StringRes successMessageRes: Int = R.string.ui_message_profile_updated,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
    ) = launchWithMessage(successMessageRes, successAction, onSaved) {
            val reviewedProfile = reviewFeedbackRisk(ProfileLifecyclePolicy.normalize(profile))
            requireValidProfileFieldLimits(reviewedProfile)
            val lint = requireAutomationLint(reviewedProfile)
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
                    previous.contexts.indices.forEach { index ->
                        LocaleConditionGrantStore(appContext).revokeAllForBinding(
                            LocaleConditionGrantStore.contextKey(profile.id, index),
                        )
                    }
                }
                db.profileDao().upsert(reviewedProfile.toEntity())
            }
            emitLintWarnings(reviewedProfile, lint)
        }

    fun removeProfileContext(profile: Profile, index: Int) {
        require(index in profile.contexts.indices) { "Context index is out of range." }
        updateProfile(
            profile.copy(
                contexts = profile.contexts.filterIndexed { contextIndex, _ -> contextIndex != index },
                contextExpression = profile.contextExpression?.removeLeaf(index),
            ),
            R.string.ui_message_context_removed,
            UiMessageAction.Undo(EditHistoryDao.TYPE_PROFILE, profile.id),
        )
    }

    fun createProject(name: String, onSaved: () -> Unit = {}) = launchWithMessage(R.string.ui_message_project_created, onSaved = onSaved) {
        val normalized = validateProjectName(name)
        require(db.projectDao().getAll().none { it.name.equals(normalized, ignoreCase = true) }) {
            "A project with that name already exists."
        }
        val nextPosition = (db.projectDao().getAll().maxOfOrNull { it.position } ?: -1) + 1
        db.projectDao().insert(ProjectEntity(name = normalized, position = nextPosition))
    }

    fun renameProject(project: Project, name: String) = launchWithMessage(R.string.ui_message_project_renamed) {
        require(project.id != DEFAULT_PROJECT_ID) { "The Default project cannot be renamed." }
        val normalized = validateProjectName(name)
        require(db.projectDao().getAll().none { it.id != project.id && it.name.equals(normalized, ignoreCase = true) }) {
            "A project with that name already exists."
        }
        db.projectDao().update(ProjectEntity(project.id, normalized, project.position))
    }

    fun reorderProject(project: Project, direction: Int) = launchWithMessage(R.string.ui_message_project_reordered) {
        val ordered = db.projectDao().getAll().sortedWith(compareBy<ProjectEntity> { it.position }.thenBy { it.id })
        val index = ordered.indexOfFirst { it.id == project.id }
        val targetIndex = (index + direction.coerceIn(-1, 1)).coerceIn(0, ordered.lastIndex)
        if (index < 0 || targetIndex == index) return@launchWithMessage
        val other = ordered[targetIndex]
        db.projectDao().update(other.copy(position = project.position))
        db.projectDao().update(ProjectEntity(project.id, project.name, other.position))
    }

    fun deleteProject(project: Project, targetProject: Project) = launchWithMessage(R.string.ui_message_project_deleted) {
        require(project.id != DEFAULT_PROJECT_ID) { "The Default project cannot be deleted." }
        require(project.id != targetProject.id) { "Choose a different destination project." }
        // Mutation lock first, then the transaction: the reverse order deadlocks against the
        // engine's variable commit path.
        variableRepository.withMutationLock {
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
                // Secrets must be re-encrypted, not row-copied: their envelope binds the project id.
                reassignProject(project.id, targetProject.id)
                check(db.projectDao().deleteIfNotDefault(project.id) == 1) { "Project no longer exists." }
            }
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
        launchWithMessage(R.string.ui_message_profile_reviewed) {
            val current = db.profileDao().getById(profileId)?.toDomain()
                ?: throw IllegalStateException("Profile no longer exists.")
            check(current.requiresRiskAcknowledgement) { "Profile review is no longer required." }
            val tasks = db.taskDao().getAll().map { it.toDomain() }
            val peers = db.profileDao().getAll().map { entity ->
                entity.toDomainDecodeResult().also { result ->
                    result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                }.value
            }
            val review = ImportedProfileEnablePolicy.review(
                profile = current,
                tasks = tasks,
                otherProfiles = peers,
                strings = AutomationLintStrings.from(appContext.resources),
            )
            check(review.canAcknowledge) {
                "Resolve unsupported actions, missing references, and blocking automation lint findings before enabling this imported profile."
            }
            val enabledProfile = current.copy(
                enabled = true,
                requiresRiskAcknowledgement = false,
            )
            val lint = requireAutomationLint(enabledProfile)
            db.withTransaction {
                // Acknowledging risk and enabling an imported profile is a real edit to that
                // profile, and it was the one profile write that recorded no history - so the
                // step that arms an unreviewed automation was the one the user could not undo.
                recordEdit(
                    entityType = EditHistoryDao.TYPE_PROFILE,
                    entityId = current.id,
                    previousJson = StorageJson.encodeToString(current),
                    nextJson = StorageJson.encodeToString(enabledProfile),
                )
                db.profileDao().upsert(enabledProfile.toEntity())
            }
            emitLintWarnings(enabledProfile, lint)
        }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    val current = db.profileDao().getById(profile.id)?.toDomainDecodeResult()?.also { result ->
                        result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                    }?.value ?: error("Profile no longer exists.")
                    recordDeletion(
                        entityType = EditHistoryDao.TYPE_PROFILE,
                        entityId = current.id,
                        previousJson = StorageJson.encodeToString(current),
                    )
                    db.profileDao().delete(current.toEntity())
                }
                LocaleConditionGrantStore(appContext).apply {
                    revokeAllForBinding(LocaleConditionGrantStore.profileKey(profile.id))
                    profile.contexts.indices.forEach { index ->
                        revokeAllForBinding(LocaleConditionGrantStore.contextKey(profile.id, index))
                    }
                }
                locationDwellStateStore.clearProfile(profile.id)
            }.onSuccess {
                events.send(
                    UiMessage(
                        resId = R.string.ui_message_profile_deleted,
                        action = UiMessageAction.Undo(EditHistoryDao.TYPE_PROFILE, profile.id),
                    ),
                )
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }

    fun installProfileTemplate(template: ProfileTemplate, slotValues: Map<String, String>) =
        launchWithMessage(R.string.ui_message_template_installed) {
            val applied = template.instantiate(slotValues)
            val resolvedValues = template.defaults() + slotValues.mapValues { it.value.trim() }
            var taskId = 0L
            var profileId = 0L
            db.withTransaction {
                taskId = db.taskDao().insert(applied.task.toEntity())
                profileId = db.profileDao().insert(
                    applied.profile.copy(enabled = false, enterTaskId = taskId).toEntity(),
                )
            }
            blueprintCatalogStore.merge(listOf(template))
            blueprintInstallationStore.record(
                BlueprintInstallation(
                    blueprintId = template.id,
                    blueprintVersion = template.version,
                    profileId = profileId,
                    taskId = taskId,
                    inputValues = resolvedValues,
                ),
            )
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

    /**
     * Writes the workspace as Tasker XML.
     *
     * The exporter shipped unreachable: nothing in the app called it, so the changelog claimed a
     * feature users could not run and its redaction path - the only export path that can match a
     * secret's literal plaintext - was never exercised outside tests.
     */
    fun exportTaskerXml(uri: Uri) {
        viewModelScope.launch {
            if (_taskerImportBusy.value) return@launch
            _taskerImportBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = TaskerXmlExporter.export(
                        profiles = db.profileDao().getAll().map { it.toDomain() },
                        tasks = db.taskDao().getAll().map { it.toDomain() },
                        variables = variableRepository.decodedForExportRedaction(),
                    )
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Unable to open export destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(report.xml) }
                    report
                }
            }
                .onSuccess { report ->
                    events.send(
                        message(
                            R.string.ui_message_tasker_xml_exported,
                            report.exportedProfileCount,
                            report.exportedTaskCount,
                            report.skippedActions.size,
                        ),
                    )
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_tasker_xml_export)) }
            _taskerImportBusy.value = false
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
                .onFailure { error ->
                    // A decode failure means the input is not an OpenTasker bundle. Surfacing the
                    // serializer's own text put "Unexpected JSON token at offset 0: Expected start
                    // of the object '{'" in front of the user, along with their raw input.
                    if (error is SerializationException) {
                        AppLogger.warn("OpenTasker", "Rejected an OpenTasker bundle that failed to decode", error)
                        events.send(message(R.string.ui_error_bundle_not_recognized))
                    } else {
                        events.send(errorMessage(error, R.string.ui_error_bundle_preview))
                    }
                }
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
                AppLogger.warn("OpenTasker.UI", "Profile share validation failed", error)
                _profileShareReview.value = current.copy(
                    draft = draft,
                    draftError = appContext.getString(R.string.profile_share_invalid_details_body),
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

    private var runLogQueryDebounceJob: Job? = null

    fun updateRunLogFilters(filters: RunLogFilterState) {
        val previous = _runLogFilters.value
        if (previous == filters) return
        _runLogFilters.value = filters
        runLogQueryDebounceJob?.cancel()
        // Typing changes only the query, and each character otherwise cost a snapshot, a count and
        // a page query. Everything else (status, task, date) is a discrete choice and reloads at
        // once.
        if (filters.copy(query = previous.query) == previous) {
            runLogQueryDebounceJob = viewModelScope.launch {
                delay(RUN_LOG_QUERY_DEBOUNCE_MS)
                refreshRunLogPage()
            }
        } else {
            refreshRunLogPage()
        }
    }

    fun refreshRunLogPage() {
        runLogPageJob?.cancel()
        // Keep what is on screen while reloading. Replacing it with an empty state made every
        // refresh - including one per keystroke in the search field - blank the list and flash the
        // loading state.
        _runLogPage.value = _runLogPage.value.copy(loading = true, failed = false)
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
                _runLogPage.value = _runLogPage.value.copy(loading = false, failed = true)
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
                events.send(pluralMessage(R.plurals.ui_message_run_logs_exported, exported, exported))
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
                    events.send(
                        if (deleted > 0) {
                            pluralMessage(R.plurals.ui_message_retention_updated_pruned, deleted, deleted)
                        } else {
                            message(R.string.ui_message_retention_updated)
                        },
                    )
                    refreshRunLogPage()
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_retention_update)) }
        }
    }

    fun updateGlobalFallbackTask(taskId: Long?) {
        val normalized = taskId?.takeIf { it > 0L }
        fallbackTaskSettings.saveTaskId(normalized)
        _globalFallbackTaskId.value = normalized
    }

    /**
     * Clears the global fallback task when it points at a task that no longer exists.
     *
     * The setting lives in SharedPreferences and cannot join the Room transaction that deletes a
     * task, so process death between the commit and the settings write leaves a dangling id.
     * Healing it on load keeps that window harmless instead of leaving a fallback that silently
     * never runs.
     */
    private suspend fun reconcileGlobalFallbackTask() {
        val storedId = fallbackTaskSettings.loadTaskId() ?: return
        if (db.taskDao().getById(storedId) != null) return
        fallbackTaskSettings.saveTaskId(null)
        _globalFallbackTaskId.value = null
    }

    private suspend fun pruneRunLogs(policy: RunLogRetentionPolicy): Int =
        db.runLogDao().applyRetention(policy, System.currentTimeMillis())

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
            val settings = ConfigurationSnapshotSettings(appContext)
            BackupSetupState(
                busy = busy,
                latestBackupName = databaseBackupManager.listBackups().firstOrNull()?.name,
                pendingRestore = databaseBackupManager.hasPendingRestore(),
                pendingRestoreSummary = databaseBackupManager.pendingRestoreSummary(),
                snapshotPolicy = settings.load(),
                snapshotStatus = settings.loadStatus(),
            )
        }
        _backupSetupState.value = loaded
    }

    /** Persists the snapshot schedule and brings the periodic worker in line with it. */
    fun updateSnapshotPolicy(policy: ConfigurationSnapshotPolicy) {
        launchBackupOperation {
            val saved = withContext(Dispatchers.IO) {
                val settings = ConfigurationSnapshotSettings(appContext)
                settings.save(policy)
                val stored = settings.load()
                ConfigurationSnapshotWorker.sync(appContext, stored)
                stored
            }
            events.send(
                if (saved.enabled) {
                    message(R.string.ui_message_snapshots_enabled, saved.maxSnapshots, saved.maxAgeDays)
                } else {
                    message(R.string.ui_message_snapshots_disabled)
                },
            )
        }
    }

    /** Persists the SAF grant and Keystore-wrapped passphrase before enabling the schedule. */
    fun updateSnapshotDestination(uri: Uri, passphrase: CharArray, enableSchedule: Boolean) {
        launchBackupOperation {
            try {
                runCatching {
                    withContext(Dispatchers.IO) {
                        configureConfigurationSnapshotDestination(appContext, uri, passphrase, enableSchedule)
                    }
                }.onSuccess { policy ->
                    events.send(
                        if (policy.enabled) {
                            message(R.string.ui_message_snapshots_enabled, policy.maxSnapshots, policy.maxAgeDays)
                        } else {
                            message(R.string.ui_message_snapshot_destination_saved)
                        },
                    )
                }.onFailure { error ->
                    withContext(Dispatchers.IO) {
                        ConfigurationSnapshotSettings(appContext).recordFailure(
                            System.currentTimeMillis(),
                            appContext.getString(R.string.setup_snapshots_destination_save_failed),
                        )
                    }
                    events.send(errorMessage(error, R.string.ui_error_snapshot_destination))
                }
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun runTaskNow(task: Task) {
        viewModelScope.launch {
            if (_runActionBusy.value) return@launch
            _runActionBusy.value = true
            runCatching {
                executeAndLogTask(
                    appContext = appContext,
                    db = db,
                    task = task,
                    source = "Manual run",
                    // Admit against the engine's live controller. The default is a separate
                    // in-memory one that admits even while the profile is saturated.
                    admissionController = ExecutionAdmissionRegistry.current(appContext),
                    execution = ExecutionEnvelope.create(task, "Manual run"),
                )
            }.onSuccess { result ->
                val status = when {
                    result.held -> appContext.getString(R.string.ui_run_status_held)
                    result.skippedReason != null -> appContext.getString(R.string.ui_run_status_skipped)
                    result.report.success -> appContext.getString(R.string.ui_run_status_succeeded)
                    else -> appContext.getString(R.string.ui_run_status_failed)
                }
                events.send(message(R.string.ui_message_run_status, task.name, status, result.report.durationMs))
                // A manual run can be held, which adds a row the run-log page should show.
                refreshRunLogPage()
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_run_task)) }
            _runActionBusy.value = false
        }
    }

    fun replayHeldRun(entry: RunLogEntry) {
        viewModelScope.launch {
            if (_runActionBusy.value) return@launch
            _runActionBusy.value = true
            runCatching {
                replayHeldExecution(
                    appContext = appContext,
                    db = db,
                    heldEntry = entry,
                    admissionController = ExecutionAdmissionRegistry.current(appContext),
                )
            }.onSuccess { result ->
                val status = when {
                    result.held -> appContext.getString(R.string.ui_run_status_held)
                    result.report.success -> appContext.getString(R.string.ui_run_status_succeeded)
                    else -> appContext.getString(R.string.ui_run_status_failed)
                }
                events.send(message(R.string.ui_message_run_replayed, entry.taskName, status, result.report.durationMs))
                refreshRunLogPage()
            }.onFailure { events.send(errorMessage(it, R.string.ui_error_run_log_replay)) }
            _runActionBusy.value = false
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

    private suspend fun transitionEdit(entityType: String, entityId: Long, redo: Boolean): SemanticDiffDocument? = db.withTransaction {
        val history = db.editHistoryDao()
        val snapshot = if (redo) {
            history.getRedoCandidate(entityType, entityId)
        } else {
            history.getUndoCandidate(entityType, entityId)
        } ?: return@withTransaction null

        if (snapshot.nextJson.isBlank()) {
            if (redo) {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                        val currentTask = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        val references = AutomationReferenceIndex.referencesTo(
                            task = currentTask,
                            profiles = db.profileDao().getAll().map { it.toDomain() },
                            tasks = db.taskDao().getAll().map { it.toDomain() },
                            scenes = db.sceneDao().getAll().map { it.toDomain() },
                            globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                        )
                        if (references.isNotEmpty()) return@withTransaction null
                        db.taskDao().delete(current)
                        LocaleGrantStore(appContext).revokeAllForTask(entityId)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareTask(currentTask, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                        val currentProfile = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.profileDao().delete(current)
                        LocaleConditionGrantStore(appContext).apply {
                            revokeAllForBinding(LocaleConditionGrantStore.profileKey(entityId))
                            currentProfile.contexts.indices.forEach { index ->
                                revokeAllForBinding(LocaleConditionGrantStore.contextKey(entityId, index))
                            }
                        }
                        locationDwellStateStore.clearProfile(entityId)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareProfile(currentProfile, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                        val currentScene = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.sceneDao().delete(current)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareScene(currentScene, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            } else {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        if (db.taskDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.task(snapshot.previousJson, entityId)
                        db.taskDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareTask(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        if (db.profileDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.profile(snapshot.previousJson, entityId)
                        db.profileDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareProfile(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        if (db.sceneDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.scene(snapshot.previousJson, entityId)
                        db.sceneDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareScene(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            }
        }

        if (snapshot.previousJson.isBlank()) {
            if (redo) {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        if (db.taskDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.task(snapshot.nextJson, entityId)
                        db.taskDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareTask(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        if (db.profileDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.profile(snapshot.nextJson, entityId)
                        db.profileDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareProfile(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        if (db.sceneDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.scene(snapshot.nextJson, entityId)
                        db.sceneDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareScene(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            } else {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                        val currentTask = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        val references = AutomationReferenceIndex.referencesTo(
                            task = currentTask,
                            profiles = db.profileDao().getAll().map { it.toDomain() },
                            tasks = db.taskDao().getAll().map { it.toDomain() },
                            scenes = db.sceneDao().getAll().map { it.toDomain() },
                            globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                        )
                        if (references.isNotEmpty()) return@withTransaction null
                        db.taskDao().delete(current)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareTask(currentTask, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                        val currentProfile = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.profileDao().delete(current)
                        locationDwellStateStore.clearProfile(entityId)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareProfile(currentProfile, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                        val currentScene = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.sceneDao().delete(current)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareScene(currentScene, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            }
        }

        val targetJson = if (redo) snapshot.nextJson else snapshot.previousJson
        if (targetJson.isBlank()) return@withTransaction null

        when (entityType) {
            EditHistoryDao.TYPE_TASK -> {
                val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.actionsJson
                }
                val target = EditHistorySnapshotDecoder.task(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareTask(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.taskDao().update(target.toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            EditHistoryDao.TYPE_PROFILE -> {
                val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.contextsJson
                }
                val target = EditHistorySnapshotDecoder.profile(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareProfile(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.profileDao().upsert(target.toEntity())
                locationDwellStateStore.clearProfile(entityId)
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            EditHistoryDao.TYPE_SCENE -> {
                val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.elementsJson
                }
                val target = EditHistorySnapshotDecoder.scene(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareScene(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.sceneDao().update(target.toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            else -> return@withTransaction null
        }
    }

    private fun transitionEditAsync(entityType: String, entityId: Long, redo: Boolean) {
        _highlightedFlowNodeKeys.value = emptySet()
        viewModelScope.launch {
            runCatching { transitionEdit(entityType, entityId, redo) }
                .onSuccess { diff ->
                    val changed = diff != null
                    if (diff != null && !diff.isEmpty) {
                        _semanticDiffReview.value = SemanticDiffReviewState(diff)
                        _highlightedFlowNodeKeys.value = diff.flowNodeKeys
                    }
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

    fun clearSemanticDiffReview() {
        _semanticDiffReview.value = null
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
        successMessage: UiMessage,
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
                    // Mutation lock first, then the transaction: the reverse order deadlocks
                    // against the engine's variable commit path.
                    variableRepository.withMutationLock {
                        db.withTransaction {
                            val (profiles, tasks, scenes) = loadDecodedAutomation()
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
                            rename(previous.name, updated)
                        }
                    }
                }
                events.send(successMessage)
            }.onFailure { error ->
                events.send(errorMessage(error, R.string.ui_error_variable_save))
            }
        }
    }

    fun deleteVariable(name: String, successMessage: UiMessage, projectId: Long = DEFAULT_PROJECT_ID) {
        var deletedVariableBinding: String? = null
        viewModelScope.launch {
            runCatching {
                // Mutation lock first, then the transaction: the reverse order deadlocks against
                // the engine's variable commit path.
                variableRepository.withMutationLock {
                    db.withTransaction {
                        val variable = get(name, projectId)
                            ?: throw IllegalStateException("Variable '%$name' no longer exists.")
                        val (profiles, tasks, scenes) = loadDecodedAutomation()
                        val guard = AutomationReferenceRewriter.guardVariableDeletion(
                            target = variable,
                            profiles = profiles,
                            tasks = tasks,
                            scenes = scenes,
                        )
                        if (!guard.canCommit) {
                            val sites = guard.blocked.map { it.describe() }.distinct().joinToString("; ")
                            throw UiRejection(
                                R.string.ui_error_variable_referenced,
                                listOf("%${variable.name}", sites),
                            )
                        }
                        delete(variable.name, projectId)
                        deletedVariableBinding = LocaleConditionGrantStore.variableKey(projectId, variable.name)
                    }
                }
            }
                .onSuccess {
                    deletedVariableBinding?.let { LocaleConditionGrantStore(appContext).revokeAllForBinding(it) }
                    events.send(successMessage)
                }
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
            .firstOrNull {
                it.field == "name" ||
                    it.field == "cooldownSec" ||
                    it.field == "priority" ||
                    it.field == "gracePeriodSec" ||
                    it.field == "expiresAtMs" ||
                    it.field == "maxActiveExecutions" ||
                    it.field == "burstLimit"
            }
        if (violation != null) {
            throw IllegalArgumentException(violation.message)
        }
    }

    private suspend fun requireAutomationLint(profile: Profile): AutomationLintReport {
        val peers = db.profileDao().getAll().map { entity ->
            entity.toDomainDecodeResult().also { result ->
                result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
            }.value
        }.filterNot { it.id == profile.id }
        val tasks = db.taskDao().getAll().map { entity ->
            entity.toDomainDecodeResult().also { result ->
                result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
            }.value
        }
        val report = AutomationLint.analyze(
            peers + profile,
            tasks,
            strings = AutomationLintStrings.from(appContext.resources),
        )
        val blockers = report.blockingFor(profile.id)
        require(blockers.isEmpty()) {
            blockers.joinToString(" ") { finding ->
                "${finding.title}: ${finding.detail} ${finding.suggestedFix}"
            }
        }
        return report
    }

    private suspend fun emitLintWarnings(profile: Profile, report: AutomationLintReport) {
        val warningCount = report.forProfile(profile.id).count { it.severity == AutomationLintSeverity.WARNING }
        if (warningCount > 0) {
            events.send(pluralMessage(R.plurals.ui_profile_lint_warnings, warningCount, warningCount))
        }
    }

    /**
     * Every profile, task, and scene decoded, refusing to proceed if any stored record is corrupt.
     * Variable rename and delete both rewrite references across all three, so they must read the
     * same consistent view rather than silently skipping a record they could not decode.
     */
    private suspend fun loadDecodedAutomation(): Triple<List<Profile>, List<Task>, List<Scene>> {
        fun <T> decode(decoded: StorageDecodeResult<T>): T {
            decoded.issue?.let { throw CorruptRecordOverwriteException(it) }
            return decoded.value
        }
        return Triple(
            db.profileDao().getAll().map { decode(it.toDomainDecodeResult()) },
            db.taskDao().getAll().map { decode(it.toDomainDecodeResult()) },
            db.sceneDao().getAll().map { decode(it.toDomainDecodeResult()) },
        )
    }

    /**
     * [onSaved] runs only when [block] succeeded. Editors use it to close themselves: closing
     * unconditionally at the call site is what discarded a whole form whenever validation the
     * dialog cannot perform (automation lint, duplicate names, reference guards) rejected the save.
     */
    private fun launchWithMessage(
        @StringRes successMessageRes: Int,
        successAction: UiMessageAction? = null,
        onSaved: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    onSaved()
                    events.send(UiMessage(successMessageRes, action = successAction))
                }
                .onFailure { events.send(errorMessage(it, R.string.ui_error_generic)) }
        }
    }
}

private const val RUN_LOG_QUERY_DEBOUNCE_MS = 300L

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
