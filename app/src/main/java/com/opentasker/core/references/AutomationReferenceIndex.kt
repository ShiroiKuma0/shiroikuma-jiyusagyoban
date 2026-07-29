package com.opentasker.core.references

import com.opentasker.core.actions.NotificationTaskBindings
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_REF_KEYS
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task

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

    enum class OwnerKind { PROFILE, TASK, SCENE }

    data class ProfileEnterTask(override val ownerId: Long, override val ownerName: String) : TaskReferenceSite {
        override val ownerKind = OwnerKind.PROFILE
    }

    data class ProfileExitTask(override val ownerId: Long, override val ownerName: String) : TaskReferenceSite {
        override val ownerKind = OwnerKind.PROFILE
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

/** How a site names the task it points at. */
sealed interface TaskRef {
    data class ById(val id: Long) : TaskRef
    data class ByName(val name: String) : TaskRef

    fun matches(task: Task): Boolean = when (this) {
        is ById -> id == task.id
        is ByName -> name.equals(task.name, ignoreCase = true)
    }
}

data class TaskReference(val site: TaskReferenceSite, val ref: TaskRef) {
    /**
     * True when the site cannot simply drop the reference. A profile must always have an enter
     * task, so clearing is not a legal outcome there — it has to be reassigned or the delete has
     * to be blocked.
     */
    val isRequired: Boolean get() = site is TaskReferenceSite.ProfileEnterTask
}

object AutomationReferenceIndex {

    /** Every task reference held by [profiles], [tasks], and [scenes], in a stable order. */
    fun build(
        profiles: List<Profile> = emptyList(),
        tasks: List<Task> = emptyList(),
        scenes: List<Scene> = emptyList(),
    ): List<TaskReference> = buildList {
        profiles.sortedBy { it.id }.forEach { profile ->
            add(
                TaskReference(
                    TaskReferenceSite.ProfileEnterTask(profile.id, profile.name),
                    TaskRef.ById(profile.enterTaskId),
                ),
            )
            profile.exitTaskId?.let { exitId ->
                add(
                    TaskReference(
                        TaskReferenceSite.ProfileExitTask(profile.id, profile.name),
                        TaskRef.ById(exitId),
                    ),
                )
            }
        }

        tasks.sortedBy { it.id }.forEach { task ->
            task.actions.forEachIndexed { actionIndex, action ->
                actionTaskRefs(task, actionIndex, action.type, action.args).forEach(::add)
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
    ): List<TaskReference> = referencesTo(
        build(profiles, tasks.filterNot { it.id == task.id }, scenes),
        task,
    )

    /** Task references carried by a single action's arguments. */
    internal fun actionTaskRefs(
        owner: Task,
        actionIndex: Int,
        actionType: String,
        args: Map<String, String>,
    ): List<TaskReference> = buildList {
        if (actionType == SUB_TASK_ACTION_ID) {
            val key = SUB_TASK_REF_KEYS.firstOrNull { args[it]?.isNotBlank() == true }
            val raw = key?.let { args[it]?.trim() }
            if (key != null && !raw.isNullOrBlank()) {
                add(
                    TaskReference(
                        TaskReferenceSite.SubTaskRun(owner.id, owner.name, actionIndex, key),
                        raw.toLongOrNull()?.takeIf { it > 0 }?.let(TaskRef::ById) ?: TaskRef.ByName(raw),
                    ),
                )
            }
        }

        for (button in 1..NotificationTaskBindings.BUTTON_COUNT) {
            val idKey = NotificationTaskBindings.taskIdKey(button)
            val nameKey = NotificationTaskBindings.legacyTaskNameKey(button)
            val rawId = args[idKey]?.trim()
            if (!rawId.isNullOrBlank()) {
                val id = rawId.toLongOrNull()?.takeIf { it > 0 } ?: continue
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
                        TaskRef.ByName(rawName),
                    ),
                )
            }
        }
    }
}
