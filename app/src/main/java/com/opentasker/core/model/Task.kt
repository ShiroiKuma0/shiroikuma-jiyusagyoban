package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Task is an ordered list of Actions executed top-to-bottom with flow control.
 *
 * collisionMode controls what happens if a task is asked to run while a previous
 * invocation is still in flight (matches Tasker's behavior: Abort New / Abort Existing /
 * Run Both / Wait).
 */
@Serializable
data class Task(
    val id: Long = 0,
    val name: String,
    val priority: Int = 5,
    val collisionMode: CollisionMode = CollisionMode.ABORT_NEW,
    val actions: List<ActionSpec> = emptyList(),
)

@Serializable
enum class CollisionMode { ABORT_NEW, ABORT_EXISTING, RUN_BOTH, WAIT }

@Serializable
data class ActionSpec(
    val id: Long = 0,
    val type: String,                       // stable action id, e.g. "wifi.toggle"
    val label: String? = null,
    val args: Map<String, String> = emptyMap(),
    val continueOnError: Boolean = false,
    val condition: String? = null,          // optional %var-based condition
)
