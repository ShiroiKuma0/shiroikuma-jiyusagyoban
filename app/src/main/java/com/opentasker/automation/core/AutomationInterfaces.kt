package com.opentasker.automation.core

import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult
import com.opentasker.automation.model.ConstraintConfig
import com.opentasker.automation.model.ConstraintGroup
import com.opentasker.automation.model.TriggerConfig

/**
 * Base interfaces for the automation engine.
 * Inspired by orkestr's modular design.
 */

// ============================================================================
// TRIGGER DEFINITION - Base interface for all triggers
// ============================================================================

interface TriggerDefinition {
    /** Unique identifier for this trigger type (e.g., "time", "geofence", "wifi") */
    val id: String

    /** User-friendly display name */
    val displayName: String

    /**
     * Check if the given event matches this trigger's configuration.
     * @param event The automation event to check
     * @param config The user's trigger configuration
     * @return true if the event matches this trigger
     */
    fun matches(event: AutomationEvent, config: TriggerConfig): Boolean
}

/**
 * Registry of all available trigger types.
 * In future, this can be replaced with KSP-generated registry for zero-boilerplate extensibility.
 */
interface TriggerRegistry {
    fun getTrigger(id: String): TriggerDefinition?
    fun getAllTriggers(): List<TriggerDefinition>
    fun register(definition: TriggerDefinition)
}

// ============================================================================
// CONSTRAINT DEFINITION - Base interface for all constraints
// ============================================================================

interface ConstraintDefinition {
    val id: String
    val displayName: String

    /**
     * Evaluate if this constraint is currently satisfied.
     * @param config The user's constraint configuration
     * @return true if constraint is satisfied, false otherwise
     */
    suspend fun evaluate(config: ConstraintConfig): Boolean
}

interface ConstraintRegistry {
    fun getConstraint(id: String): ConstraintDefinition?
    fun getAllConstraints(): List<ConstraintDefinition>
    fun register(definition: ConstraintDefinition)
}

// ============================================================================
// ACTION DEFINITION - Base interface for all actions
// ============================================================================

interface ActionDefinition {
    val id: String
    val displayName: String

    /**
     * Execute this action.
     * @param config The user's action configuration
     * @return Result of the action execution
     */
    suspend fun execute(config: ActionConfig): ActionResult
}

interface ActionRegistry {
    fun getAction(id: String): ActionDefinition?
    fun getAllActions(): List<ActionDefinition>
    fun register(definition: ActionDefinition)
}

// ============================================================================
// CONSTRAINT EVALUATOR - Evaluates constraint groups with AND/OR logic
// ============================================================================

interface ConstraintEvaluator {
    /**
     * Evaluate a constraint group (with nested AND/OR logic).
     * @param group The constraint group to evaluate
     * @param registry The constraint registry to look up definitions
     * @return true if group is satisfied, false otherwise
     */
    suspend fun evaluate(group: ConstraintGroup, registry: ConstraintRegistry): Boolean
}

// ============================================================================
// TRIGGER MATCHER - Matches events to rule triggers
// ============================================================================

interface TriggerMatcher {
    /**
     * Check if a rule's triggers are satisfied by this event.
     * Currently: any trigger can match (OR logic).
     * @param triggers List of trigger configs for a rule
     * @param event The incoming automation event
     * @param registry The trigger registry
     * @return true if any trigger matches
     */
    fun matches(triggers: List<TriggerConfig>, event: AutomationEvent, registry: TriggerRegistry): Boolean
}

// ============================================================================
// ACTION EXECUTOR - Executes actions with parallel/sequential modes
// ============================================================================

interface ActionExecutor {
    /**
     * Execute a list of actions.
     * @param actions List of action configs
     * @param registry Action registry
     * @param parallel true for parallel execution, false for sequential
     * @return List of results, one per action
     */
    suspend fun execute(
        actions: List<ActionConfig>,
        registry: ActionRegistry,
        parallel: Boolean = true
    ): List<Pair<String, ActionResult>> // action ID -> result
}
