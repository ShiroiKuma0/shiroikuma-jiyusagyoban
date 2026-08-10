package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.platform.AudioRuntimeEligibility
import java.util.Collections

/**
 * Runtime context handed to every Action.run() invocation.
 * Provides the application context, the active variable store, and a logger.
 */
class ActionContext(
    val app: Context,
    val variables: VariableStore,
    val eventVariables: Map<String, String> = emptyMap(),
    val audioEligibility: AudioRuntimeEligibility = AudioRuntimeEligibility(),
    val sensitiveArgumentNames: Set<String> = emptySet(),
    val logger: (String) -> Unit = {},
)

fun ActionContext.forAction(sensitiveArgumentNames: Set<String>): ActionContext {
    if (sensitiveArgumentNames.isEmpty()) return this
    return ActionContext(
        app = app,
        variables = variables,
        eventVariables = eventVariables,
        audioEligibility = audioEligibility,
        logger = { logger(SECRET_DERIVED_ACTION_LOG) },
        sensitiveArgumentNames = sensitiveArgumentNames,
    )
}

fun ActionContext.isArgumentSensitive(name: String): Boolean = name in sensitiveArgumentNames

internal const val SECRET_DERIVED_ACTION_LOG = "<redacted: action output depends on a secret>"

/** Result of executing a single Action. */
sealed class ActionResult {
    data object Success : ActionResult()
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        /** Child task failures retain their original structured location when they bubble up. */
        val structuredError: StructuredTaskError? = null,
    ) : ActionResult()
    data object Skip : ActionResult()
}

/**
 * Atomic unit of automation work. Implementations live in [com.opentasker.core.actions]
 * for built-ins; Locale-compatible plugins are invoked by host actions in that package.
 */
interface Action {
    val id: String                 // stable, e.g. "wifi.toggle"
    val category: ActionCategory
    /** Whether the engine may repeat this action after a transient failure. */
    val retrySafety: ActionRetrySafety get() = ActionRetrySafety.NEVER

    /** Allows an action whose safety depends on its arguments to refine its classification. */
    fun retrySafetyFor(args: Map<String, String>): ActionRetrySafety = retrySafety
    suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult
}

enum class ActionRetrySafety {
    NEVER,
    IDEMPOTENT,
}

enum class ActionCategory {
    SETTINGS, NOTIFICATION, FILE, NET, MEDIA, APP, VARIABLE, FLOW, SYSTEM, PLUGIN
}

/** Registry of all known Action implementations, keyed by [Action.id]. */
object ActionRegistry {
    private val byId = Collections.synchronizedMap(mutableMapOf<String, Action>())

    fun register(action: Action) { byId[action.id] = action }
    fun get(id: String): Action? = byId[id]
    fun all(): Collection<Action> = byId.values.toList()
    fun allIds(): Set<String> = byId.keys.toSet()
}
