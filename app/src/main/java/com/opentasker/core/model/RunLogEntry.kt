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
)
