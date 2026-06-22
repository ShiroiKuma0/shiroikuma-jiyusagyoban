package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Profile binds one or more Contexts to a Task.
 * The profile is "active" while ALL of its contexts match.
 * Activation runs the enter task; deactivation runs the exit task (if set). The task is resolved by
 * [enterTaskName]/[exitTaskName] FIRST, with [enterTaskId]/[exitTaskId] as the fallback.
 */
@Serializable
data class Profile(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val contexts: List<ContextSpec> = emptyList(),
    val enterTaskId: Long,
    val exitTaskId: Long? = null,
    val cooldownSec: Int = 0,
    val automationMode: AutomationMode = AutomationMode.SINGLE,
    val projectId: Long? = null,            // null = Unfiled
    val position: Int = 0,                  // manual sort order within its tab
    // Task link by NAME — resolved FIRST at run time, with enterTaskId/exitTaskId as the fallback. This
    // survives bundle re-imports that re-id a task (which otherwise orphan the id link → "Missing task #N").
    val enterTaskName: String = "",
    val exitTaskName: String = "",
    val group: String? = null,              // upstream's profile-group tag; our project grouping is canonical (kept for source compatibility)
)

@Serializable
enum class AutomationMode {
    SINGLE,
    RESTART,
    QUEUED,
    PARALLEL,
}
