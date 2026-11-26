package com.sysadmindoc.ruledeck.core.references

import com.sysadmindoc.ruledeck.core.actions.NotificationTaskBindings
import com.sysadmindoc.ruledeck.core.engine.STOP_TASK_ACTION_ID
import com.sysadmindoc.ruledeck.core.engine.SUB_TASK_ACTION_ID
import com.sysadmindoc.ruledeck.core.engine.SUB_TASK_REF_KEYS
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.Scene
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.model.Variable
import com.sysadmindoc.ruledeck.core.model.VariableNamePolicy
import com.sysadmindoc.ruledeck.core.model.enterTaskIdOrNull

/**
 * The one enumeration of every place a task can be referenced from another automation object.
 *
 * Deletion, rename, bundle import, and any future search or preview surface must resolve through
 * this index instead of hand-rolling their own scan, because each site stores its reference
 * differently: profiles hold a numeric column, `task.run` holds an id *or* a name, notification
 * buttons hold an id with a legacy-name fallback, and scene gestures hold nullable ids.
 */
sealed interface TaskReferenceSite {
    /** Stable identity of the owning object, used to group and rewrite references. */
    val ownerKind: OwnerKind
    val ownerId: Long
    val ownerName: String

    enum class OwnerKind { PROFILE, TASK, SCENE, SETTINGS }

    data class ProfileEnterTask(override val ownerId: Long, override val ownerName: String) : TaskReferenceSite {
        override val ownerKind = OwnerKind.PROFILE
    }

    data class ProfileExitTask(override val ownerId: Long, override val ownerName: String) : TaskReferenceSite {
        override val ownerKind = OwnerKind.PROFILE
    }

    data class ProfileFallbackTask(override val ownerId: Long, override val ownerName: String) : TaskReferenceSite {
        override val ownerKind = OwnerKind.PROFILE
    }

    data object GlobalFallbackTask : TaskReferenceSite {
        override val ownerKind = OwnerKind.SETTINGS
        override val ownerId: Long = 0L
        override val ownerName: String = "Global settings"
    }

    /** A `task.run` action inside another task. */
    data class SubTaskRun(
        override val ownerId: Long,
        override val ownerName: String,
        val actionIndex: Int,
        val argKey: String,
    ) : TaskReferenceSite {
        override val ownerKind = OwnerKind.TASK
    }

    /** A `task.stop` action inside another task. */
    data class StopTaskStep(
        override val ownerId: Long,
        override val ownerName: String,
        val actionIndex: Int,
        val argKey: String,
    ) : TaskReferenceSite {
        override val ownerKind = OwnerKind.TASK
    }

    /** A notification action's button binding inside another task. */
    data class NotificationButton(
        override val ownerId: Long,
        override val ownerName: String,
        val actionIndex: Int,
        val buttonIndex: Int,
        val argKey: String,
    ) : TaskReferenceSite {
        override val ownerKind = OwnerKind.TASK
    }

    data class SceneTap(
        override val ownerId: Long,
        override val ownerName: String,
        val elementIndex: Int,
    ) : TaskReferenceSite {
        override val ownerKind = OwnerKind.SCENE
    }

    data class SceneLongPress(
        override val ownerId: Long,
        override val ownerName: String,
        val elementIndex: Int,
    ) : TaskReferenceSite {
        override val ownerKind = OwnerKind.SCENE
    }
}

/** Every owner field that can contain an executable variable expression. */
sealed interface VariableReferenceSite {
    val ownerKind: OwnerKind
    val ownerId: Long
    val ownerName: String
    val projectId: Long

    enum class OwnerKind { PROFILE, TASK, SCENE }

    data class ProfileContextBinding(
        override val ownerId: Long,
        override val ownerName: String,
        override val projectId: Long,
        val contextIndex: Int,
        val configKey: String,
    ) : VariableReferenceSite {
        override val ownerKind = OwnerKind.PROFILE
    }

    data class TaskActionArgument(
        override val ownerId: Long,
        override val ownerName: String,
        override val projectId: Long,
        val actionIndex: Int,
        val argKey: String,
    ) : VariableReferenceSite {
        override val ownerKind = OwnerKind.TASK
    }

    data class TaskCondition(
        override val ownerId: Long,
        override val ownerName: String,
        override val projectId: Long,
        val actionIndex: Int,
    ) : VariableReferenceSite {
        override val ownerKind = OwnerKind.TASK
    }

    data class SceneBinding(
        override val ownerId: Long,
        override val ownerName: String,
        override val projectId: Long,
        val elementIndex: Int,
        val configKey: String,
    ) : VariableReferenceSite {
        override val ownerKind = OwnerKind.SCENE
    }
}

data class VariableReference(
    val site: VariableReferenceSite,
    val name: String,
    val syntax: VariableReferenceSyntax,
    val scope: VariableReferenceScope,
)

/** How a site names the task it points at. */
sealed interface TaskRef {
    data class ById(val id: Long) : TaskRef
    /** [ignoreCase] is off where the runtime compares names exactly, as a button's legacy name. */
    data class ByName(val name: String, val ignoreCase: Boolean = true) : TaskRef

    fun matches(task: Task): Boolean = when (this) {
        is ById -> id == task.id
        is ByName -> name.equals(task.name, ignoreCase)
    }
}

data class TaskReference(
    val site: TaskReferenceSite,
    val ref: TaskRef,
    /** Set on the exit task of a profile that has no enter task, which leaves it as the only one. */
    val profileOnlyTask: Boolean = false,
) {
    /**
     * True when the site cannot simply drop the reference. A profile must always have a task to
     * run (its enter task, or its exit task when it has no enter task), so clearing is not a legal
     * outcome there — it has to be reassigned or the delete has to be blocked.
     */
    val isRequired: Boolean get() = site is TaskReferenceSite.ProfileEnterTask || profileOnlyTask
}

object AutomationReferenceIndex {

    /**
     * Every task reference held by [profiles], [tasks], and [scenes], in a stable order.
     *
     * [taskIds] is every task id in the workspace. The engine reads a numeric `task.run` or
     * `task.stop` target as an id only when a task has that id, and as a name otherwise, so a task
     * named "2025" is found by its name. Without [taskIds], every number reads as an id.
     */
    fun build(
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
        globalFallbackTaskId: Long? = null,
        taskIds: Set<Long>? = null,
    ): List<TaskReference> = buildList {
        profiles.sortedBy { it.id }.forEach { profile ->
            val enterTaskId = profile.enterTaskIdOrNull
            enterTaskId?.let {
                add(
                    TaskReference(
                        TaskReferenceSite.ProfileEnterTask(profile.id, profile.name),
                        TaskRef.ById(it),
                    ),
                )
            }
            profile.exitTaskId?.let { exitId ->
                add(
                    TaskReference(
                        TaskReferenceSite.ProfileExitTask(profile.id, profile.name),
                        TaskRef.ById(exitId),
                        profileOnlyTask = enterTaskId == null,
                    ),
                )
            }
            profile.fallbackTaskId?.let { fallbackId ->
                add(
                    TaskReference(
                        TaskReferenceSite.ProfileFallbackTask(profile.id, profile.name),
                        TaskRef.ById(fallbackId),
                    ),
                )
            }
        }

        globalFallbackTaskId?.let { fallbackId ->
            add(TaskReference(TaskReferenceSite.GlobalFallbackTask, TaskRef.ById(fallbackId)))
        }

        val exactNames = tasks.mapTo(hashSetOf()) { it.name }
        tasks.sortedBy { it.id }.forEach { task ->
            task.actions.forEachIndexed { actionIndex, action ->
                actionTaskRefs(task, actionIndex, action.type, action.args, taskIds, exactNames).forEach(::add)
            }
        }

        scenes.sortedBy { it.id }.forEach { scene ->
            scene.elements.forEachIndexed { elementIndex, element ->
                element.tapTaskId?.let {
                    add(
                        TaskReference(
                            TaskReferenceSite.SceneTap(scene.id, scene.name, elementIndex),
                            TaskRef.ById(it),
                        ),
                    )
                }
                element.longPressTaskId?.let {
                    add(
                        TaskReference(
                            TaskReferenceSite.SceneLongPress(scene.id, scene.name, elementIndex),
                            TaskRef.ById(it),
                        ),
                    )
                }
            }
        }
    }

    /** The subset of [references] that resolves to [task], by id or by name. */
    fun referencesTo(references: List<TaskReference>, task: Task): List<TaskReference> =
        references.filter { it.ref.matches(task) }

    /** Convenience: every reference to [task] held anywhere in the workspace. */
    fun referencesTo(
        task: Task,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
        globalFallbackTaskId: Long? = null,
    ): List<TaskReference> = referencesTo(
        build(
            profiles,
            tasks.filterNot { it.id == task.id },
            scenes,
            globalFallbackTaskId,
            taskIds = tasks.mapTo(hashSetOf(task.id)) { it.id },
        ),
        task,
    )

    /** Every variable expression held by a profile, task, or scene, in stable source order. */
    fun buildVariableReferences(
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
    ): List<VariableReference> = buildList {
        profiles.sortedBy { it.id }.forEach { profile ->
            profile.contexts.forEachIndexed { contextIndex, context ->
                context.config.entries.sortedBy { it.key }.forEach { (key, value) ->
                    VariableReferenceScanner.scan(value).forEach { reference ->
                        add(
                            VariableReference(
                                site = VariableReferenceSite.ProfileContextBinding(
                                    ownerId = profile.id,
                                    ownerName = profile.name,
                                    projectId = profile.projectId,
                                    contextIndex = contextIndex,
                                    configKey = key,
                                ),
                                name = reference.name,
                                syntax = reference.syntax,
                                scope = reference.scope,
                            ),
                        )
                    }
                }
            }
        }

        tasks.sortedBy { it.id }.forEach { task ->
            task.actions.forEachIndexed { actionIndex, action ->
                action.args.entries.sortedBy { it.key }.forEach { (key, value) ->
                    VariableReferenceScanner.scan(value).forEach { reference ->
                        add(
                            VariableReference(
                                site = VariableReferenceSite.TaskActionArgument(
                                    ownerId = task.id,
                                    ownerName = task.name,
                                    projectId = task.projectId,
                                    actionIndex = actionIndex,
                                    argKey = key,
                                ),
                                name = reference.name,
                                syntax = reference.syntax,
                                scope = reference.scope,
                            ),
                        )
                    }
                }
                action.condition?.let { condition ->
                    VariableReferenceScanner.scan(condition).forEach { reference ->
                        add(
                            VariableReference(
                                site = VariableReferenceSite.TaskCondition(
                                    ownerId = task.id,
                                    ownerName = task.name,
                                    projectId = task.projectId,
                                    actionIndex = actionIndex,
                                ),
                                name = reference.name,
                                syntax = reference.syntax,
                                scope = reference.scope,
                            ),
                        )
                    }
                }
            }
        }

        scenes.sortedBy { it.id }.forEach { scene ->
            scene.elements.forEachIndexed { elementIndex, element ->
                element.config.entries.sortedBy { it.key }.forEach { (key, value) ->
                    VariableReferenceScanner.scan(value).forEach { reference ->
                        add(
                            VariableReference(
                                site = VariableReferenceSite.SceneBinding(
                                    ownerId = scene.id,
                                    ownerName = scene.name,
                                    projectId = scene.projectId,
                                    elementIndex = elementIndex,
                                    configKey = key,
                                ),
                                name = reference.name,
                                syntax = reference.syntax,
                                scope = reference.scope,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun referencesTo(
        references: List<VariableReference>,
        variable: Variable,
    ): List<VariableReference> {
        val normalizedName = VariableNamePolicy.normalizeForScope(variable.name, variable.isGlobal) ?: return emptyList()
        return references.filter { reference ->
            reference.site.projectId == variable.projectId &&
                reference.name == normalizedName &&
                when (reference.scope) {
                    VariableReferenceScope.GLOBAL -> variable.isGlobal
                    VariableReferenceScope.LOCAL -> !variable.isGlobal
                    VariableReferenceScope.INFERRED -> VariableNamePolicy.isGlobal(reference.name) == variable.isGlobal
                    VariableReferenceScope.EXTERNAL -> false
                }
        }
    }

    fun referencesTo(
        variable: Variable,
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
    ): List<VariableReference> = referencesTo(
        buildVariableReferences(profiles, tasks, scenes),
        variable,
    )

    /** Task references carried by a single action's arguments. */
    internal fun actionTaskRefs(
        owner: Task,
        actionIndex: Int,
        actionType: String,
        args: Map<String, String>,
        taskIds: Set<Long>? = null,
        /** Every task name, when known. The engine falls back to ignoring case only when none matches exactly. */
        exactNames: Set<String>? = null,
    ): List<TaskReference> = buildList {
        if (actionType == SUB_TASK_ACTION_ID || actionType == STOP_TASK_ACTION_ID) {
            val key = SUB_TASK_REF_KEYS.firstOrNull { args[it]?.isNotBlank() == true }
            val raw = key?.let { args[it]?.trim() }
            if (key != null && !raw.isNullOrBlank()) {
                val site = if (actionType == SUB_TASK_ACTION_ID) {
                    TaskReferenceSite.SubTaskRun(owner.id, owner.name, actionIndex, key)
                } else {
                    TaskReferenceSite.StopTaskStep(owner.id, owner.name, actionIndex, key)
                }
                add(
                    TaskReference(
                        site,
                        raw.toLongOrNull()
                            ?.takeIf { it > 0 && (taskIds == null || it in taskIds) }
                            ?.let(TaskRef::ById)
                            ?: TaskRef.ByName(raw, ignoreCase = exactNames == null || raw !in exactNames),
                    ),
                )
            }
        }

        for (button in 1..NotificationTaskBindings.BUTTON_COUNT) {
            val idKey = NotificationTaskBindings.taskIdKey(button)
            val nameKey = NotificationTaskBindings.legacyTaskNameKey(button)
            // As NotificationTaskBindings reads a button: the id key whenever it's there, even blank
            // or invalid, and the legacy name only without it, matched exactly.
            if (idKey in args) {
                val id = args[idKey]?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: continue
                add(
                    TaskReference(
                        TaskReferenceSite.NotificationButton(owner.id, owner.name, actionIndex, button, idKey),
                        TaskRef.ById(id),
                    ),
                )
                continue
            }
            val rawName = args[nameKey]?.trim()
            if (!rawName.isNullOrBlank()) {
                add(
                    TaskReference(
                        TaskReferenceSite.NotificationButton(owner.id, owner.name, actionIndex, button, nameKey),
                        TaskRef.ByName(rawName, ignoreCase = false),
                    ),
                )
            }
        }
    }
}
