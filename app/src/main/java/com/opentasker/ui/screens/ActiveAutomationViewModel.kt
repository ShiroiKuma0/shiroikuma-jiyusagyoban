package com.opentasker.ui.screens

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
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.AutomationMode
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
import com.opentasker.core.transfer.TaskerXmlImportReport
import com.opentasker.core.transfer.TaskerXmlImporter
import com.opentasker.widget.TaskShortcutHelper
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
    val report: TaskerXmlImportReport,
    val preview: TaskerImportPreview,
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
    private val databaseBackupManager = DatabaseBackupManager(appContext, db)

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
    ) {
        val pid = project.id
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
            // The project's foldable groups are project-scoped — delete them with the project so they don't
            // orphan. (Reassigned items keep their notes; a now-dangling groupId just reads as ungrouped.)
            db.itemGroupDao().deleteForProject(pid)
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

    fun createGroup(tab: String, projectId: Long?, name: String, parentId: Long? = null) = viewModelScope.launch {
        val pos = db.itemGroupDao().getForTab(tab).size
        db.itemGroupDao().upsert(
            ItemGroupEntity(projectId = projectId, tab = tab, name = name.trim(), position = pos, parentGroupId = parentId),
        )
    }

    fun renameGroup(group: ItemGroupEntity, name: String) = viewModelScope.launch {
        db.itemGroupDao().upsert(group.copy(name = name.trim()))
    }

    fun deleteGroup(group: ItemGroupEntity) = viewModelScope.launch {
        db.itemMetaDao().clearGroup(group.tab, group.id) // orphan its members back to top level
        db.itemGroupDao().orphanChildren(group.id)       // its sub-groups float up to top level
        db.itemGroupDao().delete(group.id)
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
            }.onFailure { events.send("Error: ${it.message ?: "Reorder failed"}") }
        }
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
        launchWithMessage("Deleted ${items.size} scene${plural(items.size)}") {
            items.forEach {
                db.sceneDao().delete(it.toEntity())
                db.itemMetaDao().delete("scenes", it.id.toString())
            }
        }

    fun deleteProfiles(items: List<Profile>) =
        launchWithMessage("Deleted ${items.size} profile${plural(items.size)}") {
            items.forEach { db.profileDao().delete(it.toEntity()); locationDwellStateStore.clearProfile(it.id) }
        }

    /** Delete several tasks at once, skipping any still referenced by a profile (same guard as [deleteTask]). */
    fun deleteTasks(items: List<Task>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val usedIds = db.profileDao().getAll().map { it.toDomain() }
                    .flatMap { listOfNotNull(it.enterTaskId, it.exitTaskId) }.toSet()
                val (used, free) = items.partition { it.id in usedIds }
                free.forEach { db.taskDao().delete(it.toEntity()); TaskIconStore.delete(it.iconPath) }
                buildString {
                    append("Deleted ${free.size} task${plural(free.size)}")
                    if (used.isNotEmpty()) append("; skipped ${used.size} used by a profile")
                }
            }
                .onSuccess { events.send(it) }
                .onFailure { events.send("Error: ${it.message ?: "Delete failed"}") }
        }
    }

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

    fun createTask(name: String, priority: Int, projectId: Long? = null, iconPath: String? = null, freezeBubble: Boolean = false) = launchWithMessage("Task created") {
        db.taskDao().insert(Task(name = name.trim(), priority = priority.coerceIn(0, 10), projectId = projectId, iconPath = iconPath, freezeBubble = freezeBubble).toEntity())
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
        // A replaced or cleared icon leaves its old PNG behind — remove it once the change is persisted.
        if (previous != null && previous.iconPath != task.iconPath) {
            TaskIconStore.delete(previous.iconPath)
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
                TaskIconStore.delete(task.iconPath)
            }
                .onSuccess { events.send("Task deleted") }
                .onFailure { events.send("Error: ${it.message ?: "Task delete failed"}") }
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

    fun deleteScene(scene: Scene) = launchWithMessage("Scene deleted") {
        db.sceneDao().delete(scene.toEntity())
    }

    fun createProfile(name: String, enabled: Boolean, enterTaskId: Long, cooldownSec: Int, automationMode: AutomationMode, group: String? = null, projectId: Long? = null) =
        launchWithMessage("Profile created") {
            db.profileDao().insert(
                Profile(
                    name = name.trim(),
                    enabled = enabled,
                    enterTaskId = enterTaskId,
                    cooldownSec = cooldownSec.coerceAtLeast(0),
                    automationMode = automationMode,
                    group = group,
                    projectId = projectId,
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
                .onFailure { events.send("Error: ${it.message ?: "export failed"}") }
            _openTaskerBundleBusy.value = false
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
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
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
                    events.send("Exported ${parts.joinToString().ifEmpty { "nothing" }}")
                }
                .onFailure { events.send("Error: ${it.message ?: "Export failed"}") }
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
                    events.send("Import ready to review")
                }
                .onFailure { events.send("Error: ${it.message ?: "import preview failed"}") }
            _openTaskerBundleBusy.value = false
        }
    }

    fun clearOpenTaskerBundleReview() {
        if (!_openTaskerBundleBusy.value) {
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
        viewModelScope.launch {
            if (_openTaskerBundleBusy.value) return@launch
            _openTaskerBundleBusy.value = true
            runCatching {
                withContext(Dispatchers.IO) {
                    bundleRepository.importBundle(
                        bundle, projectConflictStrategy, itemConflictStrategy, itemStrategyOverrides, projectChoices,
                    )
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
                .onFailure { events.send("Error: ${it.message ?: "import failed"}") }
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
                    putExtra(Intent.EXTRA_SUBJECT, "白い熊 自由作業盤 Diagnostic Report")
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
                .onSuccess { events.send("Backup imported. Restart 白い熊 自由作業盤 to apply the restore.") }
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

    // Variables are keyed by (projectId, name) in the fork — the VariablesScreen supplies the scope's
    // projectId (0 = super-global, >0 = project-global), so we thread it straight through to the DAO.
    fun updateVariable(projectId: Long, name: String, value: String) {
        viewModelScope.launch {
            db.variableDao().insert(VariableEntity(projectId = projectId, name = name, value = value))
        }
    }

    fun deleteVariable(projectId: Long, name: String) {
        viewModelScope.launch {
            db.variableDao().delete(projectId, name)
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
        label = "import",
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
