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
)
