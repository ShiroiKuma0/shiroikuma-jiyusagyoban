package com.opentasker.core.engine

import android.content.Context

/**
 * Runtime context handed to every Action.run() invocation.
 * Provides the application context, the active variable store, and a logger.
 */
class ActionContext(
    val app: Context,
    val variables: VariableStore,
    val logger: (String) -> Unit = {},
)

/** Result of executing a single Action. */
sealed class ActionResult {
    data object Success : ActionResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ActionResult()
    data object Skip : ActionResult()
}

/**
 * Atomic unit of automation work. Implementations live in [com.opentasker.core.actions]
 * for built-ins; plugins implement the same interface via the AIDL bridge.
 */
interface Action {
    val id: String                 // stable, e.g. "wifi.toggle"
    val category: ActionCategory
    suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult
}

enum class ActionCategory {
    SETTINGS, NOTIFICATION, FILE, NET, MEDIA, APP, VARIABLE, FLOW, SYSTEM, PLUGIN
}

/** Registry of all known Action implementations, keyed by [Action.id]. */
object ActionRegistry {
    private val byId = mutableMapOf<String, Action>()

    fun register(action: Action) { byId[action.id] = action }
    fun get(id: String): Action? = byId[id]
    fun all(): Collection<Action> = byId.values
}
