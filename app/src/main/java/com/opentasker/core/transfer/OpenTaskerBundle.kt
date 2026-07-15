package com.opentasker.core.transfer

import androidx.room.withTransaction
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.AutomationPower
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.model.VariableNamePolicy
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 2
private val SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS = 1..OPEN_TASKER_BUNDLE_SCHEMA_VERSION

@Serializable
data class OpenTaskerBundle(
    val schemaVersion: Int = OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMs: Long,
    val metadata: BundleMetadata = BundleMetadata(),
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
    val powerRequests: List<RecipePowerRequest> = emptyList(),
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CapabilityRequirement(
    val actionId: String,
    val level: CapabilityLevel,
    val reason: String,
)

@Serializable
data class RecipePowerRequest(
    val taskId: Long,
    val taskName: String,
    val profileNames: List<String> = emptyList(),
    val powers: List<AutomationPower> = emptyList(),
    val actionIds: List<String> = emptyList(),
    val dataToExternalChains: List<DataToExternalChainRequest> = emptyList(),
    val unknownActionIds: List<String> = emptyList(),
)

@Serializable
data class DataToExternalChainRequest(
    val sourceActionId: String,
    val sinkActionId: String,
)

data class BundleImportPlan(
    val canImport: Boolean,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
    val capabilityRequirements: List<CapabilityRequirement> = emptyList(),
    val powerRequests: List<RecipePowerRequest> = emptyList(),
)

data class BundleImportReport(
    val insertedTasks: Int,
    val insertedProfiles: Int,
    val insertedVariables: Int,
    val insertedScenes: Int,
    val warnings: List<String> = emptyList(),
    val lossyWarnings: List<String> = emptyList(),
)

object OpenTaskerBundleCodec {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        // Be forgiving of hand-edited/shared bundles on decode (export output is unaffected):
        // allow // comments, trailing commas, and case-insensitive enum values. Unknown keys are
        // still rejected so structurally wrong bundles fail.
        allowComments = true
        allowTrailingComma = true
        decodeEnumsCaseInsensitive = true
    }

    fun build(
        appVersion: String,
        exportedAtEpochMs: Long,
        profiles: List<Profile>,
        tasks: List<Task>,
        variables: List<Variable> = emptyList(),
        scenes: List<Scene> = emptyList(),
        omittedSecretVariableCount: Int = 0,
        name: String = "OpenTasker Export",
        description: String = "",
    ): OpenTaskerBundle {
        val sortedTasks = tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id })
        val sortedProfiles = profiles.sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id })
        val omittedSecretCount = omittedSecretVariableCount + variables.count { it.isSecret }
        val sortedVariables = variables
            .filterNot { it.isSecret }
            .sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name })
        val sortedScenes = scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id })
        val capabilityRequirements = capabilityRequirements(sortedTasks)
        val powerRequests = powerRequests(sortedTasks, sortedProfiles)
        val base = OpenTaskerBundle(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            metadata = BundleMetadata(
                name = name,
                description = description,
                warnings = if (omittedSecretCount > 0) {
                    listOf("$omittedSecretCount secret variable(s) were omitted and must be re-entered after import.")
                } else {
                    emptyList()
                },
                capabilityRequirements = capabilityRequirements,
                powerRequests = powerRequests,
            ),
            tasks = sortedTasks,
            profiles = sortedProfiles,
            variables = sortedVariables,
            scenes = sortedScenes,
        )
        val plan = validate(base)
        return base.copy(
            metadata = base.metadata.copy(
                capabilityRequirements = plan.capabilityRequirements,
                powerRequests = plan.powerRequests,
                warnings = base.metadata.warnings + plan.warnings + plan.lossyWarnings,
            )
        )
    }

    fun encode(bundle: OpenTaskerBundle): String {
        require(bundle.variables.none { it.isSecret }) {
            "Secret variable values cannot be written to an ordinary OpenTasker bundle."
        }
        return json.encodeToString(bundle)
    }

    @Throws(SerializationException::class, IllegalArgumentException::class)
    fun decode(rawJson: String): OpenTaskerBundle = decode(rawJson, ImportResourceBudget.Default)

    internal fun decode(rawJson: String, budget: ImportResourceBudget): OpenTaskerBundle {
        ImportResourceGuard.requireJsonPreflight(rawJson, budget)
        return json.decodeFromString<OpenTaskerBundle>(rawJson).also { bundle ->
            ImportResourceGuard.requireBundle(bundle, budget)
        }
    }

    fun validate(bundle: OpenTaskerBundle): BundleImportPlan = validate(bundle, ImportResourceBudget.Default)

    internal fun validate(bundle: OpenTaskerBundle, budget: ImportResourceBudget): BundleImportPlan {
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()

        ImportResourceGuard.bundleViolation(bundle, budget)?.let { violation ->
            return BundleImportPlan(
                canImport = false,
                warnings = listOf(violation.message.orEmpty()),
            )
        }

        if (bundle.schemaVersion !in SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS) {
            warnings += "Unsupported schema version ${bundle.schemaVersion}; supported versions are " +
                "${SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS.first}..${SUPPORTED_OPEN_TASKER_BUNDLE_SCHEMAS.last}."
        }

        duplicateLongs(bundle.tasks.map { it.id }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate task ids: ${duplicates.joinToString()}."
        }
        duplicateStrings(bundle.variables.map { it.name }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate variable names: ${duplicates.joinToString()}."
        }

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

        val taskPowerRequests = powerRequests(bundle.tasks, bundle.profiles)
        val computedCapabilityRequirements = capabilityRequirements(bundle.tasks)
        val unknownActions = taskPowerRequests.flatMap { it.unknownActionIds }.distinct().sorted()
        if (unknownActions.isNotEmpty()) {
            warnings += "Bundle contains unknown unclassified actions: ${unknownActions.joinToString()}."
        }
        taskPowerRequests
            .filter { it.dataToExternalChains.isNotEmpty() }
            .forEach { request ->
                val profiles = request.profileNames.takeIf(List<String>::isNotEmpty)
                    ?.joinToString(prefix = " (profiles: ", postfix = ")")
                    .orEmpty()
                warnings += "Potential data-to-external chain in task '${request.taskName}'$profiles: " +
                    request.dataToExternalChains.joinToString { "${it.sourceActionId} -> ${it.sinkActionId}" }
            }
        bundle.profiles.forEach { profile ->
            val profileRisk = AutomationSensitivityRegistry.summarize(profile, bundle.tasks)
            profileRisk.dataToExternalChains.forEach { chain ->
                warnings += "Potential data-to-external chain in profile '${profile.name}': " +
                    "${chain.sourceActionId} -> ${chain.sinkActionId}."
            }
        }

        if (bundle.schemaVersion >= 2 && bundle.metadata.powerRequests != taskPowerRequests) {
            warnings += "Bundle power manifest did not match its actions; review uses the computed powers."
        }
        if (
            bundle.schemaVersion >= 2 &&
            bundle.metadata.capabilityRequirements != computedCapabilityRequirements
        ) {
            warnings += "Bundle capability manifest did not match its actions; review uses the computed requirements."
        }

        val unsupportedActions = bundle.tasks
            .flatMap { task -> task.actions.map { task.name to it.type } }
            .filter { (_, actionId) ->
                AutomationSensitivityRegistry.isKnown(actionId) &&
                    ActionCapabilityRegistry.get(actionId).level == CapabilityLevel.Unsupported
            }
        if (unsupportedActions.isNotEmpty()) {
            warnings += "Bundle contains unsupported actions: ${unsupportedActions.joinToString { "${it.first}:${it.second}" }}."
        }

        return BundleImportPlan(
            canImport = warnings.none { warning -> warning.isBlockingImportWarning() },
            warnings = warnings,
            lossyWarnings = lossyWarnings,
            capabilityRequirements = computedCapabilityRequirements,
            powerRequests = taskPowerRequests,
        )
    }

    private fun String.isBlockingImportWarning(): Boolean =
        startsWith("Unsupported schema version") ||
            startsWith("Bundle has duplicate task ids") ||
            startsWith("Bundle has duplicate variable names") ||
            startsWith("Bundle contains unknown unclassified actions") ||
            startsWith("Import budget exceeded")

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

    private fun powerRequests(tasks: List<Task>, profiles: List<Profile>): List<RecipePowerRequest> {
        val profileNamesByTaskId = mutableMapOf<Long, MutableSet<String>>()
        profiles.forEach { profile ->
            AutomationSensitivityRegistry.reachableTasks(profile, tasks).forEach { task ->
                profileNamesByTaskId.getOrPut(task.id, ::linkedSetOf).add(profile.name)
            }
        }
        return tasks.mapNotNull { task ->
            val summary = AutomationSensitivityRegistry.summarize(task)
            if (summary.powers.isEmpty() && summary.unknownActionIds.isEmpty()) return@mapNotNull null
            RecipePowerRequest(
                taskId = task.id,
                taskName = task.name,
                profileNames = profileNamesByTaskId[task.id].orEmpty().sorted(),
                powers = summary.powers.sortedBy(AutomationPower::ordinal),
                actionIds = summary.sensitiveActionIds.sorted(),
                dataToExternalChains = summary.dataToExternalChains.map { chain ->
                    DataToExternalChainRequest(chain.sourceActionId, chain.sinkActionId)
                },
                unknownActionIds = summary.unknownActionIds.sorted(),
            )
        }.sortedWith(compareBy<RecipePowerRequest> { it.taskName.lowercase() }.thenBy { it.taskId })
    }
}

class OpenTaskerBundleRepository(
    private val db: AppDatabase,
    private val variableRepository: VariableRepository = VariableRepository(db.variableDao()),
) {
    suspend fun exportBundle(
        appVersion: String,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        name: String = "OpenTasker Export",
        description: String = "",
    ): OpenTaskerBundle {
        val tasks = db.taskDao().getAll().map { it.toDomain() }
        val profiles = db.profileDao().getAll().map { it.toDomain() }
        val variableExport = variableRepository.ordinaryExport()
        val scenes = db.sceneDao().getAll().map { it.toDomain() }

        return OpenTaskerBundleCodec.build(
            appVersion = appVersion,
            exportedAtEpochMs = exportedAtEpochMs,
            profiles = profiles,
            tasks = tasks,
            variables = variableExport.variables,
            scenes = scenes,
            omittedSecretVariableCount = variableExport.omittedSecretCount,
            name = name,
            description = description,
        )
    }

    suspend fun importBundle(bundle: OpenTaskerBundle): BundleImportReport {
        val plan = OpenTaskerBundleCodec.validate(bundle)
        require(plan.canImport) { plan.warnings.joinToString() }

        var insertedTasks = 0
        var insertedProfiles = 0
        var insertedVariables = 0
        var insertedScenes = 0
        val importWarnings = plan.warnings.toMutableList()
        val lossyWarnings = plan.lossyWarnings.toMutableList()

        db.withTransaction {
            val taskIdMap = mutableMapOf<Long, Long>()
            bundle.tasks.sortedWith(compareBy<Task> { it.name.lowercase() }.thenBy { it.id }).forEach { task ->
                val newId = db.taskDao().insert(task.copy(id = 0).toEntity())
                taskIdMap[task.id] = newId
                insertedTasks++
            }

            bundle.variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name }).forEach { variable ->
                val storageName = VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal)
                    ?: throw IllegalArgumentException("Invalid variable name '${variable.name}'")
                val existing = db.variableDao().get(storageName)
                variableRepository.importVariable(variable)
                if (existing == null) insertedVariables++
            }

            bundle.profiles.sortedWith(compareBy<Profile> { it.name.lowercase() }.thenBy { it.id }).forEach { profile ->
                val enterTaskId = taskIdMap[profile.enterTaskId]
                if (enterTaskId == null) {
                    lossyWarnings += "Skipped profile '${profile.name}' because enter task ${profile.enterTaskId} was not imported."
                    return@forEach
                }
                val remappedProfile = profile.copy(
                    id = 0,
                    enabled = false,
                    requiresRiskAcknowledgement = true,
                    enterTaskId = enterTaskId,
                    exitTaskId = profile.exitTaskId?.let { taskIdMap[it] },
                )
                db.profileDao().insert(remappedProfile.toEntity())
                insertedProfiles++
            }

            bundle.scenes.sortedWith(compareBy<Scene> { it.name.lowercase() }.thenBy { it.id }).forEach { scene ->
                val remappedElements = scene.elements.map { element ->
                    remapSceneElement(element, taskIdMap)
                }
                db.sceneDao().insert(scene.copy(id = 0, elements = remappedElements).toEntity())
                insertedScenes++
            }
        }

        return BundleImportReport(
            insertedTasks = insertedTasks,
            insertedProfiles = insertedProfiles,
            insertedVariables = insertedVariables,
            insertedScenes = insertedScenes,
            warnings = importWarnings,
            lossyWarnings = lossyWarnings.distinct(),
        )
    }

    private fun remapSceneElement(element: SceneElement, taskIdMap: Map<Long, Long>): SceneElement =
        element.copy(
            tapTaskId = element.tapTaskId?.let { taskIdMap[it] },
            longPressTaskId = element.longPressTaskId?.let { taskIdMap[it] },
        )
}
