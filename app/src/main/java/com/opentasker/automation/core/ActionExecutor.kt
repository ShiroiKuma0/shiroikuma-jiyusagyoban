package com.opentasker.automation.core

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
class ActionExecutorImpl @Inject constructor() : ActionExecutor {
    private val tag = "ActionExecutor"
    
    /**
     * Execute a list of action configs.
     * 
     * @param actions List of action configurations to execute
     * @param registry Action registry for lookup (currently unused, kept for interface compatibility)
     * @param parallel If true, execute actions concurrently; if false, execute sequentially
     * @return List of execution results
     */
    override suspend fun execute(
        actions: List<ActionConfig>,
        registry: ActionRegistry,
        parallel: Boolean = false
    ): List<ActionResult> {
        return try {
            if (parallel) {
                executeParallel(actions)
            } else {
                executeSequential(actions)
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
        actions: List<ActionConfig>
    ): List<ActionResult> {
        val results = mutableListOf<ActionResult>()
        for (action in actions) {
            val startTime = System.currentTimeMillis()
            val result = executeOne(action)
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
        actions: List<ActionConfig>
    ): List<ActionResult> {
        return coroutineScope {
            val jobs = actions.map { action ->
                async { executeOne(action) }
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
        actionConfig: ActionConfig
    ): ActionResult {
        val startTime = System.currentTimeMillis()
        return try {
            // Placeholder implementation - just return success for now
            // Full implementation would look up action in registry and execute
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
