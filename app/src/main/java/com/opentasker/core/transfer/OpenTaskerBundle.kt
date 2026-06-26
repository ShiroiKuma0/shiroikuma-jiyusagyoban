package com.opentasker.core.transfer

import androidx.room.withTransaction
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.ItemGroupEntity
import com.opentasker.core.storage.ItemMetaEntity
import com.opentasker.core.storage.ListSortStore
import com.opentasker.widget.TemplateStore
import com.opentasker.widget.WidgetTemplate
import com.opentasker.core.storage.SortMethod
import com.opentasker.core.storage.SortPrefs
import com.opentasker.core.storage.VariableEntity
import com.opentasker.core.storage.toEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// v4 adds `templates` (widget-layout templates, previously a separate processor). v3 added per-item
// `position` + per-category `sort`; v2 added `projects` + projectId. Older bundles still import (missing
// fields default), so the version is a floor on what THIS build can read, not a gate.
// Tasks may also carry an optional base64 `iconData` (embedded icon PNG, for cross-device imports); it's
// purely additive — older builds ignore the unknown key — so the schema version stays 4.
const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 4

@Serializable
data class OpenTaskerBundle(
    val schemaVersion: Int = OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMs: Long,
    val metadata: BundleMetadata = BundleMetadata(),
    val projects: List<Project> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val profiles: List<Profile> = emptyList(),
    val variables: List<Variable> = emptyList(),
    val scenes: List<Scene> = emptyList(),
    val templates: List<WidgetTemplate> = emptyList(),
    val sort: BundleSortConfig = BundleSortConfig(),
    val itemMeta: List<ItemMetaSpec> = emptyList(),
    val groups: List<ItemGroupSpec> = emptyList(),
)

/**
 * A per-item note (+ its fold state) and group membership carried in the bundle. On import [itemKey] is
 * remapped to the item's new key (tasks/profiles/scenes/widgets) and [groupId] to the imported group id.
 */
@Serializable
data class ItemMetaSpec(
    val tab: String,
    val itemKey: String,
    val note: String = "",
    val noteExpanded: Boolean = false,
    val groupId: Long? = null,
)

/** A foldable group carried in the bundle. [id] is bundle-local — items reference it via ItemMetaSpec.groupId. */
@Serializable
data class ItemGroupSpec(
    val id: Long,
    val tab: String,
    val projectId: Long? = null,
    val name: String,
    val note: String = "",
    val position: Int = 0,
    val expanded: Boolean = true,
    val noteExpanded: Boolean = false,
    val parentGroupId: Long? = null,
)

/** Per-category sort method carried in the bundle, so a tab's Alphabetical/Manual choice round-trips. */
@Serializable
data class BundleSortConfig(
    val profiles: SortMethod = SortMethod.ALPHABETICAL,
    val tasks: SortMethod = SortMethod.ALPHABETICAL,
    val scenes: SortMethod = SortMethod.ALPHABETICAL,
    val projects: SortMethod = SortMethod.MANUAL,
) {
    fun toPrefs(): SortPrefs = SortPrefs(profiles = profiles, tasks = tasks, scenes = scenes, projects = projects)

    companion object {
        fun from(prefs: SortPrefs) = BundleSortConfig(prefs.profiles, prefs.tasks, prefs.scenes, prefs.projects)
    }
}

@Serializable
data class BundleMetadata(
    val name: String = "白い熊 自由作業盤 Export",
    val description: String = "",
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CapabilityRequirement(
    val actionId: String,
    val level: CapabilityLevel,
    val reason: String,
)

/** How to handle a bundle project whose name already exists in the workspace. */
enum class ProjectConflictStrategy {
    /** Reuse the existing project (imported items are filed under it). */
    MERGE,

    /** Create a separate project, uniquifying the name (e.g. "Home (2)"). */
    RENAME,
}

/** How to handle a bundle item (task/profile/scene/template) whose name already exists. */
enum class ItemConflictStrategy {
    /** Keep both — the incoming item gets a uniquified name (e.g. "Foo (2)"). */
    RENAME,

    /** Delete the existing same-name item(s), then import the incoming under its original name. */
    OVERWRITE_DELETE,

    /** Back up the existing same-name item(s) (rename to "<name>.<timestamp>.bak"), then import the
     *  incoming under its original name. */
    OVERWRITE_BACKUP,
}

data class BundleImportPlan(
    val canImport: Boolean,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

data class BundleImportReport(
    val insertedTasks: Int,
    val insertedProfiles: Int,
    val insertedVariables: Int,
    val insertedScenes: Int,
    val insertedTemplates: Int = 0,
    val insertedProjects: Int = 0,
    /** Distinct projects the imported tasks/profiles/scenes landed in ("Unfiled" for items with none). */
    val projectNames: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

object OpenTaskerBundleCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        // Tolerate keys this build doesn't know yet, so future (v3+) bundles still decode.
        ignoreUnknownKeys = true
    }

    fun build(
        appVersion: String,
        exportedAtEpochMs: Long,
        profiles: List<Profile>,
        tasks: List<Task>,
        variables: List<Variable> = emptyList(),
        scenes: List<Scene> = emptyList(),
        templates: List<WidgetTemplate> = emptyList(),
        projects: List<Project> = emptyList(),
        sort: BundleSortConfig = BundleSortConfig(),
        name: String = "白い熊 自由作業盤 Export",
        description: String = "",
        itemMeta: List<ItemMetaSpec> = emptyList(),
        groups: List<ItemGroupSpec> = emptyList(),
    ): OpenTaskerBundle {
        // Order the arrays by manual position (then name) so the JSON reflects manual order; each
        // item also carries its own `position`, which is what import restores.
        val sortedTasks = tasks.sortedWith(compareBy<Task> { it.position }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val sortedProfiles = profiles.sortedWith(compareBy<Profile> { it.position }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val sortedVariables = variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name })
        val sortedScenes = scenes.sortedWith(compareBy<Scene> { it.position }.thenBy { it.name.lowercase() }.thenBy { it.id })
        val sortedTemplates = templates.sortedBy { it.name.lowercase() }
        val sortedProjects = projects.sortedWith(compareBy<Project> { it.sortOrder }.thenBy { it.name.lowercase() })
        val base = OpenTaskerBundle(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            metadata = BundleMetadata(name = name, description = description),
            projects = sortedProjects,
            tasks = sortedTasks,
            profiles = sortedProfiles,
            variables = sortedVariables,
            scenes = sortedScenes,
            templates = sortedTemplates,
            sort = sort,
            itemMeta = itemMeta,
            groups = groups,
        )
        val plan = validate(base)
        return base.copy(
            metadata = base.metadata.copy(
                capabilityRequirements = capabilityRequirements(sortedTasks),
                warnings = plan.warnings + plan.lossyWarnings,
            )
        )
    }

    fun encode(bundle: OpenTaskerBundle): String = json.encodeToString(bundle)

    @Throws(SerializationException::class, IllegalArgumentException::class)
    fun decode(rawJson: String): OpenTaskerBundle {
        require(rawJson.length <= MAX_BUNDLE_JSON_CHARS) {
            "Bundle JSON exceeds ${MAX_BUNDLE_JSON_CHARS / 1024 / 1024} MB size limit"
        }
        return json.decodeFromString(rawJson)
    }

    private const val MAX_BUNDLE_JSON_CHARS = 16 * 1024 * 1024

    fun validate(bundle: OpenTaskerBundle): BundleImportPlan {
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()

        if (bundle.schemaVersion > OPEN_TASKER_BUNDLE_SCHEMA_VERSION) {
            warnings += "Unsupported schema version ${bundle.schemaVersion}; this build reads up to $OPEN_TASKER_BUNDLE_SCHEMA_VERSION."
        }

        duplicateLongs(bundle.tasks.map { it.id }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate task ids: ${duplicates.joinToString()}."
        }
        duplicateStrings(bundle.variables.map { it.name }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate variable names: ${duplicates.joinToString()}."
        }

        val projectIds = bundle.projects.map { it.id }.toSet()
        (bundle.profiles.mapNotNull { it.projectId } + bundle.tasks.mapNotNull { it.projectId } + bundle.scenes.mapNotNull { it.projectId })
            .filter { it !in projectIds }
            .distinct()
            .forEach { lossyWarnings += "An item references project $it which is not in this bundle; it will become Unfiled." }

        val taskIds = bundle.tasks.map { it.id }.toSet()
        bundle.profiles.forEach { profile ->
            if (profile.enterTaskId !in taskIds) {
                lossyWarnings += "Profile '${profile.name}' references missing enter task ${profile.enterTaskId} and will be skipped."
            }
            val exitTaskId = profile.exitTaskId
            if (exitTaskId != null && exitTaskId !in taskIds) {
                lossyWarnings += "Profile '${profile.name}' references missing exit task $exitTaskId; the exit task will be dropped."
            }
        }

        // A link only truly breaks when the task is NOT in the bundle AND the element carries no task
        // NAME to re-bind against an existing task on import. With a name present it resolves by name, so
        // don't cry wolf (this is the warning that misfired for a scene-only re-import).
        bundle.scenes.forEach { scene ->
            scene.elements.forEach { element ->
                if (element.tapTaskId != null && element.tapTaskId !in taskIds && element.tapTaskName.isBlank()) {
                    lossyWarnings += "Scene '${scene.name}' element ${element.id} references missing tap task ${element.tapTaskId} (no name to re-bind); the link will be dropped."
                }
                if (element.longPressTaskId != null && element.longPressTaskId !in taskIds && element.longPressTaskName.isBlank()) {
                    lossyWarnings += "Scene '${scene.name}' element ${element.id} references missing long-press task ${element.longPressTaskId} (no name to re-bind); the link will be dropped."
                }
            }
        }

        val unsupportedActions = bundle.tasks
            .flatMap { task -> task.actions.map { task.name to it.type } }
            .filter { (_, actionId) -> ActionCapabilityRegistry.get(actionId).level == CapabilityLevel.Unsupported }
        if (unsupportedActions.isNotEmpty()) {
            warnings += "Bundle contains unsupported actions: ${unsupportedActions.joinToString { "${it.first}:${it.second}" }}."
        }

        return BundleImportPlan(
            canImport = warnings.none { warning -> warning.isBlockingImportWarning() },
            warnings = warnings,
            lossyWarnings = lossyWarnings,
        )
    }

    private fun String.isBlockingImportWarning(): Boolean =
        startsWith("Unsupported schema version") ||
            startsWith("Bundle has duplicate task ids") ||
            startsWith("Bundle has duplicate variable names")

    private fun duplicateLongs(values: List<Long>): List<Long> =
        values.groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()

    private fun duplicateStrings(values: List<String>): List<String> =
        values.groupingBy { it }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()

    private fun capabilityRequirements(tasks: List<Task>): List<CapabilityRequirement> =
        tasks
            .flatMap { it.actions }
            .map { it.type }
            .distinct()
            .sorted()
            .map { actionId -> actionId to ActionCapabilityRegistry.get(actionId) }
            .filter { (_, capability) -> capability.level != CapabilityLevel.Supported }
            .map { (actionId, capability) ->
                CapabilityRequirement(
                    actionId = actionId,
                    level = capability.level,
                    reason = capability.reason,
                )
            }
}

class OpenTaskerBundleRepository(private val db: AppDatabase) {
    suspend fun exportBundle(
        appVersion: String,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        name: String = "白い熊 自由作業盤 Export",
        description: String = "",
    ): OpenTaskerBundle {
        // Embed each task's icon bytes so it survives a cross-device import (the path alone is device-local).
        val tasks = db.taskDao().getAll().map { it.toDomain() }
            .map { it.copy(iconData = TaskIconStore.encodeIcon(it.iconPath)) }
        val profiles = db.profileDao().getAll().map { it.toDomain() }
        val variables = db.variableDao().getAll().map { it.toDomain() }
        val taskNameById = db.taskDao().getAll().associate { it.id to it.name }
        val scenes = backfillSceneTaskNames(db.sceneDao().getAll().map { it.toDomain() }, taskNameById)
        val projects = db.projectDao().getAll().map { it.toDomain() }
        val itemMeta = db.itemMetaDao().getAll()
            .filter { it.note.isNotBlank() || it.groupId != null }
            .map { ItemMetaSpec(it.tab, it.itemKey, it.note, it.noteExpanded, it.groupId) }
        val groups = db.itemGroupDao().getAll()
            .map { ItemGroupSpec(it.id, it.tab, it.projectId, it.name, it.note, it.position, it.expanded, it.noteExpanded, it.parentGroupId) }

        return OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            scenes = scenes,
            templates = TemplateStore.state.value,
            projects = projects,
            sort = BundleSortConfig.from(ListSortStore.state.value),
            name = name,
            description = description,
            itemMeta = itemMeta,
            groups = groups,
        )
    }

    /**
     * Export exactly the selected items — nothing pulled in automatically. Referenced tasks that
     * aren't selected become import-time warnings (handled by [OpenTaskerBundleCodec.validate]).
     * The projects the selected items belong to are included so their grouping survives the round
     * trip. Variables are global, so they're included only when [includeVariables] is set.
     */
    suspend fun exportSelection(
        appVersion: String,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        profileIds: Set<Long>,
        taskIds: Set<Long>,
        sceneIds: Set<Long>,
        includeVariables: Boolean,
        name: String,
        description: String = "",
        templateNames: Set<String> = emptySet(),
        variableKeys: Set<String> = emptySet(),
    ): OpenTaskerBundle {
        val profiles = db.profileDao().getAll().map { it.toDomain() }.filter { it.id in profileIds }
        val tasks = db.taskDao().getAll().map { it.toDomain() }.filter { it.id in taskIds }
            .map { it.copy(iconData = TaskIconStore.encodeIcon(it.iconPath)) }
        // Backfill names from ALL tasks (a selected scene may link a task outside the selection).
        val taskNameById = db.taskDao().getAll().associate { it.id to it.name }
        val scenes = backfillSceneTaskNames(
            db.sceneDao().getAll().map { it.toDomain() }.filter { it.id in sceneIds }, taskNameById)
        val allVariables = db.variableDao().getAll().map { it.toDomain() }
        val variables = when {
            includeVariables -> allVariables
            variableKeys.isNotEmpty() -> allVariables.filter { "${it.projectId}:${it.name}" in variableKeys }
            else -> emptyList()
        }
        val templates = TemplateStore.state.value.filter { it.name in templateNames }
        val referencedProjectIds =
            (profiles.mapNotNull { it.projectId } + tasks.mapNotNull { it.projectId } + scenes.mapNotNull { it.projectId }).toSet()
        val projects = db.projectDao().getAll().map { it.toDomain() }.filter { it.id in referencedProjectIds }
        val selectedKeys = (
            tasks.map { "tasks" to it.id.toString() } +
                profiles.map { "profiles" to it.id.toString() } +
                scenes.map { "scenes" to it.id.toString() } +
                templates.map { "widgets" to it.name }
            ).toSet()
        val itemMeta = db.itemMetaDao().getAll()
            .filter { (it.tab to it.itemKey) in selectedKeys && (it.note.isNotBlank() || it.groupId != null) }
            .map { ItemMetaSpec(it.tab, it.itemKey, it.note, it.noteExpanded, it.groupId) }
        val usedGroupIds = itemMeta.mapNotNull { it.groupId }.toSet()
        val groups = db.itemGroupDao().getAll()
            .filter { it.id in usedGroupIds }
            .map { ItemGroupSpec(it.id, it.tab, it.projectId, it.name, it.note, it.position, it.expanded, it.noteExpanded, it.parentGroupId) }

        return OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            scenes = scenes,
            templates = templates,
            projects = projects,
            sort = BundleSortConfig.from(ListSortStore.state.value),
            name = name,
            description = description,
            itemMeta = itemMeta,
            groups = groups,
        )
    }

    suspend fun importBundle(
        bundle: OpenTaskerBundle,
        projectConflictStrategy: ProjectConflictStrategy = ProjectConflictStrategy.MERGE,
        // Default: overwrite a same-name item IN PLACE (reuse its row id, so groups/notes/links survive).
        itemConflictStrategy: ItemConflictStrategy = ItemConflictStrategy.OVERWRITE_DELETE,
    ): BundleImportReport {
        val plan = OpenTaskerBundleCodec.validate(bundle)
        require(plan.canImport) { plan.warnings.joinToString() }

        // Suffix for OVERWRITE_BACKUP — the existing same-name item is renamed "<name>.<stamp>.bak".
        val backupStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        fun backupName(name: String) = "$name.$backupStamp.bak"

        var insertedTasks = 0
        var insertedProfiles = 0
        var insertedVariables = 0
        var insertedScenes = 0
        var insertedTemplates = 0
        var insertedProjects = 0
        val importWarnings = plan.warnings.toMutableList()
        val lossyWarnings = plan.lossyWarnings.toMutableList()
        // Resolved project ids the imported tasks/profiles/scenes landed in (null = Unfiled).
        val targetProjectIds = mutableSetOf<Long?>()

        db.withTransaction {
            // Resolve each bundle project against existing names per the chosen strategy: MERGE
            // reuses the existing project; RENAME inserts a separate, uniquely-named one.
            val existingByName = db.projectDao().getAll().associateTo(mutableMapOf()) { it.name.lowercase() to it.id }
            val takenNames = existingByName.keys.toMutableSet()
            val projectIdMap = mutableMapOf<Long, Long>()
            bundle.projects.sortedWith(compareBy<Project> { it.sortOrder }.thenBy { it.name.lowercase() }).forEach { project ->
                val existingId = existingByName[project.name.lowercase()]
                if (existingId != null && projectConflictStrategy == ProjectConflictStrategy.MERGE) {
                    projectIdMap[project.id] = existingId
                } else {
                    val newName = uniqueName(project.name, takenNames)
                    val newId = db.projectDao().insert(project.copy(id = 0, name = newName).toEntity())
                    projectIdMap[project.id] = newId
                    takenNames += newName.lowercase()
                    existingByName[newName.lowercase()] = newId
                    insertedProjects++
                }
            }
            fun remapProjectId(projectId: Long?): Long? = projectId?.let { projectIdMap[it] }

            // Resolve incoming-vs-existing name clashes per [itemConflictStrategy] before inserting:
            // OVERWRITE_DELETE updates the existing same-name task IN PLACE (keeps its db id, so a
            // profile's enterTaskId / a scene's tapTaskId that points at it stays linked — a task-only
            // re-import no longer strands them as "Missing task"); OVERWRITE_BACKUP renames the existing
            // to ".bak" (freeing the name); RENAME leaves it (the incoming gets uniquified).
            val incomingTaskNames = bundle.tasks.mapTo(mutableSetOf()) { it.name.lowercase() }
            val reusableTaskIds = mutableMapOf<String, Long>()
            db.taskDao().getAll().filter { it.name.lowercase() in incomingTaskNames }
                .groupBy { it.name.lowercase() }
                .forEach { (name, existing) ->
                    when (itemConflictStrategy) {
                        ItemConflictStrategy.OVERWRITE_DELETE -> {
                            reusableTaskIds[name] = existing.first().id            // keep one row's id to overwrite in place
                            existing.drop(1).forEach { db.taskDao().delete(it) }  // collapse any same-name duplicates
                        }
                        ItemConflictStrategy.OVERWRITE_BACKUP -> existing.forEach { db.taskDao().update(it.copy(name = backupName(it.name))) }
                        ItemConflictStrategy.RENAME -> Unit
                    }
                }

            val takenTaskNames = db.taskDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            val taskIdMap = mutableMapOf<Long, Long>()
            val profileIdMap = mutableMapOf<Long, Long>()
            val sceneIdMap = mutableMapOf<Long, Long>()
            bundle.tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id }).forEach { task ->
                val pid = remapProjectId(task.projectId)
                // Resolve the icon: reuse the local file if it still exists (same-device re-import), else
                // materialize the embedded bytes (cross-device), else none.
                val iconPath = when {
                    !task.iconPath.isNullOrBlank() && File(task.iconPath).exists() -> task.iconPath
                    task.iconData != null -> TaskIconStore.materializeIcon(task.iconData)
                    else -> null
                }
                val withIcon = task.copy(iconPath = iconPath)
                val reuseId = reusableTaskIds.remove(task.name.lowercase())
                val resolvedId = if (reuseId != null) {
                    val oldIcon = db.taskDao().getById(reuseId)?.iconPath
                    if (oldIcon != null && oldIcon != iconPath) TaskIconStore.delete(oldIcon)  // don't leak the replaced icon
                    db.taskDao().update(withIcon.copy(id = reuseId, projectId = pid).toEntity())  // in-place: same id, new actions
                    reuseId
                } else {
                    val newName = uniqueName(task.name, takenTaskNames)
                    val newId = db.taskDao().insert(withIcon.copy(id = 0, name = newName, projectId = pid).toEntity())
                    takenTaskNames += newName.lowercase()
                    newId
                }
                taskIdMap[task.id] = resolvedId
                targetProjectIds += pid
                insertedTasks++
            }

            bundle.variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name }).forEach { variable ->
                // Super-globals (projectId 0) stay super; project-globals remap to the resolved project
                // (or fall back to super if the bundle didn't carry that project). insert REPLACEs.
                val pid = if (variable.projectId == 0L) 0L else (projectIdMap[variable.projectId] ?: 0L)
                db.variableDao().insert(VariableEntity(pid, variable.name, variable.value))
                insertedVariables++
            }

            // Profiles overwrite IN PLACE on OVERWRITE_DELETE (reuse the existing row id → the profile keeps
            // its group/note and a stable id); BACKUP renames the old one aside; RENAME leaves it.
            val incomingProfileNames = bundle.profiles.mapTo(mutableSetOf()) { it.name.lowercase() }
            val reusableProfileIds = mutableMapOf<String, Long>()
            val reusedProfileEnabled = mutableMapOf<String, Boolean>()
            db.profileDao().getAll().filter { it.name.lowercase() in incomingProfileNames }
                .groupBy { it.name.lowercase() }
                .forEach { (name, existing) ->
                    when (itemConflictStrategy) {
                        ItemConflictStrategy.OVERWRITE_DELETE -> {
                            reusableProfileIds[name] = existing.first().id
                            reusedProfileEnabled[name] = existing.first().enabled
                            existing.drop(1).forEach { db.profileDao().delete(it) }
                        }
                        ItemConflictStrategy.OVERWRITE_BACKUP -> existing.forEach { db.profileDao().update(it.copy(name = backupName(it.name))) }
                        ItemConflictStrategy.RENAME -> Unit
                    }
                }
            val takenProfileNames = db.profileDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            bundle.profiles.sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id }).forEach { profile ->
                val enterTaskId = taskIdMap[profile.enterTaskId]
                if (enterTaskId == null) {
                    lossyWarnings += "Skipped profile '${profile.name}' because enter task ${profile.enterTaskId} was not imported."
                    return@forEach
                }
                val reuseId = reusableProfileIds.remove(profile.name.lowercase())
                val newName = if (reuseId != null) profile.name else uniqueName(profile.name, takenProfileNames)
                val pid = remapProjectId(profile.projectId)
                val remappedExitId = profile.exitTaskId?.let { taskIdMap[it] }
                // Overwriting in place keeps the existing on/off state; a brand-new profile imports disabled.
                val enabled = if (reuseId != null) reusedProfileEnabled[profile.name.lowercase()] ?: false else false
                // Bind enter/exit task by NAME too, so a later task-only re-import (which re-ids the task)
                // resolves by name instead of orphaning this profile.
                val remappedProfile = profile.copy(
                    id = reuseId ?: 0,
                    name = newName,
                    enabled = enabled,
                    enterTaskId = enterTaskId,
                    enterTaskName = db.taskDao().getById(enterTaskId)?.name ?: profile.enterTaskName,
                    exitTaskId = remappedExitId,
                    exitTaskName = remappedExitId?.let { db.taskDao().getById(it)?.name } ?: "",
                    projectId = pid,
                )
                profileIdMap[profile.id] = if (reuseId != null) {
                    db.profileDao().update(remappedProfile.toEntity()); reuseId
                } else {
                    db.profileDao().insert(remappedProfile.toEntity())
                }
                takenProfileNames += newName.lowercase()
                targetProjectIds += pid
                insertedProfiles++
            }

            // Scenes overwrite IN PLACE on OVERWRITE_DELETE (reuse the row id → the scene keeps its group/
            // note and a stable id); BACKUP renames the old one aside; RENAME leaves it.
            val incomingSceneNames = bundle.scenes.mapTo(mutableSetOf()) { it.name.lowercase() }
            val reusableSceneIds = mutableMapOf<String, Long>()
            db.sceneDao().getAll().filter { it.name.lowercase() in incomingSceneNames }
                .groupBy { it.name.lowercase() }
                .forEach { (name, existing) ->
                    when (itemConflictStrategy) {
                        ItemConflictStrategy.OVERWRITE_DELETE -> {
                            reusableSceneIds[name] = existing.first().id
                            existing.drop(1).forEach { db.sceneDao().delete(it) }
                        }
                        ItemConflictStrategy.OVERWRITE_BACKUP -> existing.forEach { db.sceneDao().update(it.copy(name = backupName(it.name))) }
                        ItemConflictStrategy.RENAME -> Unit
                    }
                }
            // Snapshot device tasks (post-import) so element task links re-bind by NAME (id is a fallback).
            val devTasksForScenes = db.taskDao().getAll()
            val taskNameToId = devTasksForScenes.associate { it.name.lowercase() to it.id }
            val taskIdToName = devTasksForScenes.associate { it.id to it.name }
            val takenSceneNames = db.sceneDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            bundle.scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id }).forEach { scene ->
                val remappedElements = scene.elements.map { element ->
                    remapSceneElement(element, taskIdMap, taskNameToId, taskIdToName)
                }
                val reuseId = reusableSceneIds.remove(scene.name.lowercase())
                val newName = if (reuseId != null) scene.name else uniqueName(scene.name, takenSceneNames)
                val pid = remapProjectId(scene.projectId)
                val remapped = scene.copy(id = reuseId ?: 0, name = newName, elements = remappedElements, projectId = pid)
                sceneIdMap[scene.id] = if (reuseId != null) {
                    db.sceneDao().update(remapped.toEntity()); reuseId
                } else {
                    db.sceneDao().insert(remapped.toEntity())
                }
                takenSceneNames += newName.lowercase()
                targetProjectIds += pid
                insertedScenes++
            }

            // Foldable groups: MERGE by (tab, project, name) — re-importing over a project updates the
            // existing groups in place instead of doubling them (preserving each group's fold state). A
            // second pass resolves nested parents (a parent may be defined after its child in the bundle).
            val existingGroups = db.itemGroupDao().getAll()
            val groupIdMap = mutableMapOf<Long, Long>()
            bundle.groups.forEach { g ->
                val pid = remapProjectId(g.projectId)
                val matches = existingGroups.filter { it.tab == g.tab && it.projectId == pid && it.name == g.name }
                // Collapse any prior-import duplicates: keep the first, drop the rest.
                matches.drop(1).forEach { dup ->
                    db.itemMetaDao().clearGroup(dup.tab, dup.id)
                    db.itemGroupDao().orphanChildren(dup.id)
                    db.itemGroupDao().delete(dup.id)
                }
                val keep = matches.firstOrNull()
                groupIdMap[g.id] = if (keep != null) {
                    db.itemGroupDao().upsert(keep.copy(position = g.position, note = g.note))
                    keep.id
                } else {
                    db.itemGroupDao().upsert(
                        ItemGroupEntity(
                            projectId = pid, tab = g.tab, name = g.name,
                            note = g.note, position = g.position, expanded = g.expanded, noteExpanded = g.noteExpanded,
                        )
                    )
                }
            }
            bundle.groups.forEach { g ->
                val newId = groupIdMap[g.id] ?: return@forEach
                val parentNew = g.parentGroupId?.let { groupIdMap[it] }
                db.itemGroupDao().getById(newId)?.let { db.itemGroupDao().upsert(it.copy(parentGroupId = parentNew)) }
            }

            // Per-item notes + group membership for DB entities: remap the numeric key to the item's new id
            // (by tab) and the groupId to the imported group. Widget notes are name-keyed, applied below.
            bundle.itemMeta.forEach { m ->
                val newKey = when (m.tab) {
                    "tasks" -> m.itemKey.toLongOrNull()?.let { taskIdMap[it]?.toString() }
                    "profiles" -> m.itemKey.toLongOrNull()?.let { profileIdMap[it]?.toString() }
                    "scenes" -> m.itemKey.toLongOrNull()?.let { sceneIdMap[it]?.toString() }
                    else -> null
                } ?: return@forEach
                db.itemMetaDao().upsert(
                    ItemMetaEntity(
                        tab = m.tab, itemKey = newKey, note = m.note, noteExpanded = m.noteExpanded,
                        groupId = m.groupId?.let { groupIdMap[it] },
                    )
                )
            }
        }

        // Widget templates live in SharedPreferences (TemplateStore), not the DB — apply after the
        // transaction, honouring the same item-conflict strategy as the DB entities.
        val takenTemplateNames = TemplateStore.names().mapTo(mutableSetOf()) { it.lowercase() }
        val templateNameMap = mutableMapOf<String, String>()
        bundle.templates.forEach { tpl ->
            val collides = tpl.name.lowercase() in takenTemplateNames
            if (collides && itemConflictStrategy == ItemConflictStrategy.OVERWRITE_BACKUP) {
                TemplateStore.get(tpl.name)?.let { TemplateStore.put(backupName(tpl.name), it) }
                takenTemplateNames += backupName(tpl.name).lowercase()
            }
            val targetName = if (collides && itemConflictStrategy == ItemConflictStrategy.RENAME) {
                uniqueName(tpl.name, takenTemplateNames)
            } else {
                tpl.name // OVERWRITE_DELETE / OVERWRITE_BACKUP / no clash → original name (put replaces)
            }
            TemplateStore.put(targetName, tpl.layout)
            templateNameMap[tpl.name] = targetName
            takenTemplateNames += targetName.lowercase()
            insertedTemplates++
        }

        // Widget notes: templates are name-keyed, so map the bundle's name to the imported name and store.
        bundle.itemMeta.filter { it.tab == "widgets" }.forEach { m ->
            val newName = templateNameMap[m.itemKey] ?: return@forEach
            db.itemMetaDao().upsert(
                ItemMetaEntity(tab = "widgets", itemKey = newName, note = m.note, noteExpanded = m.noteExpanded)
            )
        }

        // Restore the per-category sort method the bundle was exported with (v3+; older bundles
        // default to Alphabetical).
        ListSortStore.setAll(bundle.sort.toPrefs())

        // Resolve the target project ids to names for the import-result dialog.
        val projectNamesById = db.projectDao().getAll().associate { it.id to it.name }
        val projectNames = targetProjectIds
            .map { id -> if (id == null) "Unfiled" else (projectNamesById[id] ?: "Unfiled") }
            .distinct()

        return BundleImportReport(
            insertedTasks = insertedTasks,
            insertedProfiles = insertedProfiles,
            insertedVariables = insertedVariables,
            insertedScenes = insertedScenes,
            insertedTemplates = insertedTemplates,
            insertedProjects = insertedProjects,
            projectNames = projectNames,
            warnings = importWarnings,
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    // Config keys whose value targets a task (gesture handlers) — these live in the free-form config map
    // (the value is a task NAME going forward, or a legacy id string for older scenes).
    private val taskIdConfigKeys = setOf(
        "swipeUp", "swipeDown", "swipeLeft", "swipeRight",
        "longSwipeUp", "longSwipeDown", "longSwipeLeft", "longSwipeRight", "doubleTap", "moveDebug",
    )

    /**
     * Re-link a scene element's task references on import. Resolves NAME-first (survives a re-id and a
     * task that isn't in the bundle — it re-binds to an existing same-name task), then the bundle id map,
     * then the raw id if it still exists. Gesture-config values are rewritten to the resolved task NAME so
     * they too become id-independent. [taskNameToId]/[taskIdToName] are the post-import device task tables.
     */
    private fun remapSceneElement(
        element: SceneElement,
        taskIdMap: Map<Long, Long>,
        taskNameToId: Map<String, Long>,
        taskIdToName: Map<Long, String>,
    ): SceneElement {
        fun resolve(name: String, id: Long?): Pair<Long?, String> {
            if (name.isNotBlank()) taskNameToId[name.lowercase()]?.let { return it to (taskIdToName[it] ?: name) }
            if (id != null) {
                val mapped = taskIdMap[id] ?: id.takeIf { taskIdToName.containsKey(it) }
                if (mapped != null) return mapped to (taskIdToName[mapped] ?: "")
            }
            return null to name   // unresolved: keep the name so a later import can still re-bind it
        }
        val (tapId, tapName) = resolve(element.tapTaskName, element.tapTaskId)
        val (lpId, lpName) = resolve(element.longPressTaskName, element.longPressTaskId)
        val newConfig = element.config.mapValues { (key, value) ->
            if (key in taskIdConfigKeys && value.isNotBlank()) {
                val asId = value.toLongOrNull()
                val (_, nm) = resolve(if (asId == null) value else "", asId)
                nm.ifBlank { value }   // store the resolved NAME (id-independent); keep the original if unresolved
            } else value
        }
        return element.copy(
            tapTaskId = tapId, tapTaskName = tapName,
            longPressTaskId = lpId, longPressTaskName = lpName,
            config = newConfig,
        )
    }

    /**
     * Fill each scene element's task NAME from its id on EXPORT, so the bundle re-binds by name on import
     * (even if the linked task isn't included, or gets re-id'd). Also rewrites legacy gesture-config id
     * values to the task name. Existing names are left as-is. [taskNameById] = current device id→name.
     */
    private fun backfillSceneTaskNames(scenes: List<Scene>, taskNameById: Map<Long, String>): List<Scene> =
        scenes.map { scene ->
            scene.copy(elements = scene.elements.map { el ->
                el.copy(
                    tapTaskName = el.tapTaskName.ifBlank { el.tapTaskId?.let { taskNameById[it] } ?: "" },
                    longPressTaskName = el.longPressTaskName.ifBlank { el.longPressTaskId?.let { taskNameById[it] } ?: "" },
                    config = el.config.mapValues { (k, v) ->
                        if (k in taskIdConfigKeys) v.toLongOrNull()?.let { taskNameById[it] } ?: v else v
                    },
                )
            })
        }

    /** Returns [base], or "[base] (2)", "(3)", … so it doesn't collide with [takenLowercase]. */
    private fun uniqueName(base: String, takenLowercase: Set<String>): String {
        if (base.lowercase() !in takenLowercase) return base
        var suffix = 2
        while ("$base ($suffix)".lowercase() in takenLowercase) suffix++
        return "$base ($suffix)"
    }
}
