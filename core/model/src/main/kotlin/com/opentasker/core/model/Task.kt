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
    val projectId: Long? = null,            // null = Unfiled
    val position: Int = 0,                  // manual sort order within its tab
    val iconPath: String? = null,           // absolute path to a saved PNG used as the shortcut/list icon
    val freezeBubble: Boolean = false,      // running this task queues a "freeze" bubble for the app it launches
    /**
     * Off means the task does not run — from a profile, a widget, `task.run` or the bridge alike.
     *
     * A disabled task is KEPT, whole: its actions, its note, its group, its position and its id. It
     * is the answer to "I want this out of the way for a while", which was previously only
     * expressible by deleting it and hoping the mirror was current enough to put it back. It is
     * deliberately not a second kind of profile-enable: a profile that is on and points at a
     * disabled task fires and finds nothing to do, which is reported rather than silent.
     */
    val enabled: Boolean = true,
    val iconData: String? = null,           // bundle-only: base64 PNG so the icon survives a cross-device import; never persisted
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
    /**
     * Off means the interpreter walks straight past this action.
     *
     * Distinct from [condition], which is evaluated every run and belongs to the task's logic; this
     * is an editing state that belongs to whoever is working on the task. A disabled action keeps
     * its arguments, its label and its place in the order, so switching it back on restores exactly
     * what was there — which a delete-and-retype does not.
     *
     * **Any action can be disabled, flow control included** (白い熊, 2026-09-10). It is deliberately
     * not block-aware: switching off a `flow.if` removes the test and lets its body run rather than
     * removing the block, and switching off a `flow.else` lets both branches run. Those are odd
     * tasks and they are the author's to write — *"the propriety of the full task is then on me."*
     *
     * The engine's guarantee is narrower and absolute: it cannot hang or corrupt.
     * [com.opentasker.core.engine.FlowStructure] analyses every action including disabled ones, so
     * the `if`/`endif` pairing never shifts underneath; an `endfor` reached without its loop fails
     * with a named error rather than looping; and the flow step budget bounds the rest.
     */
    val enabled: Boolean = true,
)
