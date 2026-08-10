package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opentasker.ui.components.GroupOps
import com.opentasker.ui.components.TabAction
import com.opentasker.ui.theme.ThemeStore
import com.opentasker.ui.components.ThemedSnackbarHost
import com.opentasker.ui.components.TabActionsFab
import com.opentasker.ui.components.ThemedDropdownMenu
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
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.WorkspaceHealth
import com.opentasker.core.contexts.CalendarSunEventPresets
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.EventContextPreset
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.engine.AppShutdown
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.widget.TaskShortcutHelper
import com.opentasker.core.flow.AutomationFlowTarget
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProjectFilter
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.DatabaseBackupManager
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.ListSortStore
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.SortMethod
import com.opentasker.core.storage.SortTab
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.toEntity
import com.opentasker.core.transfer.BundleImportPlan
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified

/**
 * Size of the version label in the top bar, as a fraction of the app-name label it sits beside.
 * ~66%: visually subordinate, so the build string annotates the name instead of blending into it,
 * while staying big enough to read without leaning in.
 */
private const val VERSION_LABEL_SCALE = 0.66f

/** Scales a [TextUnit], passing Unspecified straight through — `lineHeight` is often unspecified and
 *  multiplying that throws. */
private fun TextUnit.scaleBy(factor: Float): TextUnit = if (isSpecified) this * factor else this


private const val NO_DIALOG_ENTITY_ID = 0L
private const val NO_DIALOG_INDEX = -1
private const val DELETE_TARGET_PROFILE = "profile"
private const val DELETE_TARGET_TASK = "task"
private const val DELETE_TARGET_SCENE = "scene"
private const val DELETE_TARGET_ACTION = "action"
private const val DELETE_TARGET_CONTEXT = "context"


private enum class OpenTaskerScreen(val label: String) {
    Monitor("Monitor"),
    Projects("Projects"),
    Profiles("Profiles"),
    Tasks("Tasks"),
    Vars("Variables"),
    Flow("Flow"),
    Scenes("Scenes"),
    Widgets("Widgets"),
    Inspector("Inspector"),
    Setup("Setup"),
    RunLog("Run Log"),
    Help("Help & Tools"),
}

/** A pending selective ("Export profiles/tasks/…") export awaiting the SAF create-document URI. */
private data class PendingExport(
    val fileName: String,
    val name: String,
    val profileIds: Set<Long> = emptySet(),
    val taskIds: Set<Long> = emptySet(),
    val sceneIds: Set<Long> = emptySet(),
    val templateNames: Set<String> = emptySet(),
    val variableKeys: Set<String> = emptySet(),
    val includeVariables: Boolean = false,
)

private val primaryNavigationScreens = listOf(
    OpenTaskerScreen.Profiles,
    OpenTaskerScreen.Tasks,
    OpenTaskerScreen.Setup,
    OpenTaskerScreen.RunLog,
    OpenTaskerScreen.Setup,
)

private val secondaryNavigationScreens = OpenTaskerScreen.entries.filterNot { it in primaryNavigationScreens }

private fun OpenTaskerScreen.icon(): ImageVector = when (this) {
    OpenTaskerScreen.Profiles -> Icons.Filled.CheckCircle
    OpenTaskerScreen.Tasks -> Icons.Filled.Edit
    OpenTaskerScreen.Vars -> Icons.Filled.Menu
    OpenTaskerScreen.Flow -> Icons.Filled.Info
    OpenTaskerScreen.Scenes -> Icons.Filled.Edit
    OpenTaskerScreen.Widgets -> Icons.Filled.PushPin
    OpenTaskerScreen.Inspector -> Icons.Filled.Info
    OpenTaskerScreen.Setup -> Icons.Filled.Settings
    OpenTaskerScreen.RunLog -> Icons.Filled.Info
    OpenTaskerScreen.Monitor -> Icons.Filled.PlayArrow
    OpenTaskerScreen.Projects -> Icons.Filled.Folder
    OpenTaskerScreen.Help -> Icons.Filled.Info
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

/**
 * Drives the top-bar expand/collapse-all toggle for a list tab: returns (anyExpanded, onToggle) where
 * onToggle collapses everything if any card is open, otherwise expands every visible card.
 */
private fun <K> expandAllControl(map: SnapshotStateMap<K, Boolean>, keys: List<K>): Pair<Boolean, () -> Unit> {
    val anyExpanded = keys.any { map[it] == true }
    return anyExpanded to { keys.forEach { map[it] = !anyExpanded } }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val dataLoaded by viewModel.dataLoaded.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val projectFilter = viewModel.projectFilter
    val currentProjectId = (projectFilter as? ProjectFilter.Of)?.projectId
    val itemGroups by viewModel.itemGroups.collectAsState()
    val prefs by ThemeStore.state.collectAsState()
    val itemMeta by viewModel.itemMeta.collectAsState()
    // Build the GroupOps a list tab needs, pre-filtered to that tab + the active project filter. Groups
    // are scoped to the SAME project filter as their items, so they appear whenever their members do.
    // `scoped = false` (used by Widgets) ignores the active project filter: widget templates carry no
    // projectId, so their groups must show regardless of which project is selected.
    fun groupOpsFor(tab: String, scoped: Boolean = true) = GroupOps(
        groups = itemGroups.filter {
            it.tab == tab && (!scoped || when (val f = projectFilter) {
                is ProjectFilter.All -> true
                is ProjectFilter.Unfiled -> it.projectId == null
                is ProjectFilter.Of -> it.projectId == f.projectId
            })
        }.sortedBy { it.position },
        groupIdOf = { key -> itemMeta.firstOrNull { it.tab == tab && it.itemKey == key }?.groupId },
        projectId = if (scoped) currentProjectId else null,
        setItemGroup = { key, gid -> viewModel.setItemGroup(tab, key, gid) },
        createGroupForItem = { key, name ->
            // File the new group in the same project as the item so it stays visible alongside it.
            val pid = if (!scoped) null else (when (tab) {
                "tasks" -> tasks.firstOrNull { it.id.toString() == key }?.projectId
                "profiles" -> profiles.firstOrNull { it.id.toString() == key }?.projectId
                "scenes" -> scenes.firstOrNull { it.id.toString() == key }?.projectId
                else -> null
            } ?: currentProjectId)
            viewModel.moveItemToNewGroup(tab, pid, name, key)
        },
        createSubgroup = { parent, name -> viewModel.createGroup(parent.tab, parent.projectId, name, parent.id) },
        setGroupParent = { g, pid -> viewModel.setGroupParent(g, pid) },
        toggleGroup = { viewModel.toggleGroupExpanded(it) },
        renameGroup = { g, n -> viewModel.renameGroup(g, n) },
        deleteGroup = { viewModel.deleteGroup(it) },
        // Drag-to-reorder from the list: file the moved item into its drop group + rewrite the tab's order.
        reorder = { key, gid, ordered -> viewModel.reorderItem(tab, key, gid, ordered) },
    )
    val visibleProfiles = when (val f = projectFilter) {
        ProjectFilter.All -> profiles
        ProjectFilter.Unfiled -> profiles.filter { it.projectId == null }
        is ProjectFilter.Of -> profiles.filter { it.projectId == f.projectId }
    }
    val visibleTasks = when (val f = projectFilter) {
        ProjectFilter.All -> tasks
        ProjectFilter.Unfiled -> tasks.filter { it.projectId == null }
        is ProjectFilter.Of -> tasks.filter { it.projectId == f.projectId }
    }
    val visibleScenes = when (val f = projectFilter) {
        ProjectFilter.All -> scenes
        ProjectFilter.Unfiled -> scenes.filter { it.projectId == null }
        is ProjectFilter.Of -> scenes.filter { it.projectId == f.projectId }
    }
    // Workspace health — evaluated over ALL tasks (not the project-filtered subset) on every resume,
    // so a permission granted in Settings clears the marks the moment the app comes back. Blocked
    // tasks drive the red ❗ on task rows, project chips, the Tasks nav tab, and Setup's health card.
    val healthTick = rememberResumeTick()
    val blockedTasks = remember(tasks, healthTick) { WorkspaceHealth.blockedTasks(tasks, context) }
    val brokenTaskIds = remember(blockedTasks) { blockedTasks.map { it.taskId }.toSet() }
    val alertProjectIds = remember(blockedTasks) {
        val byId = tasks.associateBy { it.id }
        blockedTasks.map { byId[it.taskId]?.projectId }.toSet()
    }
    // A profile inherits the mark when a task it runs (enter or exit, resolved name-first exactly like
    // the engine) is blocked — so the Profiles tab shows the same startup breakage at its own level.
    val brokenProfileIds = remember(blockedTasks, profiles) {
        if (brokenTaskIds.isEmpty()) emptySet() else {
            val byName = tasks.associateBy { it.name }
            val byId = tasks.associateBy { it.id }
            profiles.filter { p ->
                val enter = byName[p.enterTaskName] ?: byId[p.enterTaskId]
                val exit = byName[p.exitTaskName] ?: p.exitTaskId?.let { byId[it] }
                (enter != null && enter.id in brokenTaskIds) || (exit != null && exit.id in brokenTaskIds)
            }.map { it.id }.toSet()
        }
    }
    val alertProfileProjectIds = remember(brokenProfileIds) {
        profiles.filter { it.id in brokenProfileIds }.map { it.projectId }.toSet()
    }
    val runLogs by viewModel.runLogs.collectAsState()
    val globalVariables by viewModel.globalVariables.collectAsState()
    // Vars tab is project-scoped like the others: super-globals (projectId 0) always show (they're visible
    // in every project); project-globals only for the selected project. "All" shows every scope.
    val visibleVariables = when (val f = projectFilter) {
        ProjectFilter.All -> globalVariables
        ProjectFilter.Unfiled -> globalVariables.filter { it.projectId == 0L }
        is ProjectFilter.Of -> globalVariables.filter { it.projectId == 0L || it.projectId == f.projectId }
    }
    // Variables tab: expand/collapse + multi-select are local Compose state (the fork's VariablesScreen
    // owns the rest). An empty GroupOps disables variable grouping — the grouping backend wiring
    // (itemGroupDao + groupOpsFor) was dropped when the ViewModel/UI were taken from upstream; restoring
    // it is a later manual step.
    val expandedVars = remember { mutableStateMapOf<String, Boolean>() }
    // Per-card fold state for the Profiles / Tasks / Scenes list tabs (de9f47a). An empty map = every card
    // collapsed (the default); the top-bar expand/collapse-all toggle and each card's chevron flip entries.
    // Plain remember (kept while on the tab, reset on tab switch) — mirrors expandedVars / expandedTemplates.
    val expandedProfiles = remember { mutableStateMapOf<Long, Boolean>() }
    val expandedTasks = remember { mutableStateMapOf<Long, Boolean>() }
    val expandedScenes = remember { mutableStateMapOf<Long, Boolean>() }
    // Flow tab: per-card fold state keyed by profileId (the graph key). Default collapsed, like the others.
    val expandedFlows = remember { mutableStateMapOf<Long, Boolean>() }
    var selectedVarKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    // Widget templates (the re-wired Widgets tab) + its expand/select state. Templates live in the
    // device-local TemplateStore, not the DB, so they're read directly from its StateFlow.
    val widgetTemplates by com.opentasker.widget.TemplateStore.state.collectAsState()
    // Live dead-super-global analysis for the Var tab's cleanup section: recomputed whenever the workspace
    // (vars/tasks/profiles/scenes/widget templates) changes, so it self-corrects to 0/0 after a cleanup.
    val deadGlobalsReport = remember(globalVariables, tasks, profiles, scenes, widgetTemplates, projects) {
        analyzeDeadGlobals(globalVariables, tasks, profiles, scenes, widgetTemplates.map { it.layout },
            projects.mapTo(HashSet()) { it.id })
    }
    val expandedTemplates = remember { mutableStateMapOf<String, Boolean>() }
    var selectedTemplateNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var widgetCreateSignal by remember { mutableIntStateOf(0) }
    // Scenes "New scene" routes through the tab "+" menu like Tasks/Widgets: a tick opens the create dialog.
    var sceneCreateSignal by remember { mutableIntStateOf(0) }
    // Projects "New project" routes through the Projects tab "+" menu the same way: a tick opens its dialog.
    var projectCreateSignal by remember { mutableIntStateOf(0) }
    // Help (the re-wired Help tab): per-section fold state hoisted so it survives leaving the tab.
    val expandedHelpSections = remember { mutableStateMapOf<String, Boolean>() }
    // UI customization is a full-screen overlay reached from the top-bar overflow. (Projects is now a tab.)
    var showUiCustomization by rememberSaveable { mutableStateOf(false) }
    // "Exit app fully": run-on-exit tasks in flight, then the report the user confirms before anything
    // is forced down. Not rememberSaveable — a shutdown must never be resumed across a config change.
    var exitBusy by remember { mutableStateOf(false) }
    var exitReport by remember { mutableStateOf<AppShutdown.Report?>(null) }
    val hostActivity = LocalContext.current.findHostActivity()
    val sortPrefs by ListSortStore.state.collectAsState()
    // A selective export ("Export profiles/tasks/…") waiting for the SAF create-document URI.
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    val runLogRetentionPolicy by viewModel.runLogRetentionPolicy.collectAsState()
    val storageDecodeIssues by viewModel.storageDecodeIssues.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Monitor is the leftmost tab (ordinal 0) but the app should still LAND on Profiles, so seed the
    // initial selection with Profiles' ordinal rather than 0.
    // Restore the last-open tab durably (by enum name via the ViewModel store); falls back to Profiles.
    var screenOrdinal by rememberSaveable {
        mutableIntStateOf(
            runCatching { OpenTaskerScreen.valueOf(viewModel.loadLastScreen()).ordinal }
                .getOrDefault(OpenTaskerScreen.Profiles.ordinal)
        )
    }
    val screen = OpenTaskerScreen.entries.getOrElse(screenOrdinal) { OpenTaskerScreen.Profiles }
    LaunchedEffect(screenOrdinal) {
        viewModel.saveLastScreen(OpenTaskerScreen.entries.getOrNull(screenOrdinal)?.name ?: OpenTaskerScreen.Profiles.name)
    }
    var taskDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateTaskDialog by rememberSaveable { mutableStateOf(false) }
    // Quick icon picker invoked by tapping a task card's icon (or its "add icon" affordance).
    var iconPickerTask by remember { mutableStateOf<Task?>(null) }
    var profileDialogId by rememberSaveable { mutableLongStateOf(NO_DIALOG_ENTITY_ID) }
    var showCreateProfileDialog by rememberSaveable { mutableStateOf(false) }
    var showTemplateDialog by rememberSaveable { mutableStateOf(false) }
    val onboardingCompleted by OnboardingPreference.hasCompleted(context).collectAsState(initial = true)
    LaunchedEffect(onboardingCompleted) {
        if (!onboardingCompleted) {
            // Fork: upstream's first-launch template-onboarding dialog is unwanted here (we have our own
            // Setup + workspace workflow) and was flashing then being torn down under the main window on
            // first start. Silently mark onboarding completed so it never auto-pops; the template picker
            // stays reachable through the normal UI.
            OnboardingPreference.markCompleted(context)
        }
    }
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
    val selectionExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val req = pendingExport
        if (uri != null && req != null) {
            viewModel.exportSelectionBundle(
                uri = uri,
                appVersion = BuildConfig.VERSION_NAME,
                profileIds = req.profileIds,
                taskIds = req.taskIds,
                sceneIds = req.sceneIds,
                includeVariables = req.includeVariables,
                name = req.name,
                templateNames = req.templateNames,
                variableKeys = req.variableKeys,
            )
        }
        pendingExport = null
    }
    fun startSelectionExport(req: PendingExport) {
        pendingExport = req
        selectionExportLauncher.launch(req.fileName)
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
        OpenTaskerScreen.Vars -> "${visibleVariables.size} variables"
        OpenTaskerScreen.Flow -> "${profiles.size} profiles - ${tasks.size} tasks"
        OpenTaskerScreen.Scenes -> "${scenes.sumOf { it.elements.size }} elements - ${scenes.size} scenes"
        OpenTaskerScreen.Widgets -> "${widgetTemplates.size} widget templates"
        OpenTaskerScreen.Inspector -> "Live context health"
        OpenTaskerScreen.Setup -> "Permission and reliability checks"
        OpenTaskerScreen.RunLog -> "${runLogs.size} recent entries"
        OpenTaskerScreen.Monitor -> "Engine and live state"
        OpenTaskerScreen.Projects -> "${projects.size} projects"
        OpenTaskerScreen.Help -> "Schema and action reference"
    }

    if (showUiCustomization) {
        UiCustomizationScreen(onBack = { showUiCustomization = false })
        return
    }

    if (exitBusy) {
        AlertDialog(
            modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
            onDismissRequest = { /* the exit tasks are already running — no way back from here */ },
            title = { Text("Shutting down") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("Running the run-on-exit tasks…")
                }
            },
            confirmButton = {},
        )
    }

    // The shutdown report. Raised BEFORE anything is torn down, because a dialog shown after the app is
    // gone can't be read — and its whole value is naming what should already have stopped but hadn't.
    exitReport?.let { report ->
        AlertDialog(
            modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
            onDismissRequest = { exitReport = null },
            title = { Text(if (report.clean) "Ready to exit" else "Still running") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (report.ran.isEmpty()) {
                            "No run-on-exit tasks are configured (set them under Monitor ⇨ Run on exit)."
                        } else {
                            "Ran on exit: ${report.ran.joinToString()}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (report.timedOut.isNotEmpty()) {
                        Text(
                            "Gave up waiting after 30s (still going): ${report.timedOut.joinToString()}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (report.missing.isNotEmpty()) {
                        Text(
                            "Configured but no longer exist — stale “Run on exit” entries: " +
                                "${report.missing.joinToString()}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                    if (report.leftovers.isEmpty()) {
                        Text(
                            "Nothing is left running. Only the engine service remains, and it is stopped next.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text(
                            "${report.leftovers.size} thing${if (report.leftovers.size == 1) "" else "s"} " +
                                "should already have stopped and did not — they are forced down on OK:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        report.leftovers.forEach { entry ->
                            Text(
                                "• ${entry.kind.title}: ${entry.label} — ${entry.detail}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "This report is also written to the run log. Nothing will restart the app by itself " +
                            "afterwards — not the per-minute alarm, a widget, the tile or a sister app; open " +
                            "白い熊 自由作業盤 to start it again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    exitReport = null
                    AppShutdown.finish(context, report)
                    hostActivity?.finishAndRemoveTask()
                }) { Text("Stop everything & exit") }
            },
            dismissButton = { TextButton(onClick = { exitReport = null }) { Text("Cancel") } },
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        snackbarHost = { ThemedSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // The app name, with the FULL installed version pinned to its right — the whole
                        // string (upstream base + base date + upstream sha + our build counter), never a
                        // shortened form, so a glance at the bar says exactly which build is running.
                        // Bottom-aligned and at VERSION_LABEL_SCALE of the name's size so it reads as an
                        // annotation on the name rather than a second word inside it. Sitting the smaller
                        // text on the name's bottom edge also drops it clear of the 相撲字時計 overlay,
                        // which draws across the top band of the screen.
                        val nameStyle = LocalTextStyle.current
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "白い熊 自由作業盤",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // fill = false: the name yields width first, so the version is never the
                                // part that gets ellipsized away.
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(
                                BuildConfig.VERSION_NAME,
                                style = nameStyle.copy(
                                    fontSize = nameStyle.fontSize.scaleBy(VERSION_LABEL_SCALE),
                                    lineHeight = nameStyle.lineHeight.scaleBy(VERSION_LABEL_SCALE),
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
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
                actions = {
                    // Expand / collapse every card on the current list tab (de9f47a). Default = all collapsed.
                    val expandAll: Pair<Boolean, () -> Unit>? = when (screen) {
                        OpenTaskerScreen.Profiles -> expandAllControl(expandedProfiles, visibleProfiles.map { it.id })
                        OpenTaskerScreen.Tasks -> expandAllControl(expandedTasks, visibleTasks.map { it.id })
                        OpenTaskerScreen.Flow -> expandAllControl(expandedFlows, profiles.map { it.id })
                        OpenTaskerScreen.Scenes -> expandAllControl(expandedScenes, visibleScenes.map { it.id })
                        OpenTaskerScreen.Widgets -> expandAllControl(expandedTemplates, widgetTemplates.map { it.name })
                        OpenTaskerScreen.Vars -> expandAllControl(expandedVars, visibleVariables.map { variableKey(it) })
                        else -> null
                    }
                    if (expandAll != null) {
                        IconButton(onClick = expandAll.second) {
                            Icon(
                                if (expandAll.first) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                                contentDescription = if (expandAll.first) "Collapse all" else "Expand all",
                            )
                        }
                    }
                    var showOverflow by remember { mutableStateOf(false) }
                    // Tap = overflow menu; long-press = jump straight to 白い熊 自由作業盤 UI.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { showOverflow = true },
                                    onLongPress = { vibrateLongPressCue(context); showUiCustomization = true },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More options (long-press: 白い熊 自由作業盤 UI)")
                    }
                    ThemedDropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("白い熊 自由作業盤 UI") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = { showOverflow = false; showUiCustomization = true },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        // Restart is the everyday one: the same teardown as an exit, minus the stop flag,
                        // followed by a fresh start — re-establishes the context layer after a bad state.
                        DropdownMenuItem(
                            text = { Text("Restart engine") },
                            leadingIcon = { Icon(Icons.Filled.RestartAlt, contentDescription = null) },
                            onClick = {
                                showOverflow = false
                                AppShutdown.restartEngine(context)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Engine restarted — run-on-start tasks will re-run.")
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Exit app fully") },
                            leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) },
                            onClick = {
                                showOverflow = false
                                exitBusy = true
                                scope.launch {
                                    // prepare() only RUNS the exit tasks and takes stock — nothing is torn
                                    // down and nothing is stopped until the report below is confirmed.
                                    val report = AppShutdown.prepare(context)
                                    exitBusy = false
                                    exitReport = report
                                }
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // The fork's uniform per-tab "+" menu: New <item> / Import JSON / Export <selection> (set
            // apart by a divider). Import routes through the unified bundle flow; export reuses the
            // selective-export bundle (exportSelectionBundle).
            val importJson = TabAction("Import JSON…", Icons.Filled.Add) {
                openTaskerBundleImportLauncher.launch(OPEN_TASKER_BUNDLE_MIME_TYPES)
            }
            val actions: List<TabAction> = when (screen) {
                OpenTaskerScreen.Profiles -> listOf(
                    TabAction(if (tasks.isEmpty()) "New profile (needs a task)" else "New profile", Icons.Filled.Add) {
                        if (tasks.isEmpty()) showCreateTaskDialog = true else showCreateProfileDialog = true
                    },
                    TabAction("From template…", Icons.Filled.Add) { showTemplateDialog = true },
                    importJson,
                    TabAction("Export profiles…", Icons.Filled.Edit, dividerBefore = true) {
                        startSelectionExport(
                            PendingExport(
                                fileName = exportFileName("profiles"),
                                name = "All profiles (${visibleProfiles.size})",
                                profileIds = visibleProfiles.map { it.id }.toSet(),
                            ),
                        )
                    },
                    TabAction("Export everything…", Icons.Filled.Edit) {
                        openTaskerBundleExportLauncher.launch(openTaskerBundleExportName())
                    },
                )

                OpenTaskerScreen.Tasks -> listOf(
                    TabAction("New task", Icons.Filled.Add) { showCreateTaskDialog = true },
                    importJson,
                    TabAction("Export tasks…", Icons.Filled.Edit, dividerBefore = true) {
                        startSelectionExport(
                            PendingExport(
                                fileName = exportFileName("tasks"),
                                name = "All tasks (${visibleTasks.size})",
                                taskIds = visibleTasks.map { it.id }.toSet(),
                            ),
                        )
                    },
                    TabAction("Export everything…", Icons.Filled.Edit) {
                        openTaskerBundleExportLauncher.launch(openTaskerBundleExportName())
                    },
                )

                OpenTaskerScreen.Widgets -> listOf(
                    TabAction("New widget template", Icons.Filled.Add) { widgetCreateSignal++ },
                    importJson,
                    TabAction("Export templates…", Icons.Filled.Edit, dividerBefore = true) {
                        startSelectionExport(
                            PendingExport(
                                fileName = exportFileName("widget templates"),
                                name = "All widget templates (${widgetTemplates.size})",
                                templateNames = widgetTemplates.map { it.name }.toSet(),
                            ),
                        )
                    },
                    TabAction("Export everything…", Icons.Filled.Edit) {
                        openTaskerBundleExportLauncher.launch(openTaskerBundleExportName())
                    },
                )

                OpenTaskerScreen.Vars -> listOf(
                    importJson,
                    TabAction("Export variables…", Icons.Filled.Edit, dividerBefore = true) {
                        startSelectionExport(
                            PendingExport(
                                fileName = exportFileName("variables"),
                                name = "All variables (${visibleVariables.size})",
                                variableKeys = visibleVariables.map { variableKey(it) }.toSet(),
                            ),
                        )
                    },
                    TabAction("Export everything…", Icons.Filled.Edit) {
                        openTaskerBundleExportLauncher.launch(openTaskerBundleExportName())
                    },
                )

                OpenTaskerScreen.Scenes -> listOf(
                    TabAction("New scene", Icons.Filled.Add) { sceneCreateSignal++ },
                    importJson,
                    TabAction("Export scenes…", Icons.Filled.Edit, dividerBefore = true) {
                        startSelectionExport(
                            PendingExport(
                                fileName = exportFileName("scenes"),
                                name = "All scenes (${visibleScenes.size})",
                                sceneIds = visibleScenes.map { it.id }.toSet(),
                            ),
                        )
                    },
                    TabAction("Export everything…", Icons.Filled.Edit) {
                        openTaskerBundleExportLauncher.launch(openTaskerBundleExportName())
                    },
                )

                OpenTaskerScreen.Projects -> listOf(
                    TabAction("New project", Icons.Filled.Add) { projectCreateSignal++ },
                    importJson,
                    TabAction("Export everything…", Icons.Filled.Edit, dividerBefore = true) {
                        openTaskerBundleExportLauncher.launch(openTaskerBundleExportName())
                    },
                )

                OpenTaskerScreen.Flow,
                OpenTaskerScreen.Inspector,
                OpenTaskerScreen.Setup,
                OpenTaskerScreen.RunLog,
                OpenTaskerScreen.Monitor,
                OpenTaskerScreen.Help -> emptyList()
            }
            TabActionsFab(actions)
        },
        bottomBar = {
            // Fork: a single horizontally-scrollable bar showing ALL destinations inline (no "More"
            // overflow), matching de9f47a. Long-press the Setup cog jumps straight to 白い熊 自由作業盤 UI.
            // Opaque background so edge-to-edge screens (e.g. Monitor) don't show through the bar.
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.primary)
                val navScroll = rememberScrollState()
                val primary = MaterialTheme.colorScheme.primary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(navScroll)
                        .navigationBarsPadding()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpenTaskerScreen.entries.forEach { destination ->
                        val selected = screen == destination
                        val tapModifier = if (destination == OpenTaskerScreen.Setup) {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { screenOrdinal = OpenTaskerScreen.Setup.ordinal },
                                    onLongPress = { vibrateLongPressCue(context); showUiCustomization = true },
                                )
                            }
                        } else {
                            Modifier.clickable { screenOrdinal = destination.ordinal }
                        }
                        Column(
                            modifier = Modifier
                                .widthIn(min = 64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .then(tapModifier)
                                .background(if (selected) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.13f) else androidx.compose.ui.graphics.Color.Transparent)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box {
                                Icon(destination.icon(), contentDescription = null, tint = primary)
                                // Workspace-health mark: any blocked task puts the red ❗ on the Tasks
                                // tab so startup breakage is visible without opening anything.
                                val navAlert = (destination == OpenTaskerScreen.Tasks && brokenTaskIds.isNotEmpty()) ||
                                    (destination == OpenTaskerScreen.Profiles && brokenProfileIds.isNotEmpty())
                                if (navAlert) {
                                    HealthAlertIcon(
                                        Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-5).dp),
                                        size = 13.dp,
                                    )
                                }
                            }
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = primary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when (screen) {
            OpenTaskerScreen.Projects -> ProjectsManagementScreen(
                contentPadding = innerPadding,
                projects = projects,
                memberCount = { pid ->
                    profiles.count { it.projectId == pid } +
                        tasks.count { it.projectId == pid } +
                        scenes.count { it.projectId == pid }
                },
                sortMethod = sortPrefs.projects,
                onToggleSort = {
                    ListSortStore.set(
                        SortTab.PROJECTS,
                        if (sortPrefs.projects == SortMethod.MANUAL) SortMethod.ALPHABETICAL else SortMethod.MANUAL,
                    )
                },
                createSignal = projectCreateSignal,
                onCreate = { name, color -> viewModel.createProject(name, color) },
                onUpdate = { viewModel.updateProject(it) },
                onDelete = { project, deleteItems -> viewModel.deleteProject(project, deleteItems) },
                onReorder = { orderedIds -> viewModel.reorderProjects(orderedIds) },
                onExportProject = { project ->
                    startSelectionExport(
                        PendingExport(
                            fileName = exportFileName(project.name),
                            name = "Project: ${project.name}",
                            profileIds = profiles.filter { it.projectId == project.id }.map { it.id }.toSet(),
                            taskIds = tasks.filter { it.projectId == project.id }.map { it.id }.toSet(),
                            sceneIds = scenes.filter { it.projectId == project.id }.map { it.id }.toSet(),
                        ),
                    )
                },
                currentProjectId = currentProjectId,
                onSelectProject = { viewModel.selectProject(ProjectFilter.Of(it)) },
            )

            OpenTaskerScreen.Profiles -> ProfilesScreen(
                profiles = visibleProfiles,
                brokenProfileIds = brokenProfileIds,
                alertProjectIds = alertProfileProjectIds,
                tasks = tasks,
                expandedProfiles = expandedProfiles,
                runLogs = runLogs,
                storageDecodeIssues = storageDecodeIssues,
                projects = projects,
                projectFilter = projectFilter,
                currentProjectId = currentProjectId,
                onSelectProject = viewModel::selectProject,
                onReorderProjects = viewModel::reorderProjects,
                groupOps = groupOpsFor("profiles"),
                onMoveProfilesToProject = viewModel::moveProfilesToProject,
                onDeleteProfiles = viewModel::deleteProfiles,
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
                loaded = dataLoaded,
            )

            OpenTaskerScreen.Tasks -> TasksScreen(
                tasks = visibleTasks,
                brokenTaskIds = brokenTaskIds,
                alertProjectIds = alertProjectIds,
                expandedTasks = expandedTasks,
                storageDecodeIssues = storageDecodeIssues,
                projects = projects,
                projectFilter = projectFilter,
                currentProjectId = currentProjectId,
                onSelectProject = viewModel::selectProject,
                onReorderProjects = viewModel::reorderProjects,
                groupOps = groupOpsFor("tasks"),
                onMoveTasksToProject = viewModel::moveTasksToProject,
                onDeleteTasks = viewModel::deleteTasks,
                onCreateTask = { showCreateTaskDialog = true },
                onEditTask = { openTaskDialog(it) },
                onRenameTask = viewModel::renameTask,
                onDuplicateTasks = viewModel::duplicateTasks,
                onPasteTasks = viewModel::pasteTasks,
                onDeleteTask = { openDeleteTask(it) },
                onRunTask = { viewModel.runTaskNow(it) },
                onSetTaskFreeze = { t, on -> viewModel.updateTask(t.copy(freezeBubble = on), if (on) "Freeze bubble on" else "Freeze bubble off") },
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
                onApplyActions = { task, newActions ->
                    viewModel.updateTask(task.copy(actions = newActions), "Actions updated")
                },
                onPickTaskIcon = { iconPickerTask = it },
                contentPadding = innerPadding,
                loaded = dataLoaded,
            )

            OpenTaskerScreen.Flow -> AutomationFlowScreen(
                profiles = profiles,
                tasks = tasks,
                contentPadding = innerPadding,
                expandedFlows = expandedFlows,
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
                variables = visibleVariables,
                contentPadding = innerPadding,
                projects = projects,
                projectFilter = projectFilter,
                onSelectProject = viewModel::selectProject,
                onReorderProjects = viewModel::reorderProjects,
                onUpdate = viewModel::updateVariable,
                onDelete = viewModel::deleteVariable,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                expandedVars = expandedVars,
                selectedKeys = selectedVarKeys,
                onLongPressVar = { selectedVarKeys = selectedVarKeys + variableKey(it) },
                onToggleSelectVar = {
                    val k = variableKey(it)
                    selectedVarKeys = if (k in selectedVarKeys) selectedVarKeys - k else selectedVarKeys + k
                },
                onSelectAllVars = { selectedVarKeys = visibleVariables.map { variableKey(it) }.toSet() },
                onClearVarSelection = { selectedVarKeys = emptySet() },
                onDeleteSelectedVars = {
                    visibleVariables.filter { variableKey(it) in selectedVarKeys }
                        .forEach { viewModel.deleteVariable(it.projectId, it.name) }
                    selectedVarKeys = emptySet()
                },
                deadGlobals = deadGlobalsReport,
                onCleanupDeadGlobals = { viewModel.deleteDeadGlobals(deadGlobalsReport.deletable) },
            )

            // Scenes: the scene LIST has the fork's folding + multi-select (move-to-project / bulk-delete),
            // wired through the same way as Tasks/Profiles. SceneLibraryScreen still owns the scene canvas /
            // element editor below the list — that part is unchanged.
            OpenTaskerScreen.Scenes -> SceneLibraryScreen(
                scenes = visibleScenes,
                expandedScenes = expandedScenes,
                tasks = tasks,
                projects = projects,
                projectFilter = projectFilter,
                currentProjectId = currentProjectId,
                onSelectProject = viewModel::selectProject,
                onReorderProjects = viewModel::reorderProjects,
                groupOps = groupOpsFor("scenes"),
                onMoveScenesToProject = viewModel::moveScenesToProject,
                onDeleteScenes = viewModel::deleteScenes,
                createSignal = sceneCreateSignal,
                onCreateScene = { name, widthDp, heightDp, bgColor, cornerRadiusDp, scrimAlpha, borderColor, borderWidth, defaultPosition, defaultModal, defaultDismissOnOutside ->
                    viewModel.createScene(
                        name, widthDp, heightDp, currentProjectId,
                        bgColor, cornerRadiusDp, scrimAlpha, borderColor, borderWidth,
                        defaultPosition, defaultModal, defaultDismissOnOutside,
                    )
                },
                onUpdateScene = viewModel::updateScene,
                onDeleteScene = { openDeleteScene(it) },
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Setup -> PermissionOnboardingScreen(
                contentPadding = innerPadding,
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                blockedTasks = blockedTasks,
                onOpenTasks = { screenOrdinal = OpenTaskerScreen.Tasks.ordinal },
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

            OpenTaskerScreen.Widgets -> WidgetTemplatesScreen(
                templates = widgetTemplates,
                onSave = { name, layout -> com.opentasker.widget.TemplateStore.put(name, layout) },
                onDelete = { com.opentasker.widget.TemplateStore.delete(it) },
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                createSignal = widgetCreateSignal,
                expandedTemplates = expandedTemplates,
                selectedNames = selectedTemplateNames,
                onLongPressTemplate = { selectedTemplateNames = selectedTemplateNames + it.name },
                onToggleSelectTemplate = {
                    selectedTemplateNames =
                        if (it.name in selectedTemplateNames) selectedTemplateNames - it.name
                        else selectedTemplateNames + it.name
                },
                onSelectAllTemplates = { selectedTemplateNames = widgetTemplates.map { it.name }.toSet() },
                onClearTemplateSelection = { selectedTemplateNames = emptySet() },
                onDeleteSelectedTemplates = {
                    selectedTemplateNames.forEach { com.opentasker.widget.TemplateStore.delete(it) }
                    selectedTemplateNames = emptySet()
                },
                groupOps = groupOpsFor("widgets", scoped = false),
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Monitor -> MonitorScreen(
                profiles = profiles,
                tasks = tasks,
                projects = projects,
                lastFired = runLogs.filter { it.sourceLabel != null }
                    .groupBy { it.sourceLabel!! }
                    .mapValues { (_, rows) -> rows.maxOf { it.timestamp } },
                runLogs = runLogs,
                contentPadding = innerPadding,
            )

            OpenTaskerScreen.Help -> HelpDocumentationScreen(
                contentPadding = innerPadding,
                expandedSections = expandedHelpSections,
                tasks = tasks,
                scenes = scenes,
                onBrowseTemplates = { showTemplateDialog = true },
                onCreateTask = { showCreateTaskDialog = true },
                onCreateScene = {
                    // Create-scene lives in SceneLibraryScreen; jump to the Scenes tab and tick its
                    // create signal so the new-scene dialog opens there.
                    screenOrdinal = OpenTaskerScreen.Scenes.ordinal
                    sceneCreateSignal++
                },
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
            onConfirm = { viewModel.confirmTaskerImport(state) },
        )
    }

    // The consolidated near-full-screen import review: diffs the incoming bundle against the current
    // workspace BY NAME, marks each conflict, and collects the global + per-item conflict strategies.
    openTaskerBundleReview?.let { state ->
        ImportReviewScreen(
            state = state,
            projects = projects,
            tasks = tasks,
            profiles = profiles,
            scenes = scenes,
            widgetTemplates = widgetTemplates,
            variables = globalVariables,
            localGroups = itemGroups,
            busy = openTaskerBundleBusy,
            onCancel = viewModel::clearOpenTaskerBundleReview,
            onImport = { itemStrategy, overrides, projectChoices ->
                viewModel.confirmOpenTaskerBundleImport(
                    state.bundle,
                    itemConflictStrategy = itemStrategy,
                    itemStrategyOverrides = overrides,
                    projectChoices = projectChoices,
                )
            },
        )
    }

    if (showCreateTaskDialog) {
        TaskEditorDialog(
            task = null,
            siblingNames = tasks.filter { (it.projectId ?: 0L) == (currentProjectId ?: 0L) }
                .map { it.name.trim().lowercase() }.toSet(),
            onDismiss = { showCreateTaskDialog = false },
            onSave = { name, priority, iconPath, freezeBubble ->
                viewModel.createTask(name, priority, currentProjectId, iconPath, freezeBubble)
                showCreateTaskDialog = false
            },
        )
    }

    taskDialog?.let { task ->
        TaskEditorDialog(
            task = task,
            siblingNames = tasks.filter { (it.projectId ?: 0L) == (task.projectId ?: 0L) && it.id != task.id }
                .map { it.name.trim().lowercase() }.toSet(),
            onDismiss = { clearTaskDialog() },
            onSave = { name, priority, iconPath, freezeBubble ->
                viewModel.updateTask(task.copy(name = name.trim(), priority = priority.coerceIn(0, 10), iconPath = iconPath, freezeBubble = freezeBubble))
                clearTaskDialog()
            },
        )
    }

    iconPickerTask?.let { t ->
        TaskIconPickerDialog(
            initialIconPath = t.iconPath,
            onDismiss = { iconPickerTask = null },
            onConfirm = { path ->
                viewModel.updateTask(t.copy(iconPath = path))
                iconPickerTask = null
            },
        )
    }

    if (showCreateProfileDialog) {
        ProfileEditorDialog(
            profile = null,
            tasks = tasks,
            siblingNames = profiles.filter { (it.projectId ?: 0L) == (currentProjectId ?: 0L) }
                .map { it.name.trim().lowercase() }.toSet(),
            onDismiss = { showCreateProfileDialog = false },
            onSave = { name, enabled, enterTaskId, cooldown, automationMode, group, policy ->
                viewModel.createProfile(name, enabled, enterTaskId, cooldown, automationMode, group, currentProjectId, policy)
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
            siblingNames = profiles.filter { (it.projectId ?: 0L) == (profile.projectId ?: 0L) && it.id != profile.id }
                .map { it.name.trim().lowercase() }.toSet(),
            onDismiss = { clearProfileDialog() },
            onSave = { name, enabled, enterTaskId, cooldown, automationMode, group, policy ->
                viewModel.updateProfile(
                    profile.copy(
                        name = name.trim(),
                        enabled = enabled,
                        enterTaskId = enterTaskId,
                        cooldownSec = cooldown.coerceAtLeast(0),
                        automationMode = automationMode,
                        group = group,
                    ).withPolicy(policy)
                )
                clearProfileDialog()
            },
        )
    }

    actionPickerTask?.let { task ->
        // Fork's full-screen, category-foldable action picker when enabled (the default) — upstream's
        // small dialog otherwise. The upstream resync dropped this branch, falling back to the limited
        // dialog (白い熊: "adding actions uses the upstream small dialog").
        val onSelectAction: (ActionMetadata) -> Unit = { metadata ->
            clearActionPicker()
            openActionEdit(task, metadata)
        }
        if (prefs.advancedActionPicker) {
            AdvancedActionPickerScreen(onDismiss = { clearActionPicker() }, onSelect = onSelectAction)
        } else {
            ActionPickerDialog(onDismiss = { clearActionPicker() }, onSelect = onSelectAction)
        }
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
    val selectedDescription = stringResource(R.string.a11y_selected)
    val notSelectedDescription = stringResource(R.string.a11y_not_selected)
    Column(
        modifier = modifier
            .heightIn(min = 68.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                stateDescription = if (selected) selectedDescription else notSelectedDescription
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = if (selected) selectedContainerColor() else Color.Transparent,
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
internal fun SummaryMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
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
internal fun StatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        // Neutral grey fill (was the accent at low alpha → olive over black); the accent stays as the
        // border + text so the pill still reads as accented without the olive tint.
        color = Color.White.copy(alpha = 0.09f),
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
internal fun InlineNotice(title: String, body: String, color: Color) {
    val isError = color == MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // Error notices keep their red wash; others use a neutral grey fill (no olive).
        color = if (isError) color.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.07f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
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

/**
 * The hosting Activity behind a Compose LocalContext — needed by "Exit app fully" to call
 * `finishAndRemoveTask()`. The context is usually a ContextWrapper chain (theme wrappers), so unwrap
 * rather than casting; null when there is genuinely no Activity (a preview).
 */
private fun android.content.Context.findHostActivity(): android.app.Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}

/** Short confirm buzz for the long-press jump to the 白い熊 自由作業盤 UI page (白い熊 2026-07-25). */
private fun vibrateLongPressCue(context: android.content.Context) {
    val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }
    runCatching {
        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
