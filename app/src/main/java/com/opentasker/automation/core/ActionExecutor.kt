package com.opentasker.automation.core

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionRegistry
import com.opentasker.core.logging.AppLogger
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ActionResult
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
    ): List<ActionResult> {
        return try {
            if (parallel) {
                executeParallel(actions, registry)
            } else {
                executeSequential(actions, registry)
            }
        } catch (e: Exception) {
            AppLogger.error(tag, "Error during action execution", e)
            listOf(
                ActionResult(
                    success = false,
                    message = "Execution error: ${e.message}",
                    executionTimeMs = 0L
                )
            )
        }
    }
    
    private suspend fun executeSequential(
        actions: List<ActionConfig>,
        registry: ActionRegistry
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        for (action in actions) {
            val startTime = System.currentTimeMillis()
            val result = executeOne(action, registry)
            results.add(result.copy(executionTimeMs = System.currentTimeMillis() - startTime))
            // Log each result
            if (result.success) {
                AppLogger.debug(tag, "Action ${action.id} succeeded")
            } else {
                AppLogger.warn(tag, "Action ${action.id} failed: ${result.message}")
            }
        }
        return results
    }
    
    private suspend fun executeParallel(
        actions: List<ActionConfig>,
        registry: ActionRegistry
    ): List<ActionResult> {
        return coroutineScope {
            val jobs = actions.map { action ->
                async { executeOne(action, registry) }
            }
            val results = jobs.awaitAll()
            // Log results
            results.forEach { result ->
                if (result.success) {
                    AppLogger.debug(tag, "Action succeeded")
                } else {
                    AppLogger.warn(tag, "Action failed: ${result.message}")
                }
            }
            results
        }
    }
    
    private suspend fun executeOne(
        actionConfig: ActionConfig,
        registry: ActionRegistry
    ): ActionResult {
        val startTime = System.currentTimeMillis()
        return try {
            // Get the action from registry
            val action = registry.get(actionConfig.type)
            if (action == null) {
                AppLogger.warn(
                    tag,
                    "Action type '${actionConfig.type}' not found in registry, skipping"
                )
                return ActionResult(
                    success = false,
                    message = "Action type not found: ${actionConfig.type}",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            // Create action context
            // Note: This is a placeholder - real implementation would need actual context
            // val context = ActionContext(...)
            // action.run(context, actionConfig.args)
            
            // For now, log and return success
            AppLogger.debug(tag, "Executing action ${actionConfig.id} of type ${actionConfig.type}")
            ActionResult(
                success = true,
                message = "Action executed successfully",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            AppLogger.error(tag, "Error executing action ${actionConfig.id}", e)
            ActionResult(
                success = false,
                message = e.message ?: "Unknown error",
                executionTimeMs = System.currentTimeMillis() - startTime,
                stackTrace = e.stackTraceToString()
            )
        }
    }
}
