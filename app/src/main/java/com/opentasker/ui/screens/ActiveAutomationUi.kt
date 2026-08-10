package com.opentasker.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.selectedContainerColor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.opentasker.app.BuildConfig
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.diagnostics.DiagnosticExport
import com.opentasker.core.diagnostics.RunLogExportFormat
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
import com.opentasker.core.search.GlobalSearchResultKind
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.StorageDecodeIssue
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.Locale


private const val NO_DIALOG_ENTITY_ID = 0L
private const val NO_DIALOG_INDEX = -1
private const val DELETE_TARGET_PROFILE = "profile"
private const val DELETE_TARGET_TASK = "task"
private const val DELETE_TARGET_SCENE = "scene"
private const val DELETE_TARGET_ACTION = "action"
private const val DELETE_TARGET_CONTEXT = "context"


private enum class OpenTaskerScreen(@StringRes val labelRes: Int) {
    Profiles(R.string.nav_profiles),
    Tasks(R.string.nav_tasks),
    Vars(R.string.nav_variables),
    Flow(R.string.nav_flow),
    Scenes(R.string.nav_scenes),
    Inspector(R.string.nav_inspector),
    Setup(R.string.nav_setup),
    RunLog(R.string.nav_run_log),
    Diagnostics(R.string.nav_diagnostics),
}
private val primaryNavigationScreens = listOf(
    OpenTaskerScreen.Profiles,
    OpenTaskerScreen.Tasks,
    OpenTaskerScreen.Setup,
    OpenTaskerScreen.RunLog,
)

private val secondaryNavigationScreens = OpenTaskerScreen.entries.filterNot { it in primaryNavigationScreens }
private val adaptiveNavigationScreens = OpenTaskerScreen.entries

private fun OpenTaskerScreen.icon(): ImageVector = when (this) {
    OpenTaskerScreen.Profiles -> Icons.Outlined.Tune
    OpenTaskerScreen.Tasks -> Icons.AutoMirrored.Outlined.PlaylistPlay
    OpenTaskerScreen.Vars -> Icons.Outlined.Key
    OpenTaskerScreen.Flow -> Icons.Outlined.AccountTree
    OpenTaskerScreen.Scenes -> Icons.Outlined.Widgets
    OpenTaskerScreen.Inspector -> Icons.Outlined.Sensors
    OpenTaskerScreen.Setup -> Icons.Outlined.Settings
    OpenTaskerScreen.RunLog -> Icons.Outlined.History
    OpenTaskerScreen.Diagnostics -> Icons.Outlined.MonitorHeart
}

internal data class ActionEditState(
    val task: Task,
    val metadata: ActionMetadata,
    val index: Int? = null,
    val existing: ActionSpec? = null,
)

internal data class ContextEditState(
    val profile: Profile,
    val type: ContextType,
    val index: Int? = null,
    val existing: ContextSpec? = null,
)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveAutomationUi(
    db: AppDatabase,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val useNavigationRail = usesNavigationRail(LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.fontScale)
    val viewModel: ActiveAutomationViewModel = viewModel(factory = ActiveAutomationViewModelFactory(db, context))
    val profiles by viewModel.profiles.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val scenes by viewModel.scenes.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val runLogs by viewModel.runLogs.collectAsState()
    val runLogPage by viewModel.runLogPage.collectAsState()
    val runLogFilters by viewModel.runLogFilters.collectAsState()
    val runLogTaskOptions by viewModel.runLogTaskOptions.collectAsState()
    val activeExecutions by viewModel.activeExecutions.collectAsState()
    val globalVariables by viewModel.globalVariables.collectAsState()
    val runLogRetentionPolicy by viewModel.runLogRetentionPolicy.collectAsState()
    val runLogRetentionPreview by viewModel.runLogRetentionPreview.collectAsState()
    val backupSetupState by viewModel.backupSetupState.collectAsState()
    val restoreReview by viewModel.restoreReview.collectAsState()
    val diagnosticsState by viewModel.diagnosticsState.collectAsState()
    val storageDecodeIssues by viewModel.storageDecodeIssues.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var screenOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var selectedProjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showGlobalSearchDialog by rememberSaveable { mutableStateOf(false) }
    var focusedVariableName by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedVariableProjectId by rememberSaveable { mutableLongStateOf(com.opentasker.core.model.DEFAULT_PROJECT_ID) }
    var focusedSceneId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    LaunchedEffect(projects, selectedProjectId) {
        if (selectedProjectId != null && projects.none { it.id == selectedProjectId }) selectedProjectId = null
    }
    val projectTasks = remember(tasks, selectedProjectId) { tasks.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val projectProfiles = remember(profiles, selectedProjectId) { profiles.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val projectScenes = remember(scenes, selectedProjectId) { scenes.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val projectVariables = remember(globalVariables, selectedProjectId) { globalVariables.filter { selectedProjectId == null || it.projectId == selectedProjectId } }
    val screen = OpenTaskerScreen.entries.getOrElse(screenOrdinal) { OpenTaskerScreen.Profiles }
    var taskDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateTaskDialog by rememberSaveable { mutableStateOf(false) }
    var profileDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateProfileDialog by rememberSaveable { mutableStateOf(false) }
    var simulationProfile by remember { mutableStateOf<Profile?>(null) }
    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }
    var showBundleTextImportDialog by rememberSaveable { mutableStateOf(false) }
    var bundleTextImportDraft by rememberSaveable { mutableStateOf("") }
    var onboardingTemplateFlow by rememberSaveable { mutableStateOf(false) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    val onboardingCompleted by OnboardingPreference.hasCompleted(context).collectAsState(initial = true)
    LaunchedEffect(onboardingCompleted) {
        if (shouldLaunchOnboarding(onboardingCompleted, selectedTemplateId)) {
            showTemplateDialog = true
            onboardingTemplateFlow = true
        }
    }
    var actionPickerTaskId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var actionEditTaskId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var actionEditActionId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionEditIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var contextPickerProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var contextEditProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var contextEditTypeName by rememberSaveable { mutableStateOf<String?>(null) }
    var contextEditIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var contextLogicProfileId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var pendingDeleteKind by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteOwnerId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var pendingDeleteIndex by rememberSaveable { mutableIntStateOf(NO_DIALOG_INDEX) }
    var importedProfileReviewId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    val taskerImportReview by viewModel.taskerImportReview.collectAsState()
    val taskerImportBusy by viewModel.taskerImportBusy.collectAsState()
    val openTaskerBundleReview by viewModel.openTaskerBundleReview.collectAsState()
    val openTaskerBundleBusy by viewModel.openTaskerBundleBusy.collectAsState()
    val profileShareReview by viewModel.profileShareReview.collectAsState()
    val preflightReview by viewModel.preflightReview.collectAsState()
    val preflightBusy by viewModel.preflightBusy.collectAsState()
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
    val profileShareScreenshotLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.addProfileShareScreenshots(uris)
    }
    val databaseBackupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { viewModel.exportDatabaseBackup(it) }
    }
    val databaseBackupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDatabaseBackup(it) }
    }
    var exportAllRunLogs by rememberSaveable { mutableStateOf(false) }
    val runLogJsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportRunLogs(it, RunLogExportFormat.JSON, exportAllRunLogs) }
        exportAllRunLogs = false
    }
    val runLogCsvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { viewModel.exportRunLogs(it, RunLogExportFormat.CSV, exportAllRunLogs) }
        exportAllRunLogs = false
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
    val contextLogicProfile = contextLogicProfileId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }
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
    val importedProfileReview = importedProfileReviewId.takeIf { it != NO_DIALOG_ENTITY_ID }
        ?.let { profileId -> profiles.firstOrNull { it.id == profileId } }

    // Task deletes are gated on a dependency scan: a task referenced by a profile, another task's
    // `task.run`/notification button, or a scene gesture needs an explicit reassign-or-clear
    // decision before it can go away, so the plain confirmation dialog is not enough.
    val pendingTaskDelete = (pendingDelete as? DeleteTarget.TaskTarget)?.task
    var taskDeletionPreview by remember { mutableStateOf<TaskDeletionPreview?>(null) }
    LaunchedEffect(pendingTaskDelete?.id, tasks, profiles, scenes) {
        taskDeletionPreview = pendingTaskDelete?.let { viewModel.taskDeletionPreview(it) }
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
    fun openSimulation(profile: Profile) { simulationProfile = profile }
    fun clearSimulation() {
        simulationProfile = null
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
    fun clearContextLogic() {
        contextLogicProfileId = NO_DIALOG_ENTITY_ID
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
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_flow_target_missing)) }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(screen) {
        if (screen == OpenTaskerScreen.Diagnostics) {
            // repeatOnLifecycle: LaunchedEffect is composition-scoped, so without it the
            // 5-second file/crash-log polling kept running while the app sat backgrounded
            // with Diagnostics selected.
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.refreshDiagnostics()
                    delay(DIAGNOSTICS_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    var showMoreDestinations by rememberSaveable { mutableStateOf(false) }
    val headerDetail = when (screen) {
        OpenTaskerScreen.Profiles -> stringResource(R.string.header_profiles_detail, profiles.count { it.enabled }, profiles.size)
        OpenTaskerScreen.Tasks -> stringResource(R.string.header_tasks_detail, tasks.sumOf { it.actions.size }, tasks.size)
        OpenTaskerScreen.Vars -> stringResource(R.string.header_variables_detail, globalVariables.size)
        OpenTaskerScreen.Flow -> stringResource(R.string.header_flow_detail, profiles.size, tasks.size)
        OpenTaskerScreen.Scenes -> stringResource(R.string.header_scenes_detail, scenes.sumOf { it.elements.size }, scenes.size)
        OpenTaskerScreen.Inspector -> stringResource(R.string.header_inspector_detail)
        OpenTaskerScreen.Setup -> stringResource(R.string.header_setup_detail)
        OpenTaskerScreen.RunLog -> stringResource(R.string.header_run_log_detail, runLogPage.totalCount)
        OpenTaskerScreen.Diagnostics -> stringResource(R.string.header_diagnostics_detail)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                OpenTaskerHeader(
                    screen = screen,
                    detail = headerDetail,
                    onOpenSearch = { showGlobalSearchDialog = true },
                )
                if (screen in setOf(OpenTaskerScreen.Profiles, OpenTaskerScreen.Tasks, OpenTaskerScreen.Vars, OpenTaskerScreen.Scenes, OpenTaskerScreen.Flow)) {
                    ProjectScopeBar(
                        projects = projects,
                        selectedProjectId = selectedProjectId,
                        onSelectProject = { selectedProjectId = it },
                        onCreateProject = viewModel::createProject,
                        onRenameProject = viewModel::renameProject,
                        onReorderProject = viewModel::reorderProject,
                        onDeleteProject = viewModel::deleteProject,
                    )
                }
            }
        },
        floatingActionButton = {
            when (screen) {
                OpenTaskerScreen.Profiles -> {
                    val createLabel = stringResource(if (projectTasks.isEmpty()) R.string.task_new else R.string.profile_new)
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (projectTasks.isEmpty()) {
                                showCreateTaskDialog = true
                            } else {
                                showCreateProfileDialog = true
                            }
                        },
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = createLabel,
                            )
                        },
                        text = { Text(createLabel) },
                    )
                }

                OpenTaskerScreen.Tasks -> ExtendedFloatingActionButton(
                    onClick = { showCreateTaskDialog = true },
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_new)) },
                    text = { Text(stringResource(R.string.task_new)) },
                )

                OpenTaskerScreen.Vars,
                OpenTaskerScreen.Flow,
                OpenTaskerScreen.Scenes,
                OpenTaskerScreen.Inspector,
                OpenTaskerScreen.Setup,
                OpenTaskerScreen.RunLog -> Unit
                OpenTaskerScreen.Diagnostics -> Unit
            }
        },
        bottomBar = {
            if (!useNavigationRail) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f))
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
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
                                label = stringResource(destination.labelRes),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Box(Modifier.weight(1f)) {
                            OpenTaskerNavigationItem(
                                selected = screen in secondaryNavigationScreens || showMoreDestinations,
                                onClick = { showMoreDestinations = true },
                                icon = Icons.Outlined.MoreHoriz,
                                label = stringResource(R.string.nav_more),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(
                                expanded = showMoreDestinations,
                                onDismissRequest = { showMoreDestinations = false },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                secondaryNavigationScreens.forEach { destination ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(destination.labelRes)) },
                                        leadingIcon = { Icon(destination.icon(), contentDescription = stringResource(destination.labelRes)) },
                                        onClick = {
                                            screenOrdinal = destination.ordinal
                                            showMoreDestinations = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(Modifier.fillMaxSize()) {
            if (useNavigationRail) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 88.dp, max = 128.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        adaptiveNavigationScreens.forEach { destination ->
                            OpenTaskerNavigationItem(
                                selected = screen == destination,
                                onClick = {
                                    screenOrdinal = destination.ordinal
                                    showMoreDestinations = false
                                },
                                icon = destination.icon(),
                                label = stringResource(destination.labelRes),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
        when (screen) {
            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = projectProfiles,
                tasks = projectTasks,
                runLogs = runLogs,
                storageDecodeIssues = storageDecodeIssues,
                onCreateTaskFirst = {
                    screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                    showCreateTaskDialog = true
                },
                onCreateProfile = { showCreateProfileDialog = true },
                onBrowseTemplates = {
                    showTemplateDialog = true
                    if (!onboardingCompleted) onboardingTemplateFlow = true
                },
                onPreviewProfileShare = { viewModel.previewLocalProfileShare(BuildConfig.VERSION_NAME) },
                onPreflightProfile = viewModel::previewProfilePreflight,
                onExportOpenTaskerBundle = { openTaskerBundleExportLauncher.launch(openTaskerBundleExportName()) },
                onImportOpenTaskerBundle = { openTaskerBundleImportLauncher.launch(OPEN_TASKER_BUNDLE_MIME_TYPES) },
                onImportOpenTaskerBundleText = {
                    bundleTextImportDraft = readClipboardText(context)
                    showBundleTextImportDialog = true
                },
                openTaskerBundleBusy = openTaskerBundleBusy,
                onImportTaskerXml = { taskerXmlLauncher.launch(TASKER_XML_MIME_TYPES) },
                taskerImportBusy = taskerImportBusy,
                onEditProfile = { openProfileDialog(it) },
                onUndoProfileEdit = { viewModel.undoLastProfileEdit(it.id) },
                onRedoProfileEdit = { viewModel.redoLastProfileEdit(it.id) },
                onDeleteProfile = { openDeleteProfile(it) },
                onToggleProfile = { profile, enabled ->
                    if (enabled && profile.requiresRiskAcknowledgement) {
                        importedProfileReviewId = profile.id
                    } else {
                        viewModel.updateProfile(
                            profile.copy(enabled = enabled),
                            "Profile ${if (enabled) "enabled" else "disabled"}",
                        )
                    }
                },
                onAddContext = { openContextPicker(it) },
                onEditContextLogic = { contextLogicProfileId = it.id },
                onEditContext = { profile, index, context ->
                    openContextEdit(profile, context.type, index)
                },
                onDeleteContext = { profile, index ->
                    if (profile.contexts.getOrNull(index) != null) openDeleteContext(profile, index)
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = projectTasks,
                storageDecodeIssues = storageDecodeIssues,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { openTaskDialog(it) },
                onUndoTaskEdit = { viewModel.undoLastTaskEdit(it.id) },
                onRedoTaskEdit = { viewModel.redoLastTaskEdit(it.id) },
                onDeleteTask = { openDeleteTask(it) },
                onRunTask = { viewModel.runTaskNow(it) },
                onPreflightTask = viewModel::previewTaskPreflight,
                onPinTask = { viewModel.pinTaskShortcut(it) },
                onAddAction = { openActionPicker(it) },
                onEditAction = { task, index, action ->
                    val metadata = ActionMetadataRegistry.get(action.type)
                    if (metadata != null) {
                        openActionEdit(task, metadata, index)
                    } else {
                        // Unknown/unsupported action types (e.g. from an import on a build that
                        // lacks the action) have no editor; tell the user instead of dead-tapping.
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                "This action type isn't supported on this build - remove it or re-import.",
                            )
                        }
                    }
                },
                onDeleteAction = { task, index ->
                    if (task.actions.getOrNull(index) != null) openDeleteAction(task, index)
                },
                onMoveAction = { task, fromIndex, toIndex ->
                    viewModel.moveTaskAction(task.id, fromIndex, toIndex)
                },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Flow -> AutomationFlowScreen(
                profiles = projectProfiles,
                tasks = projectTasks,
                contentPadding = innerPadding,
                onNodeTargetSelected = openFlowTarget,
                onAddContext = { profileId ->
                    val profile = profiles.firstOrNull { it.id == profileId }
                    if (profile != null) {
                        screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                        openContextPicker(profile)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_flow_target_missing)) }
                    }
                },
                onAddAction = { taskId ->
                    val task = tasks.firstOrNull { it.id == taskId }
                    if (task != null) {
                        screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                        openActionPicker(task)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ui_flow_target_missing)) }
                    }
                },
            )

            OpenTaskerScreen.Vars -> VariablesScreen(
                variables = projectVariables,
                contentPadding = innerPadding,
                projectId = selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID,
                focusVariableName = focusedVariableName,
                focusVariableProjectId = focusedVariableProjectId,
                onUpdate = { previousName, name, value, isSecret, successMessage, projectId ->
                    viewModel.updateVariable(previousName, name, value, isSecret, successMessage, projectId)
                },
                onDelete = { name, successMessage, projectId ->
                    viewModel.deleteVariable(name, successMessage, projectId)
                },
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            )

            OpenTaskerScreen.Scenes -> SceneLibraryScreen(
                scenes = projectScenes,
                tasks = projectTasks,
                focusSceneId = focusedSceneId.takeIf { it != NO_DIALOG_ENTITY_ID },
                onCreateScene = { name, width, height ->
                    viewModel.createScene(name, width, height, selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID)
                },
                onUpdateScene = viewModel::updateScene,
                onUndoSceneEdit = { viewModel.undoLastSceneEdit(it.id) },
                onRedoSceneEdit = { viewModel.redoLastSceneEdit(it.id) },
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
                onCancelPendingRestore = viewModel::cancelPendingRestore,
                profiles = profiles,
                tasks = tasks,
            )

            OpenTaskerScreen.Inspector -> ContextInspectorScreen(db = db, contentPadding = innerPadding)

            OpenTaskerScreen.RunLog -> RunLogScreenContent(
                logs = runLogPage.entries,
                tasks = tasks,
                totalCount = runLogPage.totalCount,
                hasMore = runLogPage.hasMore,
                loading = runLogPage.loading,
                filters = runLogFilters,
                taskOptions = runLogTaskOptions.map { it.taskId to it.taskName },
                onFiltersChange = viewModel::updateRunLogFilters,
                onLoadMore = viewModel::loadNextRunLogPage,
                onRefresh = viewModel::refreshRunLogPage,
                retentionPolicy = runLogRetentionPolicy,
                onRetentionPolicyChange = viewModel::requestRunLogRetention,
                onShareDiagnostic = viewModel::shareDiagnosticReport,
                onExportJson = {
                    exportAllRunLogs = false
                    runLogJsonExportLauncher.launch(runLogExportName(RunLogExportFormat.JSON))
                },
                onExportCsv = {
                    exportAllRunLogs = false
                    runLogCsvExportLauncher.launch(runLogExportName(RunLogExportFormat.CSV))
                },
                contentPadding = innerPadding,
                activeExecutions = activeExecutions,
                onCancelExecution = viewModel::cancelExecution,
                onReplayHeldRun = viewModel::replayHeldRun,
                onToggleRunLogStar = { entry -> viewModel.setRunLogStarred(entry) },
            )

            OpenTaskerScreen.Diagnostics -> DiagnosticsScreen(
                state = diagnosticsState,
                contentPadding = innerPadding,
                onRefresh = viewModel::refreshDiagnostics,
                onShare = viewModel::shareDiagnosticReport,
            )
        }
            }
        }
    }

    runLogRetentionPreview?.let { preview ->
        RunLogRetentionPreviewDialog(
            preview = preview,
            onDismiss = viewModel::dismissRunLogRetentionPreview,
            onExportJson = {
                exportAllRunLogs = true
                runLogJsonExportLauncher.launch(runLogExportName(RunLogExportFormat.JSON))
            },
            onConfirm = viewModel::confirmRunLogRetention,
        )
    }

    val blockedTaskDelete = taskDeletionPreview
        ?.takeIf { preview -> preview.hasDependents && preview.task.id == pendingTaskDelete?.id }

    blockedTaskDelete?.let { preview ->
        TaskDeleteReferencesDialog(
            preview = preview,
            tasks = tasks,
            onDismiss = { clearPendingDelete() },
            onConfirm = { resolution ->
                viewModel.deleteTask(preview.task, resolution)
                clearPendingDelete()
            },
        )
    }

    pendingDelete?.takeIf { blockedTaskDelete == null }?.let { target ->
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
                        target.profile.copy(
                            contexts = target.profile.contexts.filterIndexed { i, _ -> i != target.index },
                            contextExpression = target.profile.contextExpression?.removeLeaf(target.index),
                        ),
                        "Context removed",
                    )
                }
                clearPendingDelete()
            },
        )
    }

    restoreReview?.let { review ->
        RestoreReviewDialog(
            state = review,
            busy = backupSetupState.busy,
            onDismiss = viewModel::dismissRestoreReview,
            onStage = viewModel::confirmStageRestore,
        )
    }

    importedProfileReview?.let { profile ->
        ImportedProfileRiskDialog(
            profile = profile,
            tasks = tasks,
            onDismiss = { importedProfileReviewId = NO_DIALOG_ENTITY_ID },
            onAcknowledgeAndEnable = {
                viewModel.acknowledgeAndEnableImportedProfile(profile.id)
                importedProfileReviewId = NO_DIALOG_ENTITY_ID
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
            onVariableConflictResolution = viewModel::resolveOpenTaskerVariableConflict,
            onConfirm = viewModel::confirmOpenTaskerBundleImport,
        )
    }

    profileShareReview?.let { state ->
        ProfileShareReviewDialog(
            state = state,
            busy = openTaskerBundleBusy,
            onDismiss = viewModel::clearProfileShareReview,
            onDraftChanged = viewModel::updateProfileShareDraft,
            onAttachScreenshots = { profileShareScreenshotLauncher.launch(arrayOf("image/*")) },
            onRemoveScreenshot = viewModel::removeProfileShareScreenshot,
            onContinueImportReview = viewModel::continueProfileShareImportReview,
        )
    }

    if (showBundleTextImportDialog) {
        OpenTaskerBundleTextImportDialog(
            text = bundleTextImportDraft,
            busy = openTaskerBundleBusy,
            onTextChanged = { bundleTextImportDraft = it },
            onDismiss = { if (!openTaskerBundleBusy) showBundleTextImportDialog = false },
            onConfirm = {
                showBundleTextImportDialog = false
                viewModel.previewOpenTaskerBundleText(bundleTextImportDraft)
            },
        )
    }

    preflightReview?.let { state ->
        PreflightReviewDialog(
            state = state,
            busy = preflightBusy,
            onDismiss = viewModel::clearPreflightReview,
            onRerun = viewModel::rerunPreflight,
        )
    }

    if (showGlobalSearchDialog) {
        GlobalSearchDialog(
            profiles = profiles,
            tasks = tasks,
            variables = globalVariables,
            scenes = scenes,
            onDismiss = { showGlobalSearchDialog = false },
            onSelect = { result ->
                showGlobalSearchDialog = false
                selectedProjectId = result.projectId
                when (result.kind) {
                    GlobalSearchResultKind.PROFILE -> profiles.firstOrNull { it.id == result.entityId }?.let {
                        screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                        openProfileDialog(it)
                    }

                    GlobalSearchResultKind.TASK -> tasks.firstOrNull { it.id == result.entityId }?.let {
                        screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                        openTaskDialog(it)
                    }

                    GlobalSearchResultKind.ACTION -> {
                        val task = tasks.firstOrNull { it.id == result.entityId }
                        val action = result.actionIndex?.let { task?.actions?.getOrNull(it) }
                        val metadata = action?.let { ActionMetadataRegistry.get(it.type) }
                        if (task != null && metadata != null) {
                            screenOrdinal = OpenTaskerScreen.Tasks.ordinal
                            openActionEdit(task, metadata, result.actionIndex)
                        }
                    }

                    GlobalSearchResultKind.VARIABLE -> {
                        focusedVariableName = result.variableName
                        focusedVariableProjectId = result.projectId
                        screenOrdinal = OpenTaskerScreen.Vars.ordinal
                    }

                    GlobalSearchResultKind.SCENE -> {
                        focusedSceneId = result.entityId
                        screenOrdinal = OpenTaskerScreen.Scenes.ordinal
                    }
                }
            },
        )
    }

    if (showCreateTaskDialog) {
        TaskEditorDialog(
            task = null,
            onDismiss = { showCreateTaskDialog = false },
            onSave = { name, priority, collisionMode ->
            viewModel.createTask(name, priority, collisionMode, selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID)
                showCreateTaskDialog = false
            },
        )
    }

    taskDialog?.let { task ->
        TaskEditorDialog(
            task = task,
            onDismiss = { clearTaskDialog() },
            onSave = { name, priority, collisionMode ->
                viewModel.updateTask(
                    task.copy(
                        name = name.trim(),
                        priority = priority.coerceIn(0, 10),
                        collisionMode = collisionMode,
                    ),
                )
                clearTaskDialog()
            },
        )
    }

    if (showCreateProfileDialog) {
        ProfileEditorDialog(
            profile = null,
            tasks = projectTasks,
            onDismiss = { showCreateProfileDialog = false },
            onSave = { name, enabled, enterTaskId, exitTaskId, cooldown, automationMode, group ->
                viewModel.createProfile(name, enabled, enterTaskId, exitTaskId, cooldown, automationMode, group, selectedProjectId ?: com.opentasker.core.model.DEFAULT_PROJECT_ID)
                showCreateProfileDialog = false
            },
        )
    }

    if (showTemplateDialog) {
        TemplatePickerDialog(
            onDismiss = {
                showTemplateDialog = false
                onboardingTemplateFlow = false
            },
            onSelect = { template ->
                showTemplateDialog = false
                selectedTemplateId = template.id
            },
            onSkip = if (onboardingTemplateFlow) {
                {
                    showTemplateDialog = false
                    onboardingTemplateFlow = false
                    if (shouldCompleteOnboarding(OnboardingExit.Skipped)) {
                        scope.launch { OnboardingPreference.markCompleted(context) }
                    }
                }
            } else {
                null
            },
        )
    }

    selectedTemplate?.let { template ->
        TemplateSlotDialog(
            template = template,
            onDismiss = {
                selectedTemplateId = null
                onboardingTemplateFlow = false
            },
            onInstall = { values ->
                viewModel.installProfileTemplate(template, values)
                selectedTemplateId = null
                screenOrdinal = OpenTaskerScreen.Profiles.ordinal
                if (onboardingTemplateFlow && shouldCompleteOnboarding(OnboardingExit.InstalledTemplate)) {
                    onboardingTemplateFlow = false
                    scope.launch { OnboardingPreference.markCompleted(context) }
                }
            },
        )
    }

    profileDialog?.let { profile ->
        ProfileEditorDialog(
            profile = profile,
            tasks = tasks,
            onDismiss = { clearProfileDialog() },
            onSave = { name, enabled, enterTaskId, exitTaskId, cooldown, automationMode, group ->
                viewModel.updateProfile(
                    profile.copy(
                        name = name.trim(),
                        enabled = enabled,
                        enterTaskId = enterTaskId,
                        exitTaskId = exitTaskId,
                        cooldownSec = cooldown.coerceAtLeast(0),
                        automationMode = automationMode,
                        group = group,
                    )
                )
                clearProfileDialog()
            },
            onSimulate = { selectedProfile ->
                clearProfileDialog()
                openSimulation(selectedProfile)
            },
        )
    }

    simulationProfile?.let { profile ->
        SyntheticTriggerSimulationDialog(
            profile = profile,
            onDismiss = { clearSimulation() },
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
            tasks = tasks,
            enclosingActions = state.task.actions,
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
            onSimulate = { context ->
                val updatedContexts = state.index?.let { index ->
                    state.profile.contexts.mapIndexed { i, existing -> if (i == index) context else existing }
                } ?: (state.profile.contexts + context)
                val updatedExpression = if (state.index == null) {
                    state.profile.contextExpression?.appendLeaf(updatedContexts.lastIndex)
                } else {
                    state.profile.contextExpression
                }
                clearContextEdit()
                openSimulation(
                    state.profile.copy(
                        contexts = updatedContexts,
                        contextExpression = updatedExpression,
                    ),
                )
            },
            onSave = { context ->
                val updatedContexts = state.index?.let { index ->
                    state.profile.contexts.mapIndexed { i, existing -> if (i == index) context else existing }
                } ?: (state.profile.contexts + context)
                val updatedExpression = if (state.index == null) {
                    state.profile.contextExpression?.appendLeaf(updatedContexts.lastIndex)
                } else {
                    state.profile.contextExpression
                }
                viewModel.updateProfile(
                    state.profile.copy(contexts = updatedContexts, contextExpression = updatedExpression),
                    if (state.index == null) "Context added" else "Context updated",
                )
                clearContextEdit()
            },
        )
    }

    contextLogicProfile?.let { profile ->
        ContextGroupingDialog(
            profile = profile,
            onDismiss = { clearContextLogic() },
            onSave = { expression ->
                viewModel.updateProfile(
                    profile.copy(contextExpression = expression),
                    context.getString(R.string.context_logic_updated),
                )
                clearContextLogic()
            },
        )
    }
}

private const val DIAGNOSTICS_REFRESH_INTERVAL_MS = 5_000L

@Composable
private fun OpenTaskerHeader(
    screen: OpenTaskerScreen,
    detail: String,
    onOpenSearch: () -> Unit,
) {
    val appName = stringResource(R.string.app_name)
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignSystem.Screen.horizontalPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_opentasker_mark),
                    contentDescription = appName,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        appName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(screen.labelRes),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.global_search_content_description),
                    )
                }
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = screen.icon(),
                        contentDescription = stringResource(screen.labelRes),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
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
    val selectedDescription = stringResource(R.string.a11y_selected)
    val notSelectedDescription = stringResource(R.string.a11y_not_selected)
    Column(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription = if (selected) selectedDescription else notSelectedDescription
            }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 28.dp)
                .then(
                    if (selected) Modifier.background(
                        color = selectedContainerColor(),
                        shape = RoundedCornerShape(DesignSystem.Radii.sm),
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(1.dp))
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
internal fun SummaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(color, RoundedCornerShape(percent = 50)),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun InlineNotice(title: String, body: String, color: Color) {
    val isError = color == MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                if (isError) Icons.Filled.Error else Icons.Filled.Info,
                contentDescription = stringResource(if (isError) R.string.ui_error_content_description else R.string.ui_info_content_description),
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
