package com.opentasker.core.transfer

import androidx.room.withTransaction
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
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

// v4 adds `templates` (widget-layout templates, previously a separate processor). v3 added per-item
// `position` + per-category `sort`; v2 added `projects` + projectId. Older bundles still import (missing
// fields default), so the version is a floor on what THIS build can read, not a gate.
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
) {
    fun toPrefs(): SortPrefs = SortPrefs(profiles = profiles, tasks = tasks, scenes = scenes)

    companion object {
        fun from(prefs: SortPrefs) = BundleSortConfig(prefs.profiles, prefs.tasks, prefs.scenes)
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

    @Throws(SerializationException::class)
    fun decode(rawJson: String): OpenTaskerBundle = json.decodeFromString(rawJson)

    fun validate(bundle: OpenTaskerBundle): BundleImportPlan {
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()

        if (bundle.schemaVersion > OPEN_TASKER_BUNDLE_SCHEMA_VERSION) {
            warnings += "Unsupported schema version ${bundle.schemaVersion}; this build reads up to $OPEN_TASKER_BUNDLE_SCHEMA_VERSION."
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

        bundle.scenes.forEach { scene ->
            scene.elements.forEach { element ->
                if (element.tapTaskId != null && element.tapTaskId !in taskIds) {
                    lossyWarnings += "Scene '${scene.name}' element ${element.id} references missing tap task ${element.tapTaskId}; the link will be dropped."
                }
                if (element.longPressTaskId != null && element.longPressTaskId !in taskIds) {
                    lossyWarnings += "Scene '${scene.name}' element ${element.id} references missing long-press task ${element.longPressTaskId}; the link will be dropped."
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
            canImport = warnings.none { it.startsWith("Unsupported schema version") },
            warnings = warnings,
            lossyWarnings = lossyWarnings,
        )
    }

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
        val tasks = db.taskDao().getAll().map { it.toDomain() }
        val profiles = db.profileDao().getAll().map { it.toDomain() }
        val variables = db.variableDao().getAll().map { it.toDomain() }
        val scenes = db.sceneDao().getAll().map { it.toDomain() }
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
        val scenes = db.sceneDao().getAll().map { it.toDomain() }.filter { it.id in sceneIds }
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
        itemConflictStrategy: ItemConflictStrategy = ItemConflictStrategy.RENAME,
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
            // OVERWRITE_DELETE removes the existing same-name rows; OVERWRITE_BACKUP renames them to
            // ".bak" (freeing the original name); RENAME leaves them (the incoming gets uniquified).
            val incomingTaskNames = bundle.tasks.mapTo(mutableSetOf()) { it.name.lowercase() }
            db.taskDao().getAll().filter { it.name.lowercase() in incomingTaskNames }.forEach { existing ->
                when (itemConflictStrategy) {
                    ItemConflictStrategy.OVERWRITE_DELETE -> db.taskDao().delete(existing)
                    ItemConflictStrategy.OVERWRITE_BACKUP -> db.taskDao().update(existing.copy(name = backupName(existing.name)))
                    ItemConflictStrategy.RENAME -> Unit
                }
            }

            val takenTaskNames = db.taskDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            val taskIdMap = mutableMapOf<Long, Long>()
            val profileIdMap = mutableMapOf<Long, Long>()
            val sceneIdMap = mutableMapOf<Long, Long>()
            bundle.tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id }).forEach { task ->
                val newName = uniqueName(task.name, takenTaskNames)
                val pid = remapProjectId(task.projectId)
                val newId = db.taskDao().insert(task.copy(id = 0, name = newName, projectId = pid).toEntity())
                takenTaskNames += newName.lowercase()
                taskIdMap[task.id] = newId
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

            val incomingProfileNames = bundle.profiles.mapTo(mutableSetOf()) { it.name.lowercase() }
            db.profileDao().getAll().filter { it.name.lowercase() in incomingProfileNames }.forEach { existing ->
                when (itemConflictStrategy) {
                    ItemConflictStrategy.OVERWRITE_DELETE -> db.profileDao().delete(existing)
                    ItemConflictStrategy.OVERWRITE_BACKUP -> db.profileDao().update(existing.copy(name = backupName(existing.name)))
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
                val newName = uniqueName(profile.name, takenProfileNames)
                val pid = remapProjectId(profile.projectId)
                val remappedProfile = profile.copy(
                    id = 0,
                    name = newName,
                    enabled = false,
                    enterTaskId = enterTaskId,
                    exitTaskId = profile.exitTaskId?.let { taskIdMap[it] },
                    projectId = pid,
                )
                profileIdMap[profile.id] = db.profileDao().insert(remappedProfile.toEntity())
                takenProfileNames += newName.lowercase()
                targetProjectIds += pid
                insertedProfiles++
            }

            val incomingSceneNames = bundle.scenes.mapTo(mutableSetOf()) { it.name.lowercase() }
            db.sceneDao().getAll().filter { it.name.lowercase() in incomingSceneNames }.forEach { existing ->
                when (itemConflictStrategy) {
                    ItemConflictStrategy.OVERWRITE_DELETE -> db.sceneDao().delete(existing)
                    ItemConflictStrategy.OVERWRITE_BACKUP -> db.sceneDao().update(existing.copy(name = backupName(existing.name)))
                    ItemConflictStrategy.RENAME -> Unit
                }
            }
            val takenSceneNames = db.sceneDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            bundle.scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id }).forEach { scene ->
                val remappedElements = scene.elements.map { element ->
                    remapSceneElement(element, taskIdMap)
                }
                val newName = uniqueName(scene.name, takenSceneNames)
                val pid = remapProjectId(scene.projectId)
                sceneIdMap[scene.id] = db.sceneDao().insert(scene.copy(id = 0, name = newName, elements = remappedElements, projectId = pid).toEntity())
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

    // Config keys whose value is a task id (gesture handlers), so they must be remapped on import too —
    // unlike the tapTaskId/longPressTaskId fields, these live in the free-form config map.
    private val taskIdConfigKeys = setOf(
        "swipeUp", "swipeDown", "swipeLeft", "swipeRight",
        "longSwipeUp", "longSwipeDown", "longSwipeLeft", "longSwipeRight", "doubleTap", "moveDebug",
    )

    private fun remapSceneElement(element: SceneElement, taskIdMap: Map<Long, Long>): SceneElement =
        element.copy(
            tapTaskId = element.tapTaskId?.let { taskIdMap[it] },
            longPressTaskId = element.longPressTaskId?.let { taskIdMap[it] },
            config = element.config.mapValues { (key, value) ->
                if (key in taskIdConfigKeys) value.toLongOrNull()?.let { taskIdMap[it]?.toString() } ?: value else value
            },
        )

    /** Returns [base], or "[base] (2)", "(3)", … so it doesn't collide with [takenLowercase]. */
    private fun uniqueName(base: String, takenLowercase: Set<String>): String {
        if (base.lowercase() !in takenLowercase) return base
        var suffix = 2
        while ("$base ($suffix)".lowercase() in takenLowercase) suffix++
        return "$base ($suffix)"
    }
}
