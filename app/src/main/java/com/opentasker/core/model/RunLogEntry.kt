package com.opentasker.core.model

import kotlinx.serialization.Serializable

/** A log entry from a task run. */
@Serializable
data class RunLogEntry(
    val id: Long = 0,
    val taskId: Long,
    val taskName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long,
    val success: Boolean,
    val message: String = "",
    /** Stable typed trigger key (see [com.opentasker.core.engine.RunLogSource]); null for legacy rows. */
    val source: String? = null,
    /** Human-readable trigger identifier (e.g. profile name or tile slot); null when not applicable. */
    val sourceLabel: String? = null,
    /** Stable command id for correlating a log row with its execution envelope. */
    val executionId: String? = null,
    /** Original command id when this row was created by replaying a held execution. */
    val replayOf: String? = null,
    /** True when admission rejected the command before any task action started. */
    val held: Boolean = false,
    /** Bounded, redacted trigger data used to offer a safe manual replay. */
    val heldPayload: String? = null,
    /** The admission policy/reason that placed this execution on hold. */
    val heldPolicy: String? = null,
    /** User pin that exempts this row from retention pruning. */
    val starred: Boolean = false,
)
