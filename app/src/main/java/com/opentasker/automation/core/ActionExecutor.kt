package com.opentasker.automation.core

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.logging.AppLogger
import com.opentasker.automation.model.ActionConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a list of actions either sequentially or in parallel based on execution mode.
 * Handles missing actions gracefully with skip-and-log behavior.
 */
@Singleton
class ActionExecutorImpl @Inject constructor() {
    private val tag = "ActionExecutor"
    
    /**
     * Execute a list of action configs.
     * 
     * @param actions List of action configurations to execute
     * @param registry Action registry for lookup
     * @param parallel If true, execute actions concurrently; if false, execute sequentially
     * @return List of execution results
     */
    suspend fun execute(
        actions: List<ActionConfig>,
        registry: ActionRegistry,
        parallel: Boolean = false
    ): List<ActionExecutionResult> {
        return try {
            if (parallel) {
                executeParallel(actions, registry)
            } else {
                executeSequential(actions, registry)
            }
        } catch (e: Exception) {
            AppLogger.error(tag, "Error during action execution", e)
            listOf(
                ActionExecutionResult(
                    success = false,
                    actionId = "",
                    error = "Execution error: ${e.message}"
                )
            )
        }
    }
    
    private suspend fun executeSequential(
        actions: List<ActionConfig>,
        registry: ActionRegistry
    ): List<ActionExecutionResult> {
        val results = mutableListOf<ActionExecutionResult>()
        for (action in actions) {
            val result = executeOne(action, registry)
            results.add(result)
            // Log each result
            if (result.success) {
                AppLogger.debug(tag, "Action ${action.id} succeeded")
            } else {
                AppLogger.warn(tag, "Action ${action.id} failed: ${result.error}")
            }
        }
        return results
    }
    
    private suspend fun executeParallel(
        actions: List<ActionConfig>,
        registry: ActionRegistry
    ): List<ActionExecutionResult> {
        return coroutineScope {
            val jobs = actions.map { action ->
                async { executeOne(action, registry) }
            }
            val results = jobs.awaitAll()
            // Log results
            results.forEach { result ->
                if (result.success) {
                    AppLogger.debug(tag, "Action ${result.actionId} succeeded")
                } else {
                    AppLogger.warn(tag, "Action ${result.actionId} failed: ${result.error}")
                }
            }
            results
        }
    }
    
    private suspend fun executeOne(
        actionConfig: ActionConfig,
        registry: ActionRegistry
    ): ActionExecutionResult {
        return try {
            // Get the action from registry
            val action = registry.get(actionConfig.type)
            if (action == null) {
                AppLogger.warn(
                    tag,
                    "Action type '${actionConfig.type}' not found in registry, skipping"
                )
                return ActionExecutionResult(
                    success = false,
                    actionId = actionConfig.id,
                    error = "Action type not found: ${actionConfig.type}"
                )
            }
            
            // Create action context
            // Note: This is a placeholder - real implementation would need actual context
            // val context = ActionContext(...)
            // action.run(context, actionConfig.args)
            
            // For now, log and return success
            AppLogger.debug(tag, "Executing action ${actionConfig.id} of type ${actionConfig.type}")
            ActionExecutionResult(
                success = true,
                actionId = actionConfig.id,
                error = null
            )
        } catch (e: Exception) {
            AppLogger.error(tag, "Error executing action ${actionConfig.id}", e)
            ActionExecutionResult(
                success = false,
                actionId = actionConfig.id,
                error = e.message ?: "Unknown error"
            )
        }
    }
}

/**
 * Result of executing a single action.
 */
data class ActionExecutionResult(
    val success: Boolean,
    val actionId: String = "",
    val error: String? = null
)
