package com.opentasker.core.transfer

import androidx.room.withTransaction
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.AutomationSensitivityRegistry
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

// v5 is a NAME-ONLY on-disk format ([BundleFile]) that carries ZERO numeric ids — every reference is by
// name (item→project, profile→task, scene-element→task, group→parent, note→item/group). This is a HARD
// CUT: v5 no longer reads id-bearing v1–v4 files (re-export them from an up-to-date build). The domain
// [OpenTaskerBundle] below is still id-bearing and drives the (unchanged) import/review machinery; the
// codec bridges the two via [BundleFile.fromDomain]/[BundleFile.toDomain], so ids never touch a file.
// v4 added `templates`; v3 per-item `position` + `sort`; v2 `projects` + projectId.
const val OPEN_TASKER_BUNDLE_SCHEMA_VERSION = 5
// The oldest on-disk schema this build still reads. Below this we reject with a re-export prompt.
const val MIN_READABLE_BUNDLE_SCHEMA_VERSION = 5

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

/**
 * The per-project decision made in the review's folder tree — one choice per project the import
 * references, keyed by lowercased project name. When the review passes such a map it supersedes the
 * single global [ProjectConflictStrategy]; that strategy stays the fallback for a bundle project the
 * map doesn't mention (and for callers that pass no map at all).
 */
enum class ProjectImportChoice {
    /** File items under the existing same-name local project (as MERGE does). */
    INTO_EXISTING,

    /** Create the project (uniquifying the name if it already exists locally, like RENAME) and file items under it. */
    CREATE,

    /** Don't create/resolve the project — its items import as Unfiled (projectId null). */
    UNFILED,
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
        // Bundles are routinely hand-authored here (and hand-edited before an import), so accept the
        // two things a human writing JSON by hand actually produces: // comments and a trailing comma.
        allowComments = true
        allowTrailingComma = true
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
        // Secrets never enter a bundle. They are dropped HERE, at the one place every export funnels
        // through, and the drop is announced — an import that silently lost a token would look like a
        // successful restore right up until the task using it failed.
        val secretNames = variables.filter { it.isSecret }.map { it.name }.sorted()
        val sortedVariables = variables
            .filterNot { it.isSecret }
            .sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name })
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
        val secretWarning = if (secretNames.isEmpty()) {
            emptyList()
        } else {
            listOf(
                "${secretNames.size} secret variable${if (secretNames.size == 1) "" else "s"} " +
                    "(${secretNames.joinToString()}) were not exported and must be re-entered after import.",
            )
        }
        return base.copy(
            metadata = base.metadata.copy(
                capabilityRequirements = capabilityRequirements(sortedTasks),
                warnings = plan.warnings + plan.lossyWarnings + secretWarning,
            )
        )
    }

    // Export writes the NAME-ONLY [BundleFile] (no ids), converted from the freshly-built domain bundle.
    fun encode(bundle: OpenTaskerBundle): String {
        // Fail closed on the way to the file, not just on the way into the bundle. [build] already
        // strips secrets, so reaching here with one means a caller assembled an OpenTaskerBundle by
        // hand — refuse rather than serialise a token into a backup somebody will share.
        require(bundle.variables.none { it.isSecret }) {
            "Secret variables must never be serialised into a bundle: " +
                bundle.variables.filter { it.isSecret }.joinToString { it.name }
        }
        // Export writes the NAME-ONLY BundleFile DTO (no ids), but upstream's redaction pass runs
        // first: it strips a literal copy of a secret out of an action's arguments, its run-only-if
        // guard and its label before any of it reaches the file.
        return json.encodeToString(BundleFile.fromDomain(sanitizeForExport(bundle)))
    }

    /** Applies the same field-aware policy used by diagnostic and Tasker XML serialization. */
    fun sanitizeForExport(
        bundle: OpenTaskerBundle,
        secretVariableNames: Set<String> = emptySet(),
        secretVariableValues: Set<String> = emptySet(),
    ): OpenTaskerBundle {
        // Passing the plaintext values, not just the names, is what lets the policy catch an
        // argument holding a literal copy of a secret. Without them the JSON export could only
        // redact arguments that referenced a secret by name or matched a generic token pattern,
        // while the Tasker XML exporter - the same policy - already redacted the literal.
        val context = ExportRedactionPolicy.Context(
            secretNames = secretVariableNames,
            secretValues = secretVariableValues,
        )
        var redactedFieldCount = 0
        val tasks = bundle.tasks.map { task ->
            task.copy(
                actions = task.actions.map { action ->
                    val sanitized = ExportRedactionPolicy.sanitizeActionArguments(action.type, action.args, context)
                    redactedFieldCount += sanitized.redactedFields.size
                    // A run-only-if guard is user text like `%Pin == 4321`, so it can hold a
                    // literal copy of a secret exactly the way an argument can. Only args were
                    // sanitized here, while the Tasker XML exporter - the same policy - already
                    // refused to write such a guard. A redacted guard can no longer match, so the
                    // action is skipped after import rather than running unguarded.
                    val guard = redactExportedText(action.condition, context)
                    val label = redactExportedText(action.label, context)
                    if (guard.wasRedacted) redactedFieldCount++
                    if (label.wasRedacted) redactedFieldCount++
                    action.copy(args = sanitized.args, condition = guard.value, label = label.value)
                },
            )
        }
        if (redactedFieldCount == 0) return bundle.copy(tasks = tasks)
        return bundle.copy(
            tasks = tasks,
            metadata = bundle.metadata.copy(
                warnings = bundle.metadata.warnings + ExportRedactionPolicy.SENSITIVE_ACTION_WARNING,
            ),
        )
    }

    private class ExportedText(val value: String?, val wasRedacted: Boolean)

    /**
     * Redacts a free-text action field that holds no argument semantics.
     *
     * Matching on a literal secret *value* only. A guard that names a secret (`%ApiKey == is_set`)
     * leaks nothing, because the bundle already carries that variable's name with its value
     * deliberately omitted, and redacting it would break a working guard. Running these fields
     * through the full `redactText` was worse than doing nothing: its URL, Authorization and
     * `key=value` patterns fire with no secrets configured at all, so an ordinary label like
     * "Set config key=abc123" was mangled and the export warned about a secret nobody had.
     */
    private fun redactExportedText(
        value: String?,
        context: ExportRedactionPolicy.Context,
    ): ExportedText {
        if (value.isNullOrBlank()) return ExportedText(value, wasRedacted = false)
        val carriesSecret = context.secretValues.any { it.isNotEmpty() && value.contains(it) }
        if (!carriesSecret) return ExportedText(value, wasRedacted = false)
        // The whole field goes, not just the secret inside it. Substituting in place looked
        // friendlier and was unsafe: `%Pin != 4321` would have become `%Pin != [REDACTED]`, which
        // is true for every value %Pin can realistically hold, so an action that was guarded on
        // export would run unguarded after import. A bare placeholder parses as no comparison at
        // all, falls through to toBoolean(), and is false whatever operator was there.
        return ExportedText(ExportRedactionPolicy.REDACTED, wasRedacted = true)
    }

    @Throws(SerializationException::class, IllegalArgumentException::class)
    fun decode(rawJson: String): OpenTaskerBundle {
        require(rawJson.length <= MAX_BUNDLE_JSON_CHARS) {
            "Bundle JSON exceeds ${MAX_BUNDLE_JSON_CHARS / 1024 / 1024} MB size limit"
        }
        // HARD CUT: only the id-free name-based format (schema >= 5) is readable. An older id-bearing
        // backup must be re-exported from an up-to-date build — we never fall back to reading ids.
        val version = runCatching { json.decodeFromString<SchemaProbe>(rawJson).schemaVersion }.getOrDefault(1)
        require(version >= MIN_READABLE_BUNDLE_SCHEMA_VERSION) {
            "This backup is an older format (v$version). Re-export it from an up-to-date 白い熊 自由作業盤 " +
                "(the format is now name-based and carries no ids)."
        }
        // Parse the name-only file, then rebuild the id-bearing domain bundle from the names.
        return json.decodeFromString<BundleFile>(rawJson).toDomain()
    }

    /** Tiny header probe to read just the schema version before committing to the full parse. */
    @Serializable
    private data class SchemaProbe(val schemaVersion: Int = 1)

    private const val MAX_BUNDLE_JSON_CHARS = 16 * 1024 * 1024

    fun validate(bundle: OpenTaskerBundle): BundleImportPlan {
        val warnings = mutableListOf<String>()
        val lossyWarnings = mutableListOf<String>()

        if (bundle.schemaVersion > OPEN_TASKER_BUNDLE_SCHEMA_VERSION) {
            warnings += "Unsupported schema version ${bundle.schemaVersion}; this build reads up to $OPEN_TASKER_BUNDLE_SCHEMA_VERSION."
        }

        // Actions this build has never heard of block the import. They arrive from a bundle written by a
        // newer build (or a typo), and importing them silently produces a task that looks fine in the
        // editor and fails at the first run — after the profile that owns it has already been enabled.
        val unknownActions = bundle.tasks
            .flatMap { task -> task.actions.map { it.type } }
            .filterNot(AutomationSensitivityRegistry::isKnown)
            .distinct()
            .sorted()
        if (unknownActions.isNotEmpty()) {
            warnings += "Bundle contains unknown unclassified actions: ${unknownActions.joinToString()}."
        }

        // Names are the identity now (the format carries no ids), so duplicate NAMES are the blocking
        // clash — two same-named tasks can't both name-resolve. (Scene/profile same-name overwrite in
        // place, but a task name is a link target for profiles/scenes, so it must be unique in a bundle.)
        duplicateStrings(bundle.tasks.map { it.name }).takeIf { it.isNotEmpty() }?.let { duplicates ->
            warnings += "Bundle has duplicate task names: ${duplicates.joinToString()}."
        }
        // A variable's identity is (name, SCOPE): a super-global %DT_Ampmn and 時間と日付's own project-
        // scoped %DT_Ampmn are DISTINCT and both legitimately appear in a full export. Only a true clash —
        // same name AND same scope — blocks. Keying on name alone wrongly rejected the app's own export.
        bundle.variables.groupingBy { it.name to it.projectId }.eachCount()
            .filterValues { count -> count > 1 }.keys.map { it.first }.distinct()
            .takeIf { it.isNotEmpty() }?.let { duplicates ->
                warnings += "Bundle has duplicate variable names: ${duplicates.joinToString()}."
            }

        // (The "project isn't part of this import → Unfiled" notice is gone: the review's folder tree now
        //  shows each item's project folder + a Create/Unfiled pill, so the text was redundant.)

        val taskIds = bundle.tasks.map { it.id }.toSet()
        bundle.profiles.forEach { profile ->
            if (profile.enterTaskId !in taskIds) {
                lossyWarnings += "Profile “${profile.name}” points to a task that isn't part of this import and will be skipped."
            }
            val exitTaskId = profile.exitTaskId
            if (exitTaskId != null && exitTaskId !in taskIds) {
                lossyWarnings += "Profile “${profile.name}” has an exit task that isn't part of this import; that link will be dropped."
            }
        }

        // A link only truly breaks when the task is NOT in the bundle AND the element carries no task
        // NAME to re-bind against an existing task on import. With a name present it resolves by name, so
        // don't cry wolf (this is the warning that misfired for a scene-only re-import).
        bundle.scenes.forEach { scene ->
            scene.elements.forEach { element ->
                if (element.tapTaskId != null && element.tapTaskId !in taskIds && element.tapTaskName.isBlank()) {
                    lossyWarnings += "Scene “${scene.name}” has an element whose tap task isn't part of this import (and no name to re-bind); the link will be dropped."
                }
                if (element.longPressTaskId != null && element.longPressTaskId !in taskIds && element.longPressTaskName.isBlank()) {
                    lossyWarnings += "Scene “${scene.name}” has an element whose long-press task isn't part of this import (and no name to re-bind); the link will be dropped."
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
            startsWith("Bundle contains unknown unclassified actions") ||
            startsWith("Bundle has duplicate task names") ||
            startsWith("Bundle has duplicate variable names")

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
        // A secret row's `toDomain()` throws by design (ciphertext must never reach the domain), so
        // mapping the table wholesale made ONE secret variable break every export there is: "Export
        // everything", the adb bridge's EXPORT_WORKSPACE, and 保存復元's whole app-state ZIP. Carry
        // secrets as name-only placeholders instead; [OpenTaskerBundleCodec.build] drops them and says
        // so in the bundle's warnings.
        val variables = db.variableDao().getAll().map { entity ->
            if (entity.isSecret) {
                Variable(name = entity.name, value = "", projectId = entity.projectId, isSecret = true)
            } else {
                entity.toDomain()
            }
        }
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
        // Per-item overrides keyed "<tab>:<lowercased name>" (tab ∈ tasks/profiles/scenes/templates/
        // variables). A conflicting item uses its override if present, else [itemConflictStrategy].
        itemStrategyOverrides: Map<String, ItemConflictStrategy> = emptyMap(),
        // Per-project decision from the review's folder tree, keyed by lowercased project name. A bundle
        // project the map mentions follows its choice; one it doesn't falls back to [projectConflictStrategy].
        projectChoices: Map<String, ProjectImportChoice> = emptyMap(),
    ): BundleImportReport {
        val plan = OpenTaskerBundleCodec.validate(bundle)
        require(plan.canImport) { plan.warnings.joinToString() }

        // Resolve the effective conflict strategy for one item: its per-item override, else the global one.
        // [lowercaseName] is already lowercased (matches the review screen's key + the grouping keys below).
        fun strategyFor(tab: String, lowercaseName: String): ItemConflictStrategy =
            itemStrategyOverrides["$tab:$lowercaseName"] ?: itemConflictStrategy

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
            // reuses the existing project; RENAME inserts a separate, uniquely-named one. The item→project
            // link is thus resolved BY NAME: a bundle item's projectId maps to its bundle project's NAME
            // (projects[]), which is matched (case-insensitively) to the local project of the same name.
            val existingProjects = db.projectDao().getAll()
            val existingByName = existingProjects.associateTo(mutableMapOf()) { it.name.lowercase() to it.id }
            val existingProjectIds = existingProjects.mapTo(mutableSetOf()) { it.id }
            val takenNames = existingByName.keys.toMutableSet()
            val projectIdMap = mutableMapOf<Long, Long>()
            // Bundle project ids the review explicitly sent to Unfiled — their items resolve to projectId null.
            val unfiledProjectIds = mutableSetOf<Long>()

            // Helper: create the project under a free (uniquified) name and map the bundle id to it.
            suspend fun createProject(project: Project) {
                val newName = uniqueName(project.name, takenNames)
                val newId = db.projectDao().insert(project.copy(id = 0, name = newName).toEntity())
                projectIdMap[project.id] = newId
                takenNames += newName.lowercase()
                existingByName[newName.lowercase()] = newId
                insertedProjects++
            }

            bundle.projects.sortedWith(compareBy<Project> { it.sortOrder }.thenBy { it.name.lowercase() }).forEach { project ->
                val existingId = existingByName[project.name.lowercase()]
                // The review decides per project (by lowercased name). For a project the map doesn't mention
                // fall back to the global strategy: MERGE over an existing name → INTO_EXISTING, else CREATE.
                val choice = projectChoices[project.name.lowercase()] ?: when {
                    existingId != null && projectConflictStrategy == ProjectConflictStrategy.MERGE -> ProjectImportChoice.INTO_EXISTING
                    else -> ProjectImportChoice.CREATE
                }
                when (choice) {
                    ProjectImportChoice.UNFILED -> unfiledProjectIds += project.id
                    ProjectImportChoice.INTO_EXISTING ->
                        if (existingId != null) projectIdMap[project.id] = existingId else createProject(project)
                    ProjectImportChoice.CREATE -> createProject(project)
                }
            }
            // Map a bundle projectId to the resolved local id: Unfiled-chosen projects → null; else via
            // projects[] (name-resolved above); else the legacy raw id, but ONLY if a local project already
            // carries that id (so a stray id whose project wasn't in the bundle doesn't mis-file — it falls
            // through to Unfiled instead).
            fun remapProjectId(projectId: Long?): Long? {
                if (projectId == null) return null
                if (projectId in unfiledProjectIds) return null
                projectIdMap[projectId]?.let { return it }
                return projectId.takeIf { it in existingProjectIds }
            }

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
                    when (strategyFor("tasks", name)) {
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

            // Variables are keyed by (scope projectId, name); insert REPLACEs on that key. Honour the
            // per-item strategy: OVERWRITE_DELETE replaces in place (the historical behaviour); RENAME
            // keeps both by uniquifying the incoming name within its scope; OVERWRITE_BACKUP copies the
            // existing value aside to "<name>.<stamp>.bak" first, then replaces.
            val existingVars = db.variableDao().getAll()
            val takenVarNamesByScope = mutableMapOf<Long, MutableSet<String>>()
            existingVars.forEach { takenVarNamesByScope.getOrPut(it.projectId) { mutableSetOf() }.add(it.name.lowercase()) }
            bundle.variables.sortedWith(compareBy<Variable> { it.name.lowercase() }.thenBy { it.name }).forEach { variable ->
                // Super-globals (projectId 0) stay super; project-globals remap to the resolved project
                // (or fall back to super if the bundle didn't carry that project).
                val pid = if (variable.projectId == 0L) 0L else (projectIdMap[variable.projectId] ?: 0L)
                // Guard #2 of the "no MixedCase in super" invariant: a project-scoped (MixedCase) name that
                // resolves to the super bucket (explicit projectId 0, or an unresolved project) would be a
                // dead shadow-copy of the real project-global — skip it rather than seed one.
                val n = variable.name
                if (pid == 0L && n.isNotEmpty() && n[0].isUpperCase() && n.any { it.isLowerCase() }) return@forEach
                val taken = takenVarNamesByScope.getOrPut(pid) { mutableSetOf() }
                val collides = variable.name.lowercase() in taken
                when (strategyFor("variables", variable.name.lowercase())) {
                    ItemConflictStrategy.RENAME -> {
                        val newName = if (collides) uniqueName(variable.name, taken) else variable.name
                        db.variableDao().insert(VariableEntity(pid, newName, variable.value))
                        taken += newName.lowercase()
                    }
                    ItemConflictStrategy.OVERWRITE_BACKUP -> {
                        if (collides) {
                            existingVars.firstOrNull { it.projectId == pid && it.name.equals(variable.name, ignoreCase = true) }?.let { old ->
                                db.variableDao().insert(VariableEntity(pid, backupName(old.name), old.value))
                                taken += backupName(old.name).lowercase()
                            }
                        }
                        db.variableDao().insert(VariableEntity(pid, variable.name, variable.value))
                        taken += variable.name.lowercase()
                    }
                    ItemConflictStrategy.OVERWRITE_DELETE -> {
                        db.variableDao().insert(VariableEntity(pid, variable.name, variable.value))
                        taken += variable.name.lowercase()
                    }
                }
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
                    when (strategyFor("profiles", name)) {
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
                    when (strategyFor("scenes", name)) {
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
            val strat = strategyFor("templates", tpl.name.lowercase())
            val collides = tpl.name.lowercase() in takenTemplateNames
            if (collides && strat == ItemConflictStrategy.OVERWRITE_BACKUP) {
                TemplateStore.get(tpl.name)?.let { TemplateStore.put(backupName(tpl.name), it) }
                takenTemplateNames += backupName(tpl.name).lowercase()
            }
            val targetName = if (collides && strat == ItemConflictStrategy.RENAME) {
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

        // Restore the per-category sort the bundle carried — but NEVER downgrade a tab the user has set
        // to MANUAL (their intentional drag order). A partial import (a few tasks) must not silently
        // re-sort an untouched group: its `sort` decodes to the Alphabetical default and would otherwise
        // flip the whole tab off Manual (白い熊: importing scrambled the re-sorted 起動無効 group).
        val curSort = ListSortStore.state.value
        val incSort = bundle.sort.toPrefs()
        fun keepManual(current: SortMethod, incoming: SortMethod) =
            if (current == SortMethod.MANUAL) SortMethod.MANUAL else incoming
        ListSortStore.setAll(
            SortPrefs(
                profiles = keepManual(curSort.profiles, incSort.profiles),
                tasks = keepManual(curSort.tasks, incSort.tasks),
                scenes = keepManual(curSort.scenes, incSort.scenes),
                projects = keepManual(curSort.projects, incSort.projects),
            ),
        )

        // Resolve the target project ids to names for the import-result dialog.
        val projectNamesById = db.projectDao().getAll().associate { it.id to it.name }
        val projectNames = targetProjectIds
            .map { id -> if (id == null) "Unfiled" else (projectNamesById[id] ?: "Unfiled") }
            .distinct()

        // Imported variable rows were written via the DAO, not PersistentGlobalScope — re-warm the
        // cache or the new values stay invisible to %var expansion until the process restarts.
        com.opentasker.core.engine.variables.PersistentGlobalScope.refreshFromDb()

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
