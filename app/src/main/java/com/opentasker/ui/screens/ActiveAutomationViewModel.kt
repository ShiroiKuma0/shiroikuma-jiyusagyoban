package com.opentasker.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.diagnostics.DiagnosticExport
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.engine.ExecutionAdmissionRegistry
import com.opentasker.core.engine.HeldExecutionPayloadCodec
import com.opentasker.core.engine.SingleActionRun
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.Profile
import com.opentasker.core.validation.InputValidation
import com.opentasker.core.model.Project
import com.opentasker.core.model.ProjectFilter
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.plugins.locale.LocaleConditionGrantStore
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.ProfileEntity
import com.opentasker.core.storage.SceneEntity
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.ui.components.UiMessage
import com.opentasker.core.storage.EditHistoryEntity
import com.opentasker.core.storage.ItemGroupEntity
import com.opentasker.core.storage.ItemMetaEntity
import com.opentasker.core.storage.ListSortStore
import com.opentasker.core.storage.ProjectSelectionStore
import com.opentasker.core.storage.SortMethod
import com.opentasker.core.storage.SortTab
import com.opentasker.core.storage.RunLogRetentionPolicy
import com.opentasker.core.storage.RunLogRetentionSettings
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.minimumTimestamp
import com.opentasker.core.storage.normalized
import com.opentasker.core.storage.toEntity
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.transfer.BundleImportPlan
import com.opentasker.core.transfer.ItemConflictStrategy
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import com.opentasker.core.transfer.OpenTaskerBundleRepository
import com.opentasker.core.transfer.ProjectConflictStrategy
import com.opentasker.core.transfer.ProjectImportChoice
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.MacroDroidImportPlanner
import com.opentasker.core.transfer.MacroDroidImportReport
import com.opentasker.core.transfer.MacroDroidImporter
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.widget.TaskShortcutHelper
import com.opentasker.widget.TaskWidgetProvider
import androidx.room.withTransaction
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

internal const val TASKER_MACRODROID_IMPORT_MAX_BYTES = 16 * 1024 * 1024
internal const val TASKER_XML_IMPORT_MAX_BYTES = 4 * 1024 * 1024
internal const val OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES = 8 * 1024 * 1024
internal val TASKER_XML_MIME_TYPES = arrayOf("application/xml", "text/xml", "text/*", "*/*")
internal val TASKER_MACRODROID_MIME_TYPES = arrayOf(
    "application/json",
    "text/json",
    "application/xml",
    "text/xml",
    "application/octet-stream",
    "text/*",
    "*/*",
)
internal val OPEN_TASKER_BUNDLE_MIME_TYPES = arrayOf("application/json", "text/json", "text/*", "*/*")
/** A filesystem-safe timestamp (yyyy-MM-dd_HH-mm-ss) shared by every export's default filename. */
internal fun exportStamp(): String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

/** The full-workspace ("Export everything") default filename: the app label + the timestamp. */
internal fun openTaskerBundleExportName(): String = "白い熊 自由作業盤.${exportStamp()}.json"

/** Per-export default filename: the item/category name kept readable (illegal chars stripped) + the stamp. */
internal fun exportFileName(label: String): String {
    val clean = label.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifEmpty { "export" }
    return "$clean.${exportStamp()}.json"
}

internal data class TaskerImportReviewState(
    val bundle: OpenTaskerBundle,
    val preview: TaskerImportPreview,
    // Upstream carries a @StringRes here; the fork's dialogs are written with plain strings.
    val title: String,
    val mappedActionRows: List<String>,
    val unsupportedActionRows: List<String>,
)

/** The review state for a Tasker XML document, with its rows rendered for the shared dialog. */
internal fun taskerImportReviewState(report: TaskerXmlImportReport) = TaskerImportReviewState(
    bundle = TaskerImportPlanner.confirmedBundle(report),
    preview = TaskerImportPlanner.preview(report),
    title = "Review Tasker import",
    mappedActionRows = report.mappedActions.map {
        "${it.taskName}: ${it.taskerCode} -> ${it.openTaskerActionId}"
    },
    unsupportedActionRows = report.unsupportedActions.map {
        "${it.taskName} step ${it.actionIndex + 1}: code ${it.taskerCode}"
    },
)

/** The same, for a MacroDroid `.mdr` backup or `.macro` share (upstream 0.2.88). */
internal fun macroDroidImportReviewState(report: MacroDroidImportReport) = TaskerImportReviewState(
    bundle = MacroDroidImportPlanner.confirmedBundle(report),
    preview = MacroDroidImportPlanner.preview(report),
    title = "Review MacroDroid import",
    mappedActionRows = report.mappedActions.map {
        "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} -> ${it.openTaskerActionIds.joinToString()}"
    },
    unsupportedActionRows = report.unsupportedActions.map {
        "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} (${it.reason})"
    },
)

internal data class OpenTaskerBundleReviewState(
    val bundle: OpenTaskerBundle,
    val plan: BundleImportPlan,
)

class ActiveAutomationViewModel(
    private val db: AppDatabase,
    private val appContext: Context,
) : ViewModel() {
    private val locationDwellStateStore = LocationDwellStateStore(appContext)
    private val bundleRepository = OpenTaskerBundleRepository(db)
    private val runLogRetentionSettings = RunLogRetentionSettings(appContext)
    private val writeSettingsGuard = WriteSettingsGuard(db, appContext)

    private val profileDecodeResults = db.profileDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val taskDecodeResults = db.taskDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomainDecodeResult() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Each list tab's order honours the persisted per-tab sort method (ListSortStore): ALPHABETICAL sorts
    // by name; MANUAL falls back to the saved per-item `position` (the trio 起動[71] → 設定[01] → 無効[37]).
    val profiles: StateFlow<ImmutableList<Profile>> =
        combine(profileDecodeResults, ListSortStore.state) { results, sort ->
            val items = results.map { it.value }
            (if (sort.profiles == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() }
            else items.sortedBy { it.position }).toImmutableList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val tasks: StateFlow<ImmutableList<Task>> =
        combine(taskDecodeResults, ListSortStore.state) { results, sort ->
            val items = results.map { it.value }
            (if (sort.tasks == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() }
            else items.sortedBy { it.position }).toImmutableList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    /** False until the profile + task DAO flows have each emitted at least once. Gates the list
     *  empty-states so upstream's "Build your first automation" CTA doesn't FLASH during the initial DB
     *  load on a populated workspace. Built from the RAW dao flows (which emit only on a real query) so
     *  it isn't fooled by the stateIn initial value. */
    val dataLoaded: StateFlow<Boolean> = combine(
        db.profileDao().getAllAsFlow(),
        db.taskDao().getAllAsFlow(),
    ) { _, _ -> true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val storageDecodeIssues: StateFlow<ImmutableList<StorageDecodeIssue>> = combine(
        profileDecodeResults,
        taskDecodeResults,
    ) { profileResults, taskResults ->
        (profileResults.mapNotNull { it.issue } + taskResults.mapNotNull { it.issue })
            .sortedWith(compareBy<StorageDecodeIssue> { it.recordType.label }.thenBy { it.recordName.lowercase() })
            .toImmutableList()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    val scenes: StateFlow<ImmutableList<Scene>> =
        combine(db.sceneDao().getAllAsFlow(), ListSortStore.state) { entities, sort ->
            val items = entities.map { it.toDomain() }
            (if (sort.scenes == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() }
            else items.sortedBy { it.position }).toImmutableList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    /** See [contentLoadedSignal]: screens gate first-run empty states on this. */
    val contentLoaded: StateFlow<Boolean> = contentLoadedSignal(db, viewModelScope)

    val runLogs: StateFlow<ImmutableList<RunLogEntry>> = db.runLogDao()
        .getRecentFlow()
        .map { entities -> entities.map { it.toDomain() }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    // The fork persists ONLY global variables (super-global → projectId 0, project-global → projectId > 0);
    // task-local `%lowercase` vars are never stored. So every row is a "global" — getAllAsFlow() is the set.
    val globalVariables: StateFlow<ImmutableList<Variable>> = db.variableDao()
        .getAllAsFlow()
        .map { entities -> entities.map { it.toDomain() }.toImmutableList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    // ---- Projects (organizational; the engine ignores projectId) + foldable groups ----
    // The selected project filter persists across restarts (and across tabs); items/groups carry a
    // nullable projectId (null = Unfiled). Lists filter their rows by this; groups are scoped the same way.
    private val projectSelectionStore = ProjectSelectionStore(appContext)
    var projectFilter by mutableStateOf<ProjectFilter>(projectSelectionStore.load())
        private set

    // The last-open tab persists across restarts too — stored by enum NAME (robust to tab reordering), so
    // re-entering the app after it's been killed returns to where 白い熊 was, not always Profiles.
    private val uiPrefs = appContext.getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
    fun loadLastScreen(): String = uiPrefs.getString("last_screen", "").orEmpty()
    fun saveLastScreen(name: String) { uiPrefs.edit().putString("last_screen", name).apply() }

    val projects: StateFlow<ImmutableList<Project>> =
        combine(db.projectDao().getAllAsFlow(), ListSortStore.state) { entities, sort ->
            val items = entities.map { it.toDomain() }
            (if (sort.projects == SortMethod.ALPHABETICAL) items.sortedBy { it.name.lowercase() }
            else items.sortedBy { it.sortOrder }).toImmutableList()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), persistentListOf())

    // Foldable groups + per-item group membership, shared across every list tab (keyed by tab + itemKey).
    val itemGroups: StateFlow<List<ItemGroupEntity>> = db.itemGroupDao().getAllAsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val itemMeta: StateFlow<List<ItemMetaEntity>> = db.itemMetaDao().getAllAsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectProject(filter: ProjectFilter) {
        projectSelectionStore.save(filter)
        projectFilter = filter
    }

    // ---- Project CRUD (used by ProjectsManagementScreen) ----
    fun createProject(name: String, color: Int?) = launchWithMessage("Project created") {
        val nextOrder = (db.projectDao().getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        db.projectDao().insert(Project(name = name.trim(), color = color, sortOrder = nextOrder).toEntity())
    }

    fun updateProject(project: Project) = launchWithMessage("Project updated") {
        db.projectDao().update(project.toEntity())
    }

    fun deleteProject(project: Project, deleteItems: Boolean) = launchWithMessage(
        if (deleteItems) "Project and its items deleted" else "Project deleted; items moved to Unfiled"
    ) { offer ->
        val pid = project.id
        // Everything the delete is about to touch, captured whole. A project delete is the largest
        // of these and the only one that can take hundreds of rows with it, which is exactly why it
        // is the one worth being able to take back.
        val undoProfiles = db.profileDao().getAll().filter { it.projectId == pid }
        val undoTasks = db.taskDao().getAll().filter { it.projectId == pid }
        val undoScenes = db.sceneDao().getAll().filter { it.projectId == pid }
        val undoGroups = db.itemGroupDao().getAll().filter { it.projectId == pid }
        val undoMeta = db.itemMetaDao().getAll()
        val undoVariables = db.variableDao().getAll().filter { it.projectId == pid }
        val undoProject = project.toEntity()
        db.withTransaction {
            val profileRows = db.profileDao().getAll().filter { it.projectId == pid }
            val taskRows = db.taskDao().getAll().filter { it.projectId == pid }
            val sceneRows = db.sceneDao().getAll().filter { it.projectId == pid }
            if (deleteItems) {
                profileRows.forEach { db.profileDao().delete(it); db.itemMetaDao().delete("profiles", it.id.toString()) }
                taskRows.forEach { db.taskDao().delete(it); db.itemMetaDao().delete("tasks", it.id.toString()) }
                sceneRows.forEach { db.sceneDao().delete(it); db.itemMetaDao().delete("scenes", it.id.toString()) }
            } else {
                profileRows.forEach { db.profileDao().update(it.copy(projectId = null)) }
                taskRows.forEach { db.taskDao().update(it.copy(projectId = null)) }
                sceneRows.forEach { db.sceneDao().update(it.copy(projectId = null)) }
            }
            // Project-globals can't survive their project: they can't move to Unfiled (variables have no
            // null scope) and must never become super-globals (the "no MixedCase in super" invariant). So
            // delete them either way — otherwise they'd dangle (dead projectId, frozen-stale, unreachable).
            db.variableDao().getAll().filter { it.projectId == pid }.forEach {
                db.variableDao().delete(it.projectId, it.name)
                com.opentasker.core.engine.variables.PersistentGlobalScope.unset(it.projectId, it.name)
            }
            // The project's foldable groups are project-scoped — delete them with the project so they don't
            // orphan. (Reassigned items keep their notes; a now-dangling groupId just reads as ungrouped.)
            db.itemGroupDao().deleteForProject(pid)
            db.projectDao().delete(project.toEntity())
        }
        if ((projectFilter as? ProjectFilter.Of)?.projectId == pid) {
            selectProject(ProjectFilter.All)
        }
        undoTasks.forEach { undoIcons[it.id] = it.iconPath }
        offer {
            db.withTransaction {
                db.projectDao().insert(undoProject)
                undoGroups.forEach { db.itemGroupDao().upsert(it) }
                // Insert restores a deleted row and rewrites one that was only moved to Unfiled —
                // both cases are "put it back as it was", and REPLACE makes them the same call.
                undoProfiles.forEach { db.profileDao().insert(it) }
                undoTasks.forEach { db.taskDao().insert(it) }
                undoScenes.forEach { db.sceneDao().insert(it) }
                db.variableDao().insertAll(undoVariables)
                undoMeta.forEach { db.itemMetaDao().upsert(it) }
            }
            undoTasks.forEach { undoIcons.remove(it.id) }
            TaskWidgetProvider.requestRefresh(appContext)
            "Restored “${project.name}” with ${undoTasks.size} task${plural(undoTasks.size)}, " +
                "${undoProfiles.size} profile${plural(undoProfiles.size)} and " +
                "${undoScenes.size} scene${plural(undoScenes.size)}"
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

    /**
     * Persist a full drag order: reassign contiguous sortOrder to match [orderedIds] and switch the Projects
     * tab to MANUAL so the dragged order sticks (a drag implies manual, like tasks). Backs both the Projects
     * tab list and the top project-tabs drag-reorder.
     */
    fun reorderProjects(orderedIds: List<Long>) = launchWithMessage("Projects reordered") {
        val byId = db.projectDao().getAll().associateBy { it.id }
        db.withTransaction {
            orderedIds.forEachIndexed { position, id ->
                val row = byId[id] ?: return@forEachIndexed
                if (row.sortOrder != position) db.projectDao().update(row.copy(sortOrder = position))
            }
        }
        ListSortStore.set(SortTab.PROJECTS, SortMethod.MANUAL)
    }

    fun createGroup(tab: String, projectId: Long?, name: String, parentId: Long? = null) = viewModelScope.launch {
        val pos = db.itemGroupDao().getForTab(tab).size
        db.itemGroupDao().upsert(
            ItemGroupEntity(projectId = projectId, tab = tab, name = name.trim(), position = pos, parentGroupId = parentId),
        )
    }

    fun renameGroup(group: ItemGroupEntity, name: String) = viewModelScope.launch {
        db.itemGroupDao().upsert(group.copy(name = name.trim()))
    }

    fun deleteGroup(group: ItemGroupEntity) = launchWithMessage("Group “${group.name}” deleted") { offer ->
        // Captured BEFORE the orphaning, because that is the part the undo has to put back: the
        // group row alone would return an empty group with its members still scattered.
        val members = db.itemMetaDao().getAll().filter { it.tab == group.tab && it.groupId == group.id }
        val children = db.itemGroupDao().getAll().filter { it.parentGroupId == group.id }
        db.itemMetaDao().clearGroup(group.tab, group.id) // orphan its members back to top level
        db.itemGroupDao().orphanChildren(group.id)       // its sub-groups float up to top level
        db.itemGroupDao().delete(group.id)
        offer { restoreGroup(group, members, children) }
    }

    fun toggleGroupExpanded(group: ItemGroupEntity) = viewModelScope.launch {
        db.itemGroupDao().upsert(group.copy(expanded = !group.expanded))
    }

    fun setGroupParent(group: ItemGroupEntity, parentId: Long?) = viewModelScope.launch {
        db.itemGroupDao().upsert(group.copy(parentGroupId = parentId))
    }

    fun setItemGroup(tab: String, itemKey: String, groupId: Long?) = viewModelScope.launch {
        val cur = db.itemMetaDao().get(tab, itemKey) ?: ItemMetaEntity(tab = tab, itemKey = itemKey)
        db.itemMetaDao().upsert(cur.copy(groupId = groupId))
    }

    /**
     * Persist a drag-to-reorder from a grouped list. [movedKey] is filed into [targetGroupId] (null = top
     * level), then every visible member's `position` is rewritten to match [orderedKeys] — the tab's members
     * in their NEW visual order (Members only). Finally the tab is forced to MANUAL sort so the new order is
     * honoured (Alphabetical would ignore `position`). Unknown tab → no-op; a key with no live row → skipped.
     */
    fun reorderItem(tab: String, movedKey: String, targetGroupId: Long?, orderedKeys: List<String>) {
        val sortTab = when (tab) {
            "tasks" -> SortTab.TASKS
            "profiles" -> SortTab.PROFILES
            "scenes" -> SortTab.SCENES
            else -> return // unknown tab: nothing to reorder
        }
        viewModelScope.launch {
            runCatching {
                db.withTransaction {
                    // 1. File the moved item into the drop target's group (get-or-create its meta row).
                    val cur = db.itemMetaDao().get(tab, movedKey) ?: ItemMetaEntity(tab = tab, itemKey = movedKey)
                    db.itemMetaDao().upsert(cur.copy(groupId = targetGroupId))
                    // 2. Rewrite each member's position to its new index (only when it actually changed).
                    orderedKeys.forEachIndexed { i, key ->
                        val id = key.toLongOrNull() ?: return@forEachIndexed
                        when (tab) {
                            "tasks" -> db.taskDao().getById(id)?.let { if (it.position != i) db.taskDao().setPosition(id, i) }
                            "profiles" -> db.profileDao().getById(id)?.let { if (it.position != i) db.profileDao().setPosition(id, i) }
                            "scenes" -> db.sceneDao().getById(id)?.let { if (it.position != i) db.sceneDao().setPosition(id, i) }
                        }
                    }
                }
                // 3. Force MANUAL sort so the freshly written positions drive the tab's order.
                ListSortStore.set(sortTab, SortMethod.MANUAL)
            }.onFailure { events.send(UiMessage("Error: ${it.message ?: "Reorder failed"}")) }
        }
    }

    /**
     * Persist a drag-to-reorder of the GROUPS: [orderedGroupIds] is one set of siblings in their new
     * visual order, and each gets its index written as `position`.
     *
     * Positions are only unique within a parent, and only the siblings passed here are touched, so a
     * reorder inside one branch cannot disturb another. Unlike [reorderItem] this does NOT force the
     * tab to MANUAL sort: group order has always come from `position` alone — the sort method applies
     * to the items inside a group, not to the groups.
     */
    fun reorderGroups(orderedGroupIds: List<Long>) = viewModelScope.launch {
        runCatching {
            db.withTransaction {
                orderedGroupIds.forEachIndexed { index, id ->
                    db.itemGroupDao().getById(id)?.let { group ->
                        if (group.position != index) db.itemGroupDao().upsert(group.copy(position = index))
                    }
                }
            }
        }.onFailure { events.send(UiMessage("Error: ${it.message ?: "Group reorder failed"}")) }
    }

    fun moveItemToNewGroup(tab: String, projectId: Long?, name: String, itemKey: String) = viewModelScope.launch {
        val pos = db.itemGroupDao().getForTab(tab).size
        val gid = db.itemGroupDao().upsert(ItemGroupEntity(projectId = projectId, tab = tab, name = name.trim(), position = pos))
        val cur = db.itemMetaDao().get(tab, itemKey) ?: ItemMetaEntity(tab = tab, itemKey = itemKey)
        db.itemMetaDao().upsert(cur.copy(groupId = gid))
    }

    fun moveProfilesToProject(items: List<Profile>, projectId: Long?) =
        launchWithMessage("${items.size} profile${plural(items.size)} moved") {
            items.forEach { db.profileDao().update(it.copy(projectId = projectId).toEntity()) }
        }

    fun moveTasksToProject(items: List<Task>, projectId: Long?) =
        launchWithMessage("${items.size} task${plural(items.size)} moved") {
            items.forEach { db.taskDao().update(it.copy(projectId = projectId).toEntity()) }
        }

    fun moveScenesToProject(items: List<Scene>, projectId: Long?) =
        launchWithMessage("${items.size} scene${plural(items.size)} moved") {
            items.forEach { db.sceneDao().update(it.copy(projectId = projectId).toEntity()) }
        }

    fun deleteScenes(items: List<Scene>) =
        launchWithMessage("Deleted ${items.size} scene${plural(items.size)}") { offer ->
            val rows = items.map { it.toEntity() }
            items.forEach {
                db.sceneDao().delete(it.toEntity())
                db.itemMetaDao().delete("scenes", it.id.toString())
            }
            offer { restoreScenes(rows) }
        }

    fun deleteProfiles(items: List<Profile>) =
        launchWithMessage("Deleted ${items.size} profile${plural(items.size)}") { offer ->
            val rows = items.map { it.toEntity() }
            items.forEach { db.profileDao().delete(it.toEntity()); locationDwellStateStore.clearProfile(it.id) }
            offer { restoreProfiles(rows) }
        }

    /** Delete several tasks at once, skipping any still referenced by a profile (same guard as [deleteTask]). */
    fun deleteTasks(items: List<Task>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val usedIds = db.profileDao().getAll().map { it.toDomain() }
                    .flatMap { listOfNotNull(it.enterTaskId, it.exitTaskId) }.toSet()
                val (used, free) = items.partition { it.id in usedIds }
                val rows = free.map { it.toEntity() }
                free.forEach { db.taskDao().delete(it.toEntity()); undoIcons[it.id] = it.iconPath }
                TaskWidgetProvider.requestRefresh(appContext)
                rows to buildString {
                    append("Deleted ${free.size} task${plural(free.size)}")
                    if (used.isNotEmpty()) append("; skipped ${used.size} used by a profile")
                }
            }
                .onSuccess { (rows, text) ->
                    val token = if (rows.isEmpty()) null else offerUndo(rows.map { it.id }) { restoreTasks(rows) }
                    events.send(UiMessage(text, token))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Delete failed"}")) }
        }
    }

    private val events = Channel<UiMessage>(Channel.BUFFERED)
    val messages = events.receiveAsFlow()

    /**
     * The work each outstanding Undo would do, keyed by the token its bar carries.
     *
     * In MEMORY, deliberately, and the snapshot it restores from is passed in the closure. The bar
     * is what makes an undo reachable and the bar does not survive the process, so persisting the
     * token would buy an offer nobody can accept. What IS durable is the deletion itself: it happens
     * immediately, because the engine, the widgets and the overlays read this database live and a
     * row that is "deleted" on screen while still running would be worse than no undo at all.
     *
     * Bounded, and oldest-first: an undo nobody took is litter, and holding a whole deleted project
     * forever because a bar scrolled past is how a tidy feature becomes a leak.
     */
    /** One outstanding offer: the work, and any icon files being held for it. */
    private class PendingUndo(val work: suspend () -> String, val taskIds: List<Long> = emptyList())

    private val pendingUndos = object : LinkedHashMap<String, PendingUndo>(0, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingUndo>): Boolean {
            // An offer that falls off the end takes its held icons with it, or a deleted task's PNG
            // would sit on disk forever waiting for an Undo nobody can press any more.
            if (size > MAX_PENDING_UNDOS) {
                forgetUndo(eldest.value.taskIds)
                return true
            }
            return false
        }
    }

    /** Register an undo and hand back the token that reaches it. */
    private fun offerUndo(taskIds: List<Long> = emptyList(), work: suspend () -> String): String {
        val token = "undo-${undoCounter.incrementAndGet()}"
        pendingUndos[token] = PendingUndo(work, taskIds)
        return token
    }

    private val undoCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Icon files belonging to deleted tasks, held while their Undo is still on offer.
     *
     * `deleteTask` used to remove the PNG immediately. That is right when the deletion is final and
     * wrong while it can be taken back: bytes off disk do not come back from a row snapshot, so the
     * task would return wearing no icon and nothing would say why. Swept when the offer lapses.
     */
    private val undoIcons = mutableMapOf<Long, String?>()

    /** The offer is gone — now the icon can go too. */
    private fun forgetUndo(taskIds: Collection<Long>) {
        taskIds.forEach { id -> undoIcons.remove(id)?.let { TaskIconStore.delete(it) } }
    }

    private companion object {
        /** How many outstanding Undos to keep. More bars than this and the oldest offer lapses. */
        const val MAX_PENDING_UNDOS = 10
    }

    /**
     * Take back the deletion the bar is offering.
     *
     * A token can be spent once: pressing Undo twice on a bar that lingered must not insert the row
     * twice. Anything already gone from the registry answers plainly rather than silently doing
     * nothing, because "I pressed it and cannot tell whether it worked" is its own small failure.
     */
    fun undo(token: String) {
        val pending = pendingUndos.remove(token)
        if (pending == null) {
            viewModelScope.launch { events.send(UiMessage("That undo is no longer available")) }
            return
        }
        viewModelScope.launch {
            runCatching { pending.work() }
                .onSuccess { events.send(UiMessage(it)) }
                .onFailure { events.send(UiMessage("Undo failed: ${it.message ?: "unknown error"}")) }
        }
    }

    private val _runLogRetentionPolicy = MutableStateFlow(runLogRetentionSettings.load())
    val runLogRetentionPolicy: StateFlow<RunLogRetentionPolicy> = _runLogRetentionPolicy.asStateFlow()

    private val _taskerImportReview = MutableStateFlow<TaskerImportReviewState?>(null)
    internal val taskerImportReview: StateFlow<TaskerImportReviewState?> = _taskerImportReview.asStateFlow()

    private val automationTransfer = ImportExportCoordinator(viewModelScope)
    val taskerImportBusy: StateFlow<Boolean> = automationTransfer.busy
    val taskerImportProgress: StateFlow<TransferProgress?> = automationTransfer.progress

    private val _openTaskerBundleReview = MutableStateFlow<OpenTaskerBundleReviewState?>(null)
    internal val openTaskerBundleReview: StateFlow<OpenTaskerBundleReviewState?> = _openTaskerBundleReview.asStateFlow()

    private val bundleTransfer = ImportExportCoordinator(viewModelScope)
    val openTaskerBundleBusy: StateFlow<Boolean> = bundleTransfer.busy
    val openTaskerBundleProgress: StateFlow<TransferProgress?> = bundleTransfer.progress

    /** Stops whichever transfer is running; nothing is written until a review is confirmed. */
    fun cancelTransfers() {
        automationTransfer.cancel()
        bundleTransfer.cancel()
    }

    /**
     * Guards the one-shot run actions. Their buttons stay enabled while the coroutine is in flight,
     * so a double tap ran the task — or replayed a held execution — twice, with real side effects
     * each time. Set and cleared on the main thread before any suspension point, so the second tap
     * always observes the first one's `true`.
     */
    private val _runActionBusy = MutableStateFlow(false)
    val runActionBusy: StateFlow<Boolean> = _runActionBusy.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { pruneRunLogs(_runLogRetentionPolicy.value) }
        }
    }

    fun createTask(name: String, priority: Int, projectId: Long? = null, iconPath: String? = null, freezeBubble: Boolean = false) = launchWithMessage("Task created") {
        db.taskDao().insert(Task(name = name.trim(), priority = priority.coerceIn(0, 10), projectId = projectId, iconPath = iconPath, freezeBubble = freezeBubble).toEntity())
    }

    fun renameTask(task: Task, name: String) = launchWithMessage("Renamed") {
        val n = name.trim()
        if (n.isNotEmpty() && n != task.name) db.taskDao().update(task.copy(name = n).toEntity())
    }

    /** A name not already taken by another task — "<base>", then "<base> (2)", "<base> (3)"… */
    private fun uniqueTaskName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var i = 2
        while ("$base ($i)" in taken) i++
        return "$base ($i)"
    }

    /** Clone tasks in place (same project), each "<name> (copy)". */
    fun duplicateTasks(items: List<Task>) = launchWithMessage("Duplicated ${items.size} task${plural(items.size)}") {
        val taken = db.taskDao().getAll().map { it.name }.toMutableSet()
        items.forEach { t ->
            val name = uniqueTaskName("${t.name} (copy)", taken)
            taken += name
            db.taskDao().insert(t.copy(id = 0, name = name, iconPath = null).toEntity())
        }
    }

    /** Paste clipboard tasks into [projectId]. On a Cut, [cutIds] are deleted first so a move keeps its
     *  original name; on a Copy [cutIds] is empty and same-named pastes get a " (2)" suffix. */
    fun pasteTasks(items: List<Task>, projectId: Long?, cutIds: List<Long>) = launchWithMessage("Pasted ${items.size} task${plural(items.size)}") {
        cutIds.forEach { id -> db.taskDao().getById(id)?.let { db.taskDao().delete(it) } }
        val taken = db.taskDao().getAll().map { it.name }.toMutableSet()
        items.forEach { t ->
            val name = uniqueTaskName(t.name, taken)
            taken += name
            db.taskDao().insert(t.copy(id = 0, name = name, projectId = projectId, iconPath = null).toEntity())
        }
    }

    fun updateTask(task: Task, message: String = "Task updated") = launchWithMessage(message) { offer ->
        val previous = db.taskDao().getById(task.id)
        if (previous != null) {
            // Offered whenever the edit made the list SHORTER, whatever route it came by — the
            // action row's Delete, the selection's Delete, a paste that replaced a block. "I removed
            // something" is exactly when an undo is wanted, and the snapshot already exists below,
            // so this costs a comparison rather than a mechanism.
            val before = runCatching {
                StorageJson.decodeFromString<List<com.opentasker.core.model.ActionSpec>>(previous.actionsJson).size
            }.getOrDefault(-1)
            if (before > task.actions.size) {
                val removed = before - task.actions.size
                val json = previous.actionsJson
                offer { restoreTaskActions(task.id, json, "$removed action${plural(removed)}") }
            }
            // DATA-LOSS GUARD (白い熊 critical): if the STORED actions currently fail to decode, the
            // in-memory task was built from an empty fallback — writing it would clobber recoverable JSON.
            // Refuse rather than persist a silent wipe. (Genuine "delete all actions" decodes cleanly →
            // no issue → allowed.)
            val prevIssue = previous.toDomainDecodeResult().issue
            if (prevIssue != null && task.actions.isEmpty() &&
                previous.actionsJson.isNotBlank() && previous.actionsJson.trim() != "[]") {
                AppLogger.error(
                    "ActiveAutomationVM",
                    "BLOCKED save of task ${task.id} '${task.name}': stored actions unreadable (${prevIssue.message}); not overwriting with empty",
                )
                events.send(UiMessage("Save blocked: this task's stored actions couldn't be read — not overwriting them"))
                return@launchWithMessage
            }
            // Trace every task write so a rogue overwriter (dropping actions) is identifiable in logcat.
            AppLogger.info(
                "ActiveAutomationVM",
                "updateTask id=${task.id} '${task.name}' actions ${previous.let { runCatching { StorageJson.decodeFromString<List<com.opentasker.core.model.ActionSpec>>(it.actionsJson).size }.getOrDefault(-1) }} -> ${task.actions.size}",
            )
        }
        // Atomic snapshot-prune-update (upstream deep-audit): a concurrent writer (dialog save vs.
        // notification/external path) can't interleave between the history snapshot and the update.
        db.withTransaction {
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
        // A replaced or cleared icon leaves its old PNG behind — remove it once the change is persisted.
        if (previous != null && previous.iconPath != task.iconPath) {
            TaskIconStore.delete(previous.iconPath)
        }
        // A rename leaves every task widget showing the old label until something asks them to
        // re-read it; nothing else does.
        TaskWidgetProvider.requestRefresh(appContext)
    }

    fun deleteTask(task: Task) {
        viewModelScope.launch {
            runCatching {
                val profilesUsingTask = db.profileDao().getAll().map { it.toDomain() }
                    .filter { it.enterTaskId == task.id || it.exitTaskId == task.id }
                if (profilesUsingTask.isNotEmpty()) {
                    events.send(UiMessage("Task is used by ${profilesUsingTask.size} profile(s). Reassign or delete those profiles first."))
                    return@launch
                }
                val row = task.toEntity()
                db.taskDao().delete(row)
                // The icon is the one thing a row snapshot cannot hold: it is a PNG on disk, and
                // deleting it here would make the undo restore a task with a blank icon. So it is
                // left in place, and only swept once the offer has lapsed — see forgetUndo.
                undoIcons[row.id] = task.iconPath
                // Otherwise a widget bound to this task keeps looking runnable and only answers a
                // tap with "Task not found".
                TaskWidgetProvider.requestRefresh(appContext)
                row
            }
                .onSuccess { row ->
                    val token = offerUndo(listOf(row.id)) { restoreTasks(listOf(row)) }
                    events.send(UiMessage("Task deleted", token))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Task delete failed"}")) }
        }
    }

    fun createScene(
        name: String, widthDp: Int, heightDp: Int, projectId: Long? = null,
        bgColor: String? = null, cornerRadiusDp: Int = 16, scrimAlpha: Int = 55,
        borderColor: String? = null, borderWidth: Int = 0,
        defaultPosition: String = "center", defaultModal: Boolean = true, defaultDismissOnOutside: Boolean = true,
    ) = launchWithMessage("Scene created") {
        db.sceneDao().insert(
            Scene(
                name = name.trim(),
                widthDp = widthDp.coerceIn(120, 1440),
                heightDp = heightDp.coerceIn(80, 2560),
                projectId = projectId,
                bgColor = bgColor,
                cornerRadiusDp = cornerRadiusDp,
                scrimAlpha = scrimAlpha,
                borderColor = borderColor,
                borderWidth = borderWidth,
                defaultPosition = defaultPosition,
                defaultModal = defaultModal,
                defaultDismissOnOutside = defaultDismissOnOutside,
            ).toEntity()
        )
    }

    fun updateScene(scene: Scene, message: String = "Scene updated") = launchWithMessage(message) {
        db.sceneDao().update(scene.toEntity())
    }

    fun deleteScene(scene: Scene) = launchWithMessage("Scene deleted") { offer ->
        val row = scene.toEntity()
        db.sceneDao().delete(row)
        offer { restoreScenes(listOf(row)) }
    }

    fun createProfile(
        name: String,
        enabled: Boolean,
        enterTaskId: Long,
        cooldownSec: Int,
        automationMode: AutomationMode,
        group: String? = null,
        projectId: Long? = null,
        policy: ProfilePolicyDraft = ProfilePolicyDraft.from(null),
    ) =
        launchWithMessage("Profile created") {
            val profile = Profile(
                name = name.trim(),
                enabled = enabled,
                enterTaskId = enterTaskId,
                cooldownSec = cooldownSec.coerceAtLeast(0),
                automationMode = automationMode,
                group = group,
                projectId = projectId,
            ).withPolicy(policy)
            requireValidProfileFieldLimits(profile)
            db.profileDao().insert(profile.toEntity())
        }

    fun updateProfile(profile: Profile, message: String = "Profile updated") =
        launchWithMessage(message) { offer ->
            requireValidProfileFieldLimits(profile)
            // Atomic read-check-snapshot-update (upstream deep-audit), so racing writers
            // (dialog save vs. notification/external-intent path) can't lose a revision.
            // The fork drops upstream's corrupt-record throw here: corrupt rows surface via the
            // storageDecodeIssues banner instead of blocking every profile save path.
            db.withTransaction {
                val previousEntity = profile.id.takeIf { it > 0L }
                    ?.let { db.profileDao().getById(it) }
                val previous = previousEntity?.toDomain()
                if (
                    previous?.requiresRiskAcknowledgement == true &&
                    (profile.enabled || !profile.requiresRiskAcknowledgement)
                ) {
                    throw IllegalStateException("Review imported automation powers before enabling this profile.")
                }
                if (profile.enabled && previous?.enabled != true) {
                    writeSettingsGuard.requireWriteSettingsIfEnabled(profile)
                }
                if (previousEntity != null) {
                    // Same rule as a task's actions: a shorter list is a removal, and a removal is
                    // the moment to offer the way back.
                    val before = previous?.contexts?.size ?: -1
                    if (before > profile.contexts.size) {
                        val removed = before - profile.contexts.size
                        val json = previousEntity.contextsJson
                        offer { restoreProfileContexts(profile.id, json, "$removed context${plural(removed)}") }
                    }
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
                    previous.contexts.indices.forEach { index ->
                        LocaleConditionGrantStore(appContext).revokeAllForBinding(
                            LocaleConditionGrantStore.contextKey(profile.id, index),
                        )
                    }
                }
                db.profileDao().update(profile.toEntity())
            }
        }

    fun deleteProfile(profile: Profile) = launchWithMessage("Profile deleted") { offer ->
        val row = profile.toEntity()
        db.profileDao().delete(row)
        offer { restoreProfiles(listOf(row)) }
        LocaleConditionGrantStore(appContext).apply {
            revokeAllForBinding(LocaleConditionGrantStore.profileKey(profile.id))
            profile.contexts.indices.forEach { index ->
                revokeAllForBinding(LocaleConditionGrantStore.contextKey(profile.id, index))
            }
        }
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
        automationTransfer.launch { report ->
            runCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Preflight)
                    val raw = readBoundedTaskerOrMacroDroid(appContext, uri)
                    report(TransferStage.Decode)
                    if (raw.removePrefix("\uFEFF").trimStart().startsWith("<")) {
                        taskerImportReviewState(TaskerXmlImporter.parse(rawXml = raw, appVersion = appVersion))
                    } else {
                        macroDroidImportReviewState(MacroDroidImporter.parse(rawJson = raw, appVersion = appVersion))
                    }
                }
            }
                .onSuccess {
                    _taskerImportReview.value = it
                    events.send(UiMessage("${it.title.removePrefix("Review ").removeSuffix(" import")} import ready for review"))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Import preview failed"}")) }
        }
    }

    fun clearTaskerImportReview() {
        if (!taskerImportBusy.value) {
            _taskerImportReview.value = null
        }
    }

    internal fun confirmTaskerImport(state: TaskerImportReviewState) {
        automationTransfer.launch { report ->
            runCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Write)
                    bundleRepository.importBundle(state.bundle)
                }
            }
                .onSuccess { importReport ->
                    _taskerImportReview.value = null
                    events.send(UiMessage(
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}"
                    ))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Tasker XML import failed"}")) }
        }
    }

    fun exportOpenTaskerBundle(uri: Uri, appVersion: String) {
        bundleTransfer.launch { report ->
            runCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Write)
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
                    events.send(UiMessage(
                        "Exported ${bundle.tasks.size} task${plural(bundle.tasks.size)}, " +
                            "${bundle.profiles.size} profile${plural(bundle.profiles.size)}, " +
                            "${bundle.scenes.size} scene${plural(bundle.scenes.size)}"
                    ))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "export failed"}")) }
        }
    }

    /**
     * Export exactly the chosen items as a bundle (the fork's selective export — Export profiles/tasks/
     * scenes/templates/variables, and per-project export). Reuses [OpenTaskerBundleRepository.exportSelection];
     * variables are included only when [includeVariables] or specific [variableKeys] are given.
     */
    fun exportSelectionBundle(
        uri: Uri,
        appVersion: String,
        profileIds: Set<Long>,
        taskIds: Set<Long>,
        sceneIds: Set<Long>,
        includeVariables: Boolean,
        name: String,
        templateNames: Set<String> = emptySet(),
        variableKeys: Set<String> = emptySet(),
    ) {
        bundleTransfer.launch { report ->
            runCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Write)
                    val bundle = bundleRepository.exportSelection(
                        appVersion = appVersion,
                        profileIds = profileIds,
                        taskIds = taskIds,
                        sceneIds = sceneIds,
                        includeVariables = includeVariables,
                        name = name,
                        templateNames = templateNames,
                        variableKeys = variableKeys,
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
                        if (bundle.templates.isNotEmpty()) add("${bundle.templates.size} template${plural(bundle.templates.size)}")
                    }
                    events.send(UiMessage("Exported ${parts.joinToString().ifEmpty { "nothing" }}"))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Export failed"}")) }
        }
    }

    fun previewOpenTaskerBundle(uri: Uri) {
        bundleTransfer.launch { report ->
            runCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Preflight)
                    val rawJson = readBoundedOpenTaskerBundle(appContext, uri)
                    report(TransferStage.Decode)
                    val bundle = OpenTaskerBundleCodec.decode(rawJson)
                    OpenTaskerBundleReviewState(bundle = bundle, plan = OpenTaskerBundleCodec.validate(bundle))
                }
            }
                .onSuccess {
                    _openTaskerBundleReview.value = it
                    events.send(UiMessage("Import ready to review"))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "import preview failed"}")) }
        }
    }

    fun clearOpenTaskerBundleReview() {
        if (!openTaskerBundleBusy.value) {
            _openTaskerBundleReview.value = null
        }
    }

    fun confirmOpenTaskerBundleImport(
        bundle: OpenTaskerBundle,
        projectConflictStrategy: ProjectConflictStrategy = ProjectConflictStrategy.MERGE,
        itemConflictStrategy: ItemConflictStrategy = ItemConflictStrategy.OVERWRITE_DELETE,
        itemStrategyOverrides: Map<String, ItemConflictStrategy> = emptyMap(),
        projectChoices: Map<String, ProjectImportChoice> = emptyMap(),
    ) {
        bundleTransfer.launch { report ->
            runCatching {
                withContext(Dispatchers.IO) {
                    report(TransferStage.Write)
                    bundleRepository.importBundle(
                        bundle, projectConflictStrategy, itemConflictStrategy, itemStrategyOverrides, projectChoices,
                    )
                }
            }
                .onSuccess { importReport ->
                    _openTaskerBundleReview.value = null
                    events.send(UiMessage(
                        "Imported ${importReport.insertedTasks} task${plural(importReport.insertedTasks)}, " +
                            "${importReport.insertedProfiles} disabled profile${plural(importReport.insertedProfiles)}, " +
                            "${importReport.insertedScenes} scene${plural(importReport.insertedScenes)}"
                    ))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "import failed"}")) }
        }
    }

    /**
     * Deletes ordinary run history on request. Pinned rows and held rows waiting to be replayed
     * survive, because a log purge has no Undo and those are the rows a user cannot recreate.
     */
    fun clearRunLog() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { db.runLogDao().clearUnpinned() }
            }
                .onSuccess { deleted -> events.send(UiMessage("Cleared $deleted run log entr${if (deleted == 1) "y" else "ies"}")) }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Run log clear failed"}")) }
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
                    events.send(UiMessage("Run log retention updated$suffix"))
                }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Run log retention update failed"}")) }
        }
    }

    private suspend fun pruneRunLogs(policy: RunLogRetentionPolicy): Int =
        db.runLogDao().pruneRetention(
            maxEntries = policy.maxEntries,
            minimumTimestamp = policy.minimumTimestamp(System.currentTimeMillis()),
        )

    /**
     * Puts the same redacted report Share sends onto the clipboard.
     *
     * Share opens a chooser, which is the wrong shape for pasting into a bug report, so issue
     * reports arrived as screenshots of this screen instead of its text.
     */
    fun copyDiagnosticReport() {
        viewModelScope.launch {
            try {
                val report = DiagnosticExport.buildReport(appContext, db)
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                    ?: throw IllegalStateException("Clipboard service is unavailable")
                clipboard.setPrimaryClip(ClipData.newPlainText("白い熊 自由作業盤 Diagnostic Report", report))
                events.send(UiMessage("Diagnostic report copied to the clipboard."))
            } catch (ex: Exception) {
                events.send(UiMessage("Error: ${ex.message ?: "Failed to copy the diagnostic report"}"))
            }
        }
    }

    fun shareDiagnosticReport() {
        viewModelScope.launch {
            try {
                val report = DiagnosticExport.buildReport(appContext, db)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "白い熊 自由作業盤 Diagnostic Report")
                    putExtra(Intent.EXTRA_TEXT, report)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(Intent.createChooser(intent, "Share diagnostic report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ex: Exception) {
                events.send(UiMessage("Error: ${ex.message ?: "Failed to share diagnostic report"}"))
            }
        }
    }

    /**
     * Re-runs a refused execution from its held run-log row.
     *
     * The row is consumed FIRST, and only a consume that actually changed a row proceeds: `clearHeld`
     * is `WHERE id = :id AND held = 1`, so two taps — or two windows — cannot both win and run the
     * work twice. That ordering also means a replay that then fails does not leave the entry
     * re-armed, which is the honest outcome: it was attempted, and the new run has its own log row.
     *
     * [_runActionBusy] is held on top of that, so a replay cannot start while a manual run is still
     * in flight, or the other way round. The consume already defeats two taps on the *same* row; the
     * flag is what stops two *different* one-shot runs from overlapping.
     */
    /**
     * Runs one action of [task] on its own, so tuning an HTTP call or a variable write does not mean
     * re-running everything before it.
     *
     * It goes through the same execution path as a whole-task manual run, against the engine's live
     * admission controller, so limits, the collision policy and the run log all behave as they do for
     * a real run. Flow-control markers are refused: the menu does not offer them, and
     * [SingleActionRun.taskFor] refuses them again in case a stale index arrives.
     */
    fun runActionNow(task: Task, index: Int) {
        viewModelScope.launch {
            if (_runActionBusy.value) { events.send(UiMessage("A run is already in flight.")); return@launch }
            val single = SingleActionRun.taskFor(task, index) ?: return@launch
            val action = single.actions.first()
            val label = action.label?.takeIf { it.isNotBlank() }
                ?: ActionMetadataRegistry.get(action.type)?.name
                ?: action.type
            _runActionBusy.value = true
            // finally, not a trailing assignment: executeAndLogTask is not wrapped in a runCatching
            // here, so a throw would otherwise leave the flag standing and disable Run for the rest
            // of the process.
            try {
                val result = executeAndLogTask(
                    appContext = appContext,
                    db = db,
                    task = single,
                    source = SingleActionRun.sourceFor(label),
                    admissionController = ExecutionAdmissionRegistry.current(appContext),
                )
                val status = if (result.report.success) "succeeded" else "failed"
                events.send(UiMessage("$label $status (${result.report.durationMs}ms)"))
            } finally {
                _runActionBusy.value = false
            }
        }
    }

    fun replayHeldRun(entry: RunLogEntry) {
        viewModelScope.launch {
            if (_runActionBusy.value) return@launch
            _runActionBusy.value = true
            // finally, not a trailing assignment: every early return below leaves through it, and
            // executeAndLogTask is not wrapped in a runCatching here — a throw would otherwise leave
            // the flag standing and disable both run actions for the rest of the process.
            try {
                val entryId = entry.id
                val consumed = runCatching { db.runLogDao().clearHeld(entryId) }.getOrDefault(0)
                if (consumed == 0) {
                    events.send(UiMessage("That held run has already been replayed."))
                    return@launch
                }
                val payload = HeldExecutionPayloadCodec.decode(entry.heldPayload)
                if (payload == null) {
                    events.send(UiMessage("That held run no longer carries enough detail to replay."))
                    return@launch
                }
                val task = runCatching { db.taskDao().getById(payload.taskId)?.toDomain() }.getOrNull()
                if (task == null) {
                    events.send(UiMessage("${payload.taskName} no longer exists."))
                    return@launch
                }
                val result = executeAndLogTask(
                    appContext = appContext,
                    db = db,
                    task = task,
                    source = "Replay: ${payload.source}",
                    metadata = payload.metadata,
                    initialVariables = payload.initialVariables,
                )
                val status = if (result.report.success) "succeeded" else "failed"
                events.send(UiMessage("${task.name} $status (${result.report.durationMs}ms)"))
            } finally {
                _runActionBusy.value = false
            }
        }
    }

    fun runTaskNow(task: Task) {
        viewModelScope.launch {
            if (_runActionBusy.value) { events.send(UiMessage("A run is already in flight.")); return@launch }
            _runActionBusy.value = true
            // finally, not a plain trailing assignment: executeAndLogTask is not wrapped in a
            // runCatching here, so a throw would otherwise leave the flag standing and disable
            // Run now for the rest of the process.
            try {
                val result = executeAndLogTask(
                    appContext = appContext,
                    db = db,
                    task = task,
                    source = "Manual run",
                )
                val status = if (result.report.success) "succeeded" else "failed"
                events.send(UiMessage("${task.name} $status (${result.report.durationMs}ms)"))
            } finally {
                _runActionBusy.value = false
            }
        }
    }

    fun pinTaskShortcut(task: Task) {
        viewModelScope.launch {
            if (!TaskShortcutHelper.canPinShortcut(appContext)) {
                events.send(UiMessage("Launcher does not support pinned shortcuts"))
                return@launch
            }
            val requested = TaskShortcutHelper.requestPinShortcut(appContext, task)
            if (requested) {
                events.send(UiMessage("Pinning \"${task.name}\" to home screen"))
            } else {
                events.send(UiMessage("Failed to pin shortcut"))
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
                events.send(UiMessage(if (undone) "Edit undone" else "No edit history available"))
            }.onFailure { events.send(UiMessage("Error: ${it.message ?: "Undo failed"}")) }
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
                events.send(UiMessage(if (undone) "Edit undone" else "No edit history available"))
            }.onFailure { events.send(UiMessage("Error: ${it.message ?: "Undo failed"}")) }
        }
    }

    // Variables are keyed by (projectId, name) in the fork — the VariablesScreen supplies the scope's
    // projectId (0 = super-global, >0 = project-global), so we thread it straight through to the DAO.
    fun updateVariable(projectId: Long, name: String, value: String) {
        viewModelScope.launch {
            db.variableDao().insert(VariableEntity(projectId = projectId, name = name, value = value))
            // Keep the runtime cache in sync (it warms once at startup; UI edits must route through it too).
            com.opentasker.core.engine.variables.PersistentGlobalScope.set(projectId, name, value)
        }
    }

    fun deleteVariable(projectId: Long, name: String) {
        viewModelScope.launch {
            // Report real success/failure (upstream deep-audit): no optimistic toast on a failed delete.
            runCatching {
                db.variableDao().delete(projectId, name)
                com.opentasker.core.engine.variables.PersistentGlobalScope.unset(projectId, name)
                // A Locale-condition grant outlives the variable it names unless it is revoked here,
                // so a variable recreated under the same name would honour the old token again.
                LocaleConditionGrantStore(appContext)
                    .revokeAllForBinding(LocaleConditionGrantStore.variableKey(projectId, name))
            }
                .onSuccess { events.send(UiMessage("Variable deleted")) }
                .onFailure { events.send(UiMessage("Error: ${it.message ?: "Variable could not be deleted"}")) }
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

    /** Remove the dead super-globals the Variables-tab analyzer found (shadow-copies + orphans). Each is a
     *  super-global (projectId 0); deleting the exact row leaves every project's live copy untouched. The
     *  runtime cache is unset too, so it never lingers with a stale copy (which diverged the export). */
    fun deleteDeadGlobals(vars: List<com.opentasker.core.model.Variable>) =
        launchWithMessage("Removed ${vars.size} dead global${plural(vars.size)}") {
            vars.forEach {
                db.variableDao().delete(it.projectId, it.name)
                com.opentasker.core.engine.variables.PersistentGlobalScope.unset(it.projectId, it.name)
            }
        }

    /**
     * Restore a task's action list to what it was before the last edit — the undo behind
     * "Action removed", and the cheapest of the four tiers.
     *
     * Nothing is re-inserted: the row never went anywhere, `updateTask` already snapshotted its
     * `actionsJson` into `edit_history`, and putting the old JSON back is the whole operation. That
     * is why tier 1 costs almost nothing and why it covers contexts and scene elements too.
     */
    /**
     * Put deleted rows back **under their original ids**.
     *
     * The id is the whole difficulty. `Run on start`, `Run on exit`, widget bindings and a profile's
     * `enterTaskId`/`exitTaskId` all point at tasks by number, so a restore that took a fresh id
     * would look successful and leave every one of them pointing at nothing — which is exactly what
     * happened to 白い熊's Run-on-start entry on 2026-09-10, from the other direction. Room honours
     * an explicit non-zero id on an `autoGenerate` key, so the original goes back in.
     *
     * The one case that cannot: without `AUTOINCREMENT` SQLite reuses the highest free rowid, so
     * deleting the newest row and creating another before pressing Undo can hand that id away. Then
     * the row comes back under a new number and the message SAYS so, rather than reporting a clean
     * restore over a reference that no longer resolves.
     */
    private suspend fun restoreTasks(entities: List<TaskEntity>): String {
        var renumbered = 0
        db.withTransaction {
            entities.forEach { row ->
                if (db.taskDao().getById(row.id) == null) {
                    db.taskDao().insert(row)
                } else {
                    db.taskDao().insert(row.copy(id = 0))
                    renumbered++
                }
            }
        }
        // Restored: the icons are theirs again, so release them from the holding pen unharmed.
        entities.forEach { undoIcons.remove(it.id) }
        TaskWidgetProvider.requestRefresh(appContext)
        val what = "${entities.size} task${plural(entities.size)}"
        return if (renumbered == 0) {
            "Restored $what"
        } else {
            "Restored $what — $renumbered had to be renumbered, so check Monitor → Run on start"
        }
    }

    private suspend fun restoreProfiles(entities: List<ProfileEntity>): String {
        db.withTransaction { entities.forEach { db.profileDao().insert(it) } }
        return "Restored ${entities.size} profile${plural(entities.size)}"
    }

    private suspend fun restoreScenes(entities: List<SceneEntity>): String {
        db.withTransaction { entities.forEach { db.sceneDao().insert(it) } }
        return "Restored ${entities.size} scene${plural(entities.size)}"
    }

    /**
     * A group, and the membership that went with it.
     *
     * Deleting a group orphans its members back to the top level rather than deleting them, so the
     * undo has to put the group back AND re-point the rows that were in it — otherwise the group
     * returns empty and the items stay where the delete scattered them.
     */
    private suspend fun restoreGroup(
        group: ItemGroupEntity,
        members: List<ItemMetaEntity>,
        children: List<ItemGroupEntity>,
    ): String {
        db.withTransaction {
            db.itemGroupDao().upsert(group)
            members.forEach { db.itemMetaDao().upsert(it) }
            children.forEach { db.itemGroupDao().upsert(it) }
        }
        return "Restored group “${group.name}” and ${members.size} item${plural(members.size)}"
    }

    private suspend fun restoreTaskActions(taskId: Long, actionsJson: String, what: String): String {
        val current = db.taskDao().getById(taskId) ?: return "“$what” is gone — nothing to undo into"
        db.taskDao().update(current.copy(actionsJson = actionsJson))
        return "Restored $what"
    }

    private suspend fun restoreProfileContexts(profileId: Long, contextsJson: String, what: String): String {
        val current = db.profileDao().getById(profileId) ?: return "“$what” is gone — nothing to undo into"
        db.profileDao().update(current.copy(contextsJson = contextsJson))
        return "Restored $what"
    }

    /**
     * Run [block], then say what happened — and offer to take it back if [block] asked.
     *
     * The block receives a sink: calling `it { ... }` registers the work that would reverse what is
     * about to happen, and the bar then carries an Undo pill. Every existing caller ignores the
     * parameter and reads exactly as it did, which is why this is a sink rather than a new function
     * — one message path, one place that decides how a message reaches the screen.
     */
    private fun launchWithMessage(
        successMessage: String,
        block: suspend (offer: (suspend () -> String) -> Unit) -> Unit,
    ) {
        viewModelScope.launch {
            var token: String? = null
            runCatching { block { work -> token = offerUndo(work = work) } }
                .onSuccess { events.send(UiMessage(successMessage, token)) }
                .onFailure {
                    // The undo is dropped rather than left dangling: if the change did not happen,
                    // an offer to reverse it would reverse something nobody did.
                    token?.let { t -> pendingUndos.remove(t) }
                    events.send(UiMessage("Error: ${it.message ?: "Operation failed"}"))
                }
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

/**
 * One picker serves both formats (upstream 0.2.88), so the read is bounded by the larger of the two
 * caps and the caller decides what it got by looking at the first non-blank character.
 */
internal fun readBoundedTaskerOrMacroDroid(context: Context, uri: Uri): String {
    return readBoundedDocumentText(
        context = context,
        uri = uri,
        maxBytes = maxOf(TASKER_XML_IMPORT_MAX_BYTES, OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES),
        label = "Tasker XML or MacroDroid backup",
    )
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
    val bytes = readBoundedDocumentBytes(
        context = context,
        uri = uri,
        maxBytes = OPEN_TASKER_BUNDLE_IMPORT_MAX_BYTES,
        label = "import",
    )
    // A settings ZIP (白い熊 自由作業盤-…-export_….zip) is accepted here too: its workspace.json
    // entry IS the standard full export, so picking the ZIP behaves exactly like picking that JSON.
    return if (com.opentasker.core.transfer.SettingsBackup.isZip(bytes)) {
        com.opentasker.core.transfer.SettingsBackup.workspaceJsonFrom(bytes)
    } else {
        String(bytes, Charsets.UTF_8)
    }
}

internal fun readBoundedDocumentText(context: Context, uri: Uri, maxBytes: Int, label: String): String =
    String(readBoundedDocumentBytes(context, uri, maxBytes, label), Charsets.UTF_8)

internal fun readBoundedDocumentBytes(context: Context, uri: Uri, maxBytes: Int, label: String): ByteArray {
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
        return output.toByteArray()
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
