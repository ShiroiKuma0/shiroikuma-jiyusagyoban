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
    val requiresRiskAcknowledgement: Boolean = false,
    // Upstream 0.2.80 nested ALL/ANY/NOT context grouping. The fork does not ship the authoring UI
    // for it, so this stays null and every read path takes upstream's own legacy branch: contexts are
    // combined with implicit AND, exactly as the fork has always evaluated them.
    val contextExpression: ContextExpressionNode? = null,
    /** Higher-priority profiles win deterministic arbitration when multiple profiles match. */
    val priority: Int = 0,
    /** Stable time in seconds required before either an activation or deactivation is accepted. */
    val gracePeriodSec: Int = 0,
    val lifetime: ProfileLifetime = ProfileLifetime.NEVER,
    /** Absolute epoch-millis expiry for [ProfileLifetime.UNTIL_DATE]. */
    val expiresAtMs: Long? = null,
    /** Internal persisted state for one-shot profiles; it is never user-authored directly. */
    val lifetimeConsumed: Boolean = false,
    /** Optional override for the number of active executions admitted for this profile. */
    val maxActiveExecutions: Int? = null,
    /** Optional override for starts admitted during the engine burst window. */
    val burstLimit: Int? = null,
    /** Whether admission overflow should leave a replayable run-log entry. */
    val overflowPolicy: ProfileOverflowPolicy = ProfileOverflowPolicy.LOG,
    /** Optional task to run when this profile's task fails without a lexical flow.catch. */
    val fallbackTaskId: Long? = null,
)

@Serializable
enum class ProfileLifetime {
    NEVER,
    UNTIL_DATE,
    ONCE,
}

@Serializable
enum class ProfileOverflowPolicy {
    LOG,
    SILENT,
}

@Serializable
enum class AutomationMode {
    SINGLE,
    RESTART,
    QUEUED,
    PARALLEL,
}
