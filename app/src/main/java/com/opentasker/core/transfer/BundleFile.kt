package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.CollisionMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.widget.WidgetTemplate
import kotlinx.serialization.Serializable

/**
 * The on-disk bundle format (schema 5+) — a NAME-ONLY interchange schema that carries **zero numeric
 * ids**. Every reference is by name: an item names its project; a profile names its task; a scene element
 * names its task; a group names its parent; item-meta names its item and group. This is deliberately
 * decoupled from the id-bearing Room/domain models: ids are storage primary keys and belong in the DB,
 * never in an exported file. Nothing here relies on an id matching the device.
 *
 * The bridge to the (tested) id-based import/export machinery is two pure converters:
 *  - [fromDomain] builds this from a freshly-assembled domain [OpenTaskerBundle], resolving every id to a
 *    name using the bundle's own lists (no DB lookup needed).
 *  - [toDomain] rebuilds a domain [OpenTaskerBundle] by fabricating throwaway *bundle-local* ids FROM the
 *    names (an incrementing counter per type) and wiring the id references from the name references. Those
 *    synthetic ids never appear in a file and are immediately remapped to real DB ids on import — the
 *    import resolves purely from names.
 */
@Serializable
data class BundleFile(
    val schemaVersion: Int = OPEN_TASKER_BUNDLE_SCHEMA_VERSION,
    val appVersion: String,
    val exportedAtEpochMs: Long,
    val metadata: BundleMetadata = BundleMetadata(),
    val projects: List<ProjectDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val profiles: List<ProfileDto> = emptyList(),
    val variables: List<VariableDto> = emptyList(),
    val scenes: List<SceneDto> = emptyList(),
    val templates: List<WidgetTemplate> = emptyList(),
    val sort: BundleSortConfig = BundleSortConfig(),
    val groups: List<GroupDto> = emptyList(),
    val itemMeta: List<ItemMetaDto> = emptyList(),
) {
    /** Rebuild the id-bearing domain bundle from names (see class doc). Synthetic ids are local + throwaway. */
    fun toDomain(): OpenTaskerBundle {
        // Project name (lowercased) → synthetic id. null name → Unfiled (projectId null / super-global 0).
        var pid = 0L
        val projectIdByName = HashMap<String, Long>()
        val domainProjects = projects.map { d ->
            val id = ++pid
            projectIdByName[d.name.lowercase()] = id
            Project(id = id, name = d.name, color = d.color, sortOrder = d.sortOrder, description = d.description)
        }
        fun projId(name: String?): Long? = name?.let { projectIdByName[it.lowercase()] }

        var tid = 0L
        val taskIdByName = HashMap<String, Long>()
        val domainTasks = tasks.map { d ->
            val id = ++tid
            taskIdByName[d.name.lowercase()] = id
            Task(
                id = id, name = d.name, priority = d.priority, collisionMode = d.collisionMode,
                actions = d.actions.map { a -> ActionSpec(id = 0, type = a.type, label = a.label, args = a.args, continueOnError = a.continueOnError, condition = a.condition) },
                projectId = projId(d.projectName), position = d.position, iconPath = null,
                freezeBubble = d.freezeBubble, iconData = d.iconData,
            )
        }
        fun taskId(name: String): Long? = name.takeIf { it.isNotBlank() }?.let { taskIdByName[it.lowercase()] }

        var prid = 0L
        val profileIdByName = HashMap<String, Long>()
        val domainProfiles = profiles.map { d ->
            val id = ++prid
            profileIdByName[d.name.lowercase()] = id
            Profile(
                id = id, name = d.name, enabled = d.enabled, contexts = d.contexts,
                enterTaskId = taskId(d.enterTaskName) ?: 0L, exitTaskId = taskId(d.exitTaskName),
                cooldownSec = d.cooldownSec, automationMode = d.automationMode,
                projectId = projId(d.projectName), position = d.position,
                enterTaskName = d.enterTaskName, exitTaskName = d.exitTaskName,
            )
        }

        var sid = 0L
        val sceneIdByName = HashMap<String, Long>()
        val domainScenes = scenes.map { d ->
            val id = ++sid
            sceneIdByName[d.name.lowercase()] = id
            Scene(
                id = id, name = d.name, widthDp = d.widthDp, heightDp = d.heightDp,
                elements = d.elements.map { e ->
                    SceneElement(
                        id = 0, type = e.type, xDp = e.xDp, yDp = e.yDp, widthDp = e.widthDp, heightDp = e.heightDp,
                        config = e.config, tapTaskId = taskId(e.tapTaskName), longPressTaskId = taskId(e.longPressTaskName),
                        tapTaskName = e.tapTaskName, longPressTaskName = e.longPressTaskName,
                    )
                },
                projectId = projId(d.projectName), position = d.position, bgColor = d.bgColor,
                cornerRadiusDp = d.cornerRadiusDp, scrimAlpha = d.scrimAlpha, borderColor = d.borderColor,
                borderWidth = d.borderWidth, defaultPosition = d.defaultPosition, defaultModal = d.defaultModal,
                defaultDismissOnOutside = d.defaultDismissOnOutside,
            )
        }

        val domainVariables = variables.map { d -> Variable(name = d.name, value = d.value, projectId = projId(d.projectName) ?: 0L) }

        // Groups keyed by (tab, project, name) → synthetic id, so a note's groupName and a group's parent
        // resolve within the same tab + project.
        var gid = 0L
        val groupIdByKey = HashMap<String, Long>()
        fun gkey(tab: String, projectName: String?, name: String) = "$tab|${projectName?.lowercase() ?: ""}|${name.lowercase()}"
        val groupSyn = groups.map { d -> val id = ++gid; groupIdByKey[gkey(d.tab, d.projectName, d.name)] = id; d to id }
        val domainGroups = groupSyn.map { (d, id) ->
            ItemGroupSpec(
                id = id, tab = d.tab, projectId = projId(d.projectName), name = d.name, note = d.note,
                position = d.position, expanded = d.expanded, noteExpanded = d.noteExpanded,
                parentGroupId = d.parentGroupName?.let { groupIdByKey[gkey(d.tab, d.projectName, it)] },
            )
        }

        fun itemSynId(tab: String, name: String): Long? = when (tab) {
            "tasks" -> taskIdByName[name.lowercase()]
            "profiles" -> profileIdByName[name.lowercase()]
            "scenes" -> sceneIdByName[name.lowercase()]
            else -> null
        }
        val domainItemMeta = itemMeta.mapNotNull { m ->
            if (m.tab == "widgets") {
                // Widget notes are name-keyed on both sides — itemKey stays the template name.
                ItemMetaSpec(tab = "widgets", itemKey = m.itemName, note = m.note, noteExpanded = m.noteExpanded, groupId = null)
            } else {
                val iid = itemSynId(m.tab, m.itemName) ?: return@mapNotNull null
                ItemMetaSpec(
                    tab = m.tab, itemKey = iid.toString(), note = m.note, noteExpanded = m.noteExpanded,
                    groupId = m.groupName?.let { groupIdByKey[gkey(m.tab, m.projectName, it)] },
                )
            }
        }

        return OpenTaskerBundle(
            schemaVersion = schemaVersion, appVersion = appVersion, exportedAtEpochMs = exportedAtEpochMs,
            metadata = metadata, projects = domainProjects, tasks = domainTasks, profiles = domainProfiles,
            variables = domainVariables, scenes = domainScenes, templates = templates, sort = sort,
            itemMeta = domainItemMeta, groups = domainGroups,
        )
    }

    companion object {
        // Action arg keys whose value references another entity by name-or-id — rewritten id→name on export.
        private val SCENE_REF = mapOf("scene.show" to "scene", "scene.hide" to "scene")
        private val TASK_REF = mapOf("task.run" to "task", "task.stop" to "task", "perform.task" to "task")

        /** Build the id-free file from a freshly-assembled domain bundle, resolving every id to a name
         *  using the bundle's OWN lists (so no DB access is needed). */
        fun fromDomain(b: OpenTaskerBundle): BundleFile {
            val projectNameById = b.projects.associate { it.id to it.name }
            val taskNameById = b.tasks.associate { it.id to it.name }
            val profileById = b.profiles.associateBy { it.id }
            val taskById = b.tasks.associateBy { it.id }
            val sceneNameById = b.scenes.associate { it.id to it.name }
            val sceneById = b.scenes.associateBy { it.id }
            val groupNameById = b.groups.associate { it.id to it.name }
            fun projName(pid: Long?): String? = pid?.takeIf { it != 0L }?.let { projectNameById[it] }

            // Rewrite a numeric entity-ref inside an action's args to the entity NAME (belt-and-braces so no
            // id survives even in string-typed args). Non-numeric (already-name) values pass through.
            fun cleanArgs(type: String, args: Map<String, String>): Map<String, String> {
                fun rewrite(key: String?, nameById: Map<Long, String>): Map<String, String> {
                    if (key == null) return args
                    val v = args[key]?.toLongOrNull() ?: return args
                    val name = nameById[v] ?: return args
                    return args + (key to name)
                }
                return rewrite(SCENE_REF[type], sceneNameById).let { a ->
                    val tk = TASK_REF[type] ?: return@let a
                    val v = a[tk]?.toLongOrNull() ?: return@let a
                    taskNameById[v]?.let { a + (tk to it) } ?: a
                }
            }

            val projects = b.projects.map { ProjectDto(it.name, it.color, it.sortOrder, it.description) }
            val tasks = b.tasks.map { t ->
                TaskDto(
                    name = t.name, projectName = projName(t.projectId), priority = t.priority, collisionMode = t.collisionMode,
                    actions = t.actions.map { a -> ActionDto(a.type, a.label, cleanArgs(a.type, a.args), a.continueOnError, a.condition) },
                    position = t.position, freezeBubble = t.freezeBubble, iconData = t.iconData,
                )
            }
            val profiles = b.profiles.map { p ->
                ProfileDto(
                    name = p.name, enabled = p.enabled, contexts = p.contexts,
                    // Names first; fall back to resolving the numeric id via the bundle if a name is blank.
                    enterTaskName = p.enterTaskName.ifBlank { taskNameById[p.enterTaskId] ?: "" },
                    exitTaskName = p.exitTaskName.ifBlank { p.exitTaskId?.let { taskNameById[it] } ?: "" },
                    cooldownSec = p.cooldownSec, automationMode = p.automationMode,
                    projectName = projName(p.projectId), position = p.position,
                )
            }
            val variables = b.variables.map { VariableDto(it.name, it.value, projName(it.projectId)) }
            val scenes = b.scenes.map { s ->
                SceneDto(
                    name = s.name, projectName = projName(s.projectId), widthDp = s.widthDp, heightDp = s.heightDp,
                    elements = s.elements.map { e ->
                        SceneElementDto(
                            type = e.type, xDp = e.xDp, yDp = e.yDp, widthDp = e.widthDp, heightDp = e.heightDp, config = e.config,
                            tapTaskName = e.tapTaskName.ifBlank { e.tapTaskId?.let { taskNameById[it] } ?: "" },
                            longPressTaskName = e.longPressTaskName.ifBlank { e.longPressTaskId?.let { taskNameById[it] } ?: "" },
                        )
                    },
                    position = s.position, bgColor = s.bgColor, cornerRadiusDp = s.cornerRadiusDp, scrimAlpha = s.scrimAlpha,
                    borderColor = s.borderColor, borderWidth = s.borderWidth, defaultPosition = s.defaultPosition,
                    defaultModal = s.defaultModal, defaultDismissOnOutside = s.defaultDismissOnOutside,
                )
            }
            val groups = b.groups.map { g ->
                GroupDto(
                    tab = g.tab, projectName = projName(g.projectId), name = g.name, note = g.note, position = g.position,
                    expanded = g.expanded, noteExpanded = g.noteExpanded,
                    parentGroupName = g.parentGroupId?.let { groupNameById[it] },
                )
            }
            val itemMeta = b.itemMeta.mapNotNull { m ->
                if (m.tab == "widgets") {
                    ItemMetaDto(tab = "widgets", projectName = null, itemName = m.itemKey, note = m.note, noteExpanded = m.noteExpanded, groupName = null)
                } else {
                    val id = m.itemKey.toLongOrNull() ?: return@mapNotNull null
                    val (projName, itemName) = when (m.tab) {
                        "tasks" -> taskById[id]?.let { projName(it.projectId) to it.name }
                        "profiles" -> profileById[id]?.let { projName(it.projectId) to it.name }
                        "scenes" -> sceneById[id]?.let { projName(it.projectId) to it.name }
                        else -> null
                    } ?: return@mapNotNull null
                    ItemMetaDto(m.tab, projName, itemName, m.note, m.noteExpanded, m.groupId?.let { groupNameById[it] })
                }
            }

            return BundleFile(
                schemaVersion = b.schemaVersion, appVersion = b.appVersion, exportedAtEpochMs = b.exportedAtEpochMs,
                metadata = b.metadata, projects = projects, tasks = tasks, profiles = profiles, variables = variables,
                scenes = scenes, templates = b.templates, sort = b.sort, groups = groups, itemMeta = itemMeta,
            )
        }
    }
}

@Serializable
data class ProjectDto(
    val name: String,
    val color: Int? = null,
    val sortOrder: Int = 0,
    val description: String = "",
)

@Serializable
data class TaskDto(
    val name: String,
    val projectName: String? = null,   // null = Unfiled
    val priority: Int = 5,
    val collisionMode: CollisionMode = CollisionMode.ABORT_NEW,
    val actions: List<ActionDto> = emptyList(),
    val position: Int = 0,
    val freezeBubble: Boolean = false,
    val iconData: String? = null,      // base64 PNG (the device-local iconPath is intentionally not exported)
)

@Serializable
data class ActionDto(
    val type: String,
    val label: String? = null,
    val args: Map<String, String> = emptyMap(),
    val continueOnError: Boolean = false,
    val condition: String? = null,
)

@Serializable
data class ProfileDto(
    val name: String,
    val enabled: Boolean = true,
    val contexts: List<ContextSpec> = emptyList(),
    val enterTaskName: String = "",    // the enter task, by name
    val exitTaskName: String = "",     // the exit task, by name ("" = none)
    val cooldownSec: Int = 0,
    val automationMode: AutomationMode = AutomationMode.SINGLE,
    val projectName: String? = null,
    val position: Int = 0,
)

@Serializable
data class SceneDto(
    val name: String,
    val projectName: String? = null,
    val widthDp: Int,
    val heightDp: Int,
    val elements: List<SceneElementDto> = emptyList(),
    val position: Int = 0,
    val bgColor: String? = null,
    val cornerRadiusDp: Int = 16,
    val scrimAlpha: Int = 55,
    val borderColor: String? = null,
    val borderWidth: Int = 0,
    val defaultPosition: String = "center",
    val defaultModal: Boolean = true,
    val defaultDismissOnOutside: Boolean = true,
)

@Serializable
data class SceneElementDto(
    val type: SceneElementType,
    val xDp: Int,
    val yDp: Int,
    val widthDp: Int,
    val heightDp: Int,
    val config: Map<String, String> = emptyMap(),
    val tapTaskName: String = "",
    val longPressTaskName: String = "",
)

@Serializable
data class VariableDto(
    val name: String,
    val value: String,
    val projectName: String? = null,   // null = super-global (%ALLCAPS); a name = that project's %MixedCase
)

@Serializable
data class GroupDto(
    val tab: String,                   // "tasks" / "profiles" / "scenes"
    val projectName: String? = null,
    val name: String,
    val note: String = "",
    val position: Int = 0,
    val expanded: Boolean = true,
    val noteExpanded: Boolean = false,
    val parentGroupName: String? = null,
)

@Serializable
data class ItemMetaDto(
    val tab: String,                   // "tasks" / "profiles" / "scenes" / "widgets"
    val projectName: String? = null,   // the item's project (disambiguates same-named items across projects)
    val itemName: String,
    val note: String = "",
    val noteExpanded: Boolean = false,
    val groupName: String? = null,     // the group this item sits in, within (tab, projectName)
)
