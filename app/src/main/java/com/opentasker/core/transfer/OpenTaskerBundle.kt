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
import com.opentasker.core.storage.toEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// v2 adds the `projects` array and a projectId on profiles/tasks/scenes. v1 bundles still import
// (their missing fields default), so the version is a floor on what THIS build can read, not a gate.
const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 2

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
)

@Serializable
data class BundleMetadata(
    val name: String = "OpenTasker Export",
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
    val insertedProjects: Int = 0,
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
        projects: List<Project> = emptyList(),
        name: String = "OpenTasker Export",
        description: String = "",
    ): OpenTaskerBundle {
        val sortedTasks = tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id })
        val sortedProfiles = profiles.sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id })
        val sortedVariables = variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name })
        val sortedScenes = scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id })
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
        name: String = "OpenTasker Export",
        description: String = "",
    ): OpenTaskerBundle {
        val tasks = db.taskDao().getAll().map { it.toDomain() }
        val profiles = db.profileDao().getAll().map { it.toDomain() }
        val variables = db.variableDao().getAll().map { it.toDomain() }
        val scenes = db.sceneDao().getAll().map { it.toDomain() }
        val projects = db.projectDao().getAll().map { it.toDomain() }

        return OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            scenes = scenes,
            projects = projects,
            name = name,
            description = description,
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
    ): OpenTaskerBundle {
        val profiles = db.profileDao().getAll().map { it.toDomain() }.filter { it.id in profileIds }
        val tasks = db.taskDao().getAll().map { it.toDomain() }.filter { it.id in taskIds }
        val scenes = db.sceneDao().getAll().map { it.toDomain() }.filter { it.id in sceneIds }
        val variables = if (includeVariables) db.variableDao().getAll().map { it.toDomain() } else emptyList()
        val referencedProjectIds =
            (profiles.mapNotNull { it.projectId } + tasks.mapNotNull { it.projectId } + scenes.mapNotNull { it.projectId }).toSet()
        val projects = db.projectDao().getAll().map { it.toDomain() }.filter { it.id in referencedProjectIds }

        return OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variables,
            scenes = scenes,
            projects = projects,
            name = name,
            description = description,
        )
    }

    suspend fun importBundle(
        bundle: OpenTaskerBundle,
        projectConflictStrategy: ProjectConflictStrategy = ProjectConflictStrategy.MERGE,
    ): BundleImportReport {
        val plan = OpenTaskerBundleCodec.validate(bundle)
        require(plan.canImport) { plan.warnings.joinToString() }

        var insertedTasks = 0
        var insertedProfiles = 0
        var insertedVariables = 0
        var insertedScenes = 0
        var insertedProjects = 0
        val importWarnings = plan.warnings.toMutableList()
        val lossyWarnings = plan.lossyWarnings.toMutableList()

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

            val takenTaskNames = db.taskDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            val taskIdMap = mutableMapOf<Long, Long>()
            bundle.tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id }).forEach { task ->
                val newName = uniqueName(task.name, takenTaskNames)
                val newId = db.taskDao().insert(task.copy(id = 0, name = newName, projectId = remapProjectId(task.projectId)).toEntity())
                takenTaskNames += newName.lowercase()
                taskIdMap[task.id] = newId
                insertedTasks++
            }

            bundle.variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name }).forEach { variable ->
                val existing = db.variableDao().get(variable.name)
                if (existing == null) {
                    db.variableDao().insert(variable.toEntity())
                } else {
                    db.variableDao().update(variable.toEntity())
                }
                insertedVariables++
            }

            val takenProfileNames = db.profileDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            bundle.profiles.sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id }).forEach { profile ->
                val enterTaskId = taskIdMap[profile.enterTaskId]
                if (enterTaskId == null) {
                    lossyWarnings += "Skipped profile '${profile.name}' because enter task ${profile.enterTaskId} was not imported."
                    return@forEach
                }
                val newName = uniqueName(profile.name, takenProfileNames)
                val remappedProfile = profile.copy(
                    id = 0,
                    name = newName,
                    enabled = false,
                    enterTaskId = enterTaskId,
                    exitTaskId = profile.exitTaskId?.let { taskIdMap[it] },
                    projectId = remapProjectId(profile.projectId),
                )
                db.profileDao().insert(remappedProfile.toEntity())
                takenProfileNames += newName.lowercase()
                insertedProfiles++
            }

            val takenSceneNames = db.sceneDao().getAll().mapTo(mutableSetOf()) { it.name.lowercase() }
            bundle.scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id }).forEach { scene ->
                val remappedElements = scene.elements.map { element ->
                    remapSceneElement(element, taskIdMap)
                }
                val newName = uniqueName(scene.name, takenSceneNames)
                db.sceneDao().insert(scene.copy(id = 0, name = newName, elements = remappedElements, projectId = remapProjectId(scene.projectId)).toEntity())
                takenSceneNames += newName.lowercase()
                insertedScenes++
            }
        }

        return BundleImportReport(
            insertedTasks = insertedTasks,
            insertedProfiles = insertedProfiles,
            insertedVariables = insertedVariables,
            insertedScenes = insertedScenes,
            insertedProjects = insertedProjects,
            warnings = importWarnings,
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    private fun remapSceneElement(element: SceneElement, taskIdMap: Map<Long, Long>): SceneElement =
        element.copy(
            tapTaskId = element.tapTaskId?.let { taskIdMap[it] },
            longPressTaskId = element.longPressTaskId?.let { taskIdMap[it] },
        )

    /** Returns [base], or "[base] (2)", "(3)", … so it doesn't collide with [takenLowercase]. */
    private fun uniqueName(base: String, takenLowercase: Set<String>): String {
        if (base.lowercase() !in takenLowercase) return base
        var suffix = 2
        while ("$base ($suffix)".lowercase() in takenLowercase) suffix++
        return "$base ($suffix)"
    }
}
