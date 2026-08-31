package com.opentasker.core.engine

import android.content.Context
import com.opentasker.core.actions.ActionCatalog
import com.opentasker.core.actions.ActionDefinition
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
    /** Read-only named parameters passed into a called task; referenced as `{{ param.name }}`. */
    val parameters: Map<String, String> = emptyMap(),
    /** Named return values this task exposes to a caller; populated by the Return Values action. */
    val returns: MutableMap<String, String> = mutableMapOf(),
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
        parameters = parameters,
        returns = returns,
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
    /** The shared catalogue entry for a built-in implementation, when one exists. */
    val definition: ActionDefinition?
        get() = null
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

    fun register(action: Action) {
        val declaration = action.definition ?: ActionCatalog.get(action.id)
        if (action.definition != null) {
            require(ActionCatalog.get(action.id) === action.definition) {
                "Action ${action.id} does not use its canonical ActionCatalog declaration"
            }
        }
        // No category/retry-safety comparison here: since every built-in extends DeclaredAction,
        // both sides of such a check read from the same ActionDefinition, so it could only ever
        // compare the declaration to itself. The invariant that matters is enforced where the two
        // are declared independently - ActionCatalog against the editor metadata registry.
        byId[declaration?.id ?: action.id] = action
    }

    fun registerAll() {
        ActionCatalog.all.map { it.factory() }.forEach(::register)
    }
    fun clear() = byId.clear()
    fun get(id: String): Action? = byId[id]
    fun all(): Collection<Action> = byId.values.toList()
    fun allIds(): Set<String> = byId.keys.toSet()
}

/**
 * Base class for built-in actions. Requiring the canonical definition in the constructor makes
 * omitting an action declaration a compile-time choice instead of another hand-maintained field
 * triplet that can drift from the runtime registry.
 */
abstract class DeclaredAction(
    final override val definition: ActionDefinition,
) : Action {
    final override val id: String
        get() = definition.id
    final override val category: ActionCategory
        get() = definition.category
    final override val retrySafety: ActionRetrySafety
        get() = definition.retrySafety
}
