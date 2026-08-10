package com.opentasker.core.validation

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.Task

/**
 * Input validation utilities to ensure data integrity before storage.
 */
object InputValidation {
    const val MAX_NAME_LENGTH = 200
    const val MIN_NAME_LENGTH = 1
    const val MAX_COOLDOWN_SEC = 3600 // 1 hour
    const val MIN_PROFILE_PRIORITY = ProfileLifecyclePolicy.MIN_PRIORITY
    const val MAX_PROFILE_PRIORITY = ProfileLifecyclePolicy.MAX_PRIORITY
    const val MAX_GRACE_PERIOD_SEC = ProfileLifecyclePolicy.MAX_GRACE_PERIOD_SEC
    
    data class ValidationError(val field: String, val message: String)
    
    fun validateProfile(profile: Profile): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        if (profile.name.isBlank()) {
            errors.add(ValidationError("name", "Profile name cannot be empty"))
        }
        if (profile.name.length > MAX_NAME_LENGTH) {
            errors.add(ValidationError("name", "Profile name exceeds $MAX_NAME_LENGTH characters"))
        }
        if (profile.enterTaskId <= 0) {
            errors.add(ValidationError("enterTaskId", "Enter task must be set"))
        }
        if (profile.cooldownSec < 0 || profile.cooldownSec > MAX_COOLDOWN_SEC) {
            errors.add(ValidationError("cooldownSec", "Cooldown must be between 0 and $MAX_COOLDOWN_SEC seconds"))
        }
        if (profile.priority !in MIN_PROFILE_PRIORITY..MAX_PROFILE_PRIORITY) {
            errors.add(
                ValidationError(
                    "priority",
                    "Profile priority must be between $MIN_PROFILE_PRIORITY and $MAX_PROFILE_PRIORITY",
                ),
            )
        }
        if (profile.gracePeriodSec < 0 || profile.gracePeriodSec > MAX_GRACE_PERIOD_SEC) {
            errors.add(
                ValidationError(
                    "gracePeriodSec",
                    "Grace period must be between 0 and $MAX_GRACE_PERIOD_SEC seconds",
                ),
            )
        }
        when (profile.lifetime) {
            ProfileLifetime.UNTIL_DATE -> if (profile.expiresAtMs == null || profile.expiresAtMs <= 0L) {
                errors.add(ValidationError("expiresAtMs", "An expiry date is required for date-limited profiles"))
            }
            ProfileLifetime.NEVER, ProfileLifetime.ONCE -> Unit
        }
        if (profile.contexts.isEmpty()) {
            errors.add(ValidationError("contexts", "Profile must have at least one context"))
        }
        
        return errors
    }
    
    fun validateTask(task: Task): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        if (task.name.isBlank()) {
            errors.add(ValidationError("name", "Task name cannot be empty"))
        }
        if (task.name.length > MAX_NAME_LENGTH) {
            errors.add(ValidationError("name", "Task name exceeds $MAX_NAME_LENGTH characters"))
        }
        if (task.priority < 0 || task.priority > 10) {
            errors.add(ValidationError("priority", "Priority must be between 0 and 10"))
        }
        if (task.actions.isEmpty()) {
            errors.add(ValidationError("actions", "Task must have at least one action"))
        }
        
        return errors
    }
    
    fun validateAction(action: ActionSpec): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        if (action.type.isBlank()) {
            errors.add(ValidationError("type", "Action type cannot be empty"))
        }
        if (action.label != null && action.label.length > MAX_NAME_LENGTH) {
            errors.add(ValidationError("label", "Action label exceeds $MAX_NAME_LENGTH characters"))
        }
        
        return errors
    }
}
