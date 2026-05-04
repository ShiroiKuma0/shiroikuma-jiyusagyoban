package com.opentasker.automation.core

import android.content.Context
import android.util.Log
import com.opentasker.automation.data.repository.AutomationRuleRepository
import com.opentasker.automation.data.repository.ExecutionLogRepository
import com.opentasker.automation.model.AutomationEvent
import com.opentasker.automation.model.AutomationRule
import com.opentasker.automation.model.ExecutionLog
import com.opentasker.automation.model.ExecutionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core automation engine that orchestrates the entire event→trigger→constraint→action pipeline.
 * 
 * This is the heart of OpenTasker. It:
 * 1. Receives events from BroadcastReceivers
 * 2. Finds matching rules via TriggerMatcher
 * 3. Evaluates constraints via ConstraintEvaluator
 * 4. Executes actions via ActionExecutor
 * 5. Logs everything to ExecutionLog
 */
@Singleton
class AutomationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ruleRepository: AutomationRuleRepository,
    private val logRepository: ExecutionLogRepository,
    private val triggerRegistry: TriggerRegistry,
    private val constraintRegistry: ConstraintRegistry,
    private val actionRegistry: ActionRegistry,
    private val triggerMatcher: TriggerMatcher,
    private val constraintEvaluator: ConstraintEvaluator,
    private val actionExecutor: ActionExecutor
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    
    // Cache for active profiles (in-memory, not persisted)
    private val activeProfiles = ConcurrentHashMap<Long, Boolean>()

    /**
     * Process an incoming automation event.
     * This is the main entry point for BroadcastReceivers.
     */
    fun onEvent(event: AutomationEvent) {
        scope.launch {
            try {
                log("Event received: $event")

                // Load all enabled rules that belong to active profiles
                val rules = ruleRepository.getAllEnabled()
                    .filter { activeProfiles.getOrDefault(it.profileId, false) }

                if (rules.isEmpty()) {
                    log("No active profiles or rules found")
                    return@launch
                }

                // Process each rule
                for (rule in rules) {
                    processRule(rule, event)
                }
            } catch (e: Exception) {
                log("Error processing event", e)
            }
        }
    }

    /**
     * Process a single rule against an event.
     */
    private suspend fun processRule(rule: AutomationRule, event: AutomationEvent) {
        try {
            // Step 1: Check if any trigger matches
            if (!triggerMatcher.matches(rule.triggers, event, triggerRegistry)) {
                log("Rule ${rule.id} (${rule.name}): No trigger matched")
                return
            }
            log("Rule ${rule.id} (${rule.name}): Trigger matched ✓")

            // Step 2: Evaluate constraints
            for (constraintGroup in rule.constraintGroups) {
                val constraintsMet = constraintEvaluator.evaluate(constraintGroup, constraintRegistry)
                if (!constraintsMet) {
                    log("Rule ${rule.id} (${rule.name}): Constraints not met")
                    return
                }
            }
            log("Rule ${rule.id} (${rule.name}): All constraints satisfied ✓")

            // Step 3: Execute actions
            val startTime = System.currentTimeMillis()
            val actionResults = actionExecutor.execute(
                rule.actions,
                actionRegistry,
                parallel = rule.executionMode.name.lowercase() == "parallel"
            )
            val executionTimeMs = System.currentTimeMillis() - startTime

            // Step 4: Log execution
            val status = if (actionResults.all { it.success }) ExecutionStatus.SUCCESS else ExecutionStatus.FAILURE
            val successCount = actionResults.count { it.success }
            val executionLog = ExecutionLog(
                ruleId = rule.id,
                ruleName = rule.name,
                triggerId = null,
                triggerType = event::class.simpleName,
                timestamp = System.currentTimeMillis(),
                status = status,
                message = "Executed $successCount/${rule.actions.size} actions",
                executionTimeMs = executionTimeMs,
                actionResults = actionResults.mapIndexed { idx, result ->
                    rule.actions.getOrNull(idx)?.id ?: "unknown-$idx" to result
                }
            )
            logRepository.insert(executionLog)

            log("Rule ${rule.id} (${rule.name}): Executed successfully (${executionTimeMs}ms)")
        } catch (e: Exception) {
            log("Error processing rule ${rule.id}", e)

            // Log the failure
            val executionLog = ExecutionLog(
                ruleId = rule.id,
                profileId = rule.profileId,
                eventType = event::class.simpleName ?: "Unknown",
                triggered = false,
                executionTimeMs = 0,
                actionCount = 0,
                successCount = 0,
                details = "Error: ${e.message}"
            )
            logRepository.insert(executionLog)
        }
    }

    /**
     * Enable/disable a profile.
     */
    fun setProfileActive(profileId: Long, active: Boolean) {
        activeProfiles[profileId] = active
        log("Profile $profileId: ${if (active) "enabled" else "disabled"}")
    }

    /**
     * Check if a profile is active.
     */
    fun isProfileActive(profileId: Long): Boolean {
        return activeProfiles.getOrDefault(profileId, false)
    }

    /**
     * Load active profiles from database (called on app startup).
     */
    suspend fun initializeActiveProfiles() {
        try {
            // Load all profiles and cache their enabled state
            // This should be done in a real profile repository (not yet implemented)
            log("Initialized active profiles")
        } catch (e: Exception) {
            log("Error initializing profiles", e)
        }
    }

    private fun log(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    companion object {
        private const val TAG = "AutomationEngine"
    }
}
