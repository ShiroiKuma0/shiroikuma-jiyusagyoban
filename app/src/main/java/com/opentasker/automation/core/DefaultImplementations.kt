package com.opentasker.automation.core

import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.ConstraintConfig
import com.opentasker.automation.model.ConstraintGroup
import com.opentasker.automation.model.LogicalOperator
import com.opentasker.automation.model.TriggerConfig
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementations of core automation engine interfaces.
 */

// ============================================================================
// TRIGGER REGISTRY IMPLEMENTATION
// ============================================================================

class DefaultTriggerRegistry : TriggerRegistry {
    private val triggers = ConcurrentHashMap<String, TriggerDefinition>()

    override fun getTrigger(id: String): TriggerDefinition? = triggers[id]

    override fun getAllTriggers(): List<TriggerDefinition> = triggers.values.toList()

    override fun register(definition: TriggerDefinition) {
        triggers[definition.id] = definition
    }
}

// ============================================================================
// CONSTRAINT REGISTRY IMPLEMENTATION
// ============================================================================

class DefaultConstraintRegistry : ConstraintRegistry {
    private val constraints = ConcurrentHashMap<String, ConstraintDefinition>()

    override fun getConstraint(id: String): ConstraintDefinition? = constraints[id]

    override fun getAllConstraints(): List<ConstraintDefinition> = constraints.values.toList()

    override fun register(definition: ConstraintDefinition) {
        constraints[definition.id] = definition
    }
}

// ============================================================================
// ACTION REGISTRY IMPLEMENTATION
// ============================================================================

class DefaultActionRegistry : ActionRegistry {
    private val actions = ConcurrentHashMap<String, ActionDefinition>()

    override fun getAction(id: String): ActionDefinition? = actions[id]

    override fun getAllActions(): List<ActionDefinition> = actions.values.toList()

    override fun register(definition: ActionDefinition) {
        actions[definition.id] = definition
    }
}

// ============================================================================
// TRIGGER MATCHER IMPLEMENTATION
// ============================================================================

class DefaultTriggerMatcher : TriggerMatcher {
    override fun matches(
        triggers: List<TriggerConfig>,
        event: AutomationEvent,
        registry: TriggerRegistry
    ): Boolean {
        // Any trigger matching is sufficient (OR logic)
        return triggers.any { triggerConfig ->
            registry.getTrigger(triggerConfig.type)?.matches(event, triggerConfig) ?: false
        }
    }
}

// ============================================================================
// CONSTRAINT EVALUATOR IMPLEMENTATION
// ============================================================================

class DefaultConstraintEvaluator : ConstraintEvaluator {
    override suspend fun evaluate(
        group: ConstraintGroup,
        registry: ConstraintRegistry
    ): Boolean {
        return evaluateGroupRecursive(group, registry)
    }

    private suspend fun evaluateGroupRecursive(
        group: ConstraintGroup,
        registry: ConstraintRegistry
    ): Boolean {
        // Evaluate all constraints in this group
        val constraintResults = group.constraints.map { constraint ->
            registry.getConstraint(constraint.type)?.evaluate(constraint) ?: false
        }

        // Recursively evaluate subgroups
        val subgroupResults = group.subgroups.map { subgroup ->
            evaluateGroupRecursive(subgroup, registry)
        }

        // Combine all results
        val allResults = constraintResults + subgroupResults

        // Apply operator
        return when (group.operator) {
            LogicalOperator.AND -> allResults.all { it }  // All must be true
            LogicalOperator.OR -> allResults.any { it }   // At least one must be true
        }
    }
}

// ============================================================================
// ACTION EXECUTOR IMPLEMENTATION
// ============================================================================

class DefaultActionExecutor : ActionExecutor {
    override suspend fun execute(
        actions: List<ActionConfig>,
        registry: ActionRegistry,
        parallel: Boolean
    ): List<Pair<String, ActionResult>> {
        return if (parallel) {
            executeParallel(actions, registry)
        } else {
            executeSequential(actions, registry)
        }
    }

    private suspend fun executeParallel(
        actions: List<ActionConfig>,
        registry: ActionRegistry
    ): List<Pair<String, ActionResult>> = coroutineScope {
        val deferreds: List<Deferred<Pair<String, ActionResult>>> = actions.map { action ->
            async {
                val result = executeAction(action, registry)
                action.id to result
            }
        }
        deferreds.awaitAll()
    }

    private suspend fun executeSequential(
        actions: List<ActionConfig>,
        registry: ActionRegistry
    ): List<Pair<String, ActionResult>> {
        return actions.map { action ->
            val result = executeAction(action, registry)
            action.id to result
        }
    }

    private suspend fun executeAction(
        action: ActionConfig,
        registry: ActionRegistry
    ): ActionResult {
        return try {
            val startTime = System.currentTimeMillis()
            val definition = registry.getAction(action.type)
                ?: return ActionResult(
                    success = false,
                    message = "Unknown action type: ${action.type}",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )

            val result = definition.execute(action)
            result
        } catch (e: Exception) {
            ActionResult(
                success = false,
                message = "Action execution failed: ${e.message}",
                executionTimeMs = 0,
                stackTrace = e.stackTraceToString()
            )
        }
    }
}
