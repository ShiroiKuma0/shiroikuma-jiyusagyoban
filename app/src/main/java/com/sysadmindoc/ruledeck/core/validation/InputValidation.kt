package com.sysadmindoc.ruledeck.core.validation

import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.ProfileConcurrencyPolicy
import com.sysadmindoc.ruledeck.core.model.ProfileLifetime
import com.sysadmindoc.ruledeck.core.model.ProfileLifecyclePolicy
import com.sysadmindoc.ruledeck.core.model.Task

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
    const val MIN_PROFILE_MAX_ACTIVE = ProfileConcurrencyPolicy.MIN_MAX_ACTIVE
    const val MAX_PROFILE_MAX_ACTIVE = ProfileConcurrencyPolicy.MAX_MAX_ACTIVE
    const val MIN_PROFILE_BURST_LIMIT = ProfileConcurrencyPolicy.MIN_BURST_LIMIT
    const val MAX_PROFILE_BURST_LIMIT = ProfileConcurrencyPolicy.MAX_BURST_LIMIT
    
    data class ValidationError(val field: String, val message: String)
    
    fun validateProfile(profile: Profile): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        if (profile.name.isBlank()) {
            errors.add(ValidationError("name", tr("Profile name cannot be empty")))
        }
        if (profile.name.length > MAX_NAME_LENGTH) {
            errors.add(ValidationError("name", tr("Profile name exceeds %1\$s characters", MAX_NAME_LENGTH)))
        }
        val exitTaskId = profile.exitTaskId
        if (exitTaskId != null && exitTaskId <= 0L) {
            errors.add(ValidationError("exitTaskId", tr("Exit task id must be positive")))
        }
        // 0 is the one way to say there is no enter task.
        if (profile.enterTaskId < 0) {
            errors.add(ValidationError("enterTaskId", tr("Enter task id must not be negative")))
        } else if (profile.enterTaskId == 0L && (exitTaskId == null || exitTaskId <= 0L)) {
            errors.add(ValidationError("enterTaskId", tr("An enter or exit task must be set")))
        }
        val fallbackTaskId = profile.fallbackTaskId
        if (fallbackTaskId != null && fallbackTaskId <= 0L) {
            errors.add(ValidationError("fallbackTaskId", tr("Fallback task id must be positive")))
        }
        if (profile.cooldownSec < 0 || profile.cooldownSec > MAX_COOLDOWN_SEC) {
            errors.add(ValidationError("cooldownSec", tr("Cooldown must be between 0 and %1\$s seconds", MAX_COOLDOWN_SEC)))
        }
        if (profile.priority !in MIN_PROFILE_PRIORITY..MAX_PROFILE_PRIORITY) {
            errors.add(
                ValidationError(
                    "priority",
                    tr("Profile priority must be between %1\$s and %2\$s", MIN_PROFILE_PRIORITY, MAX_PROFILE_PRIORITY),
                ),
            )
        }
        if (profile.gracePeriodSec < 0 || profile.gracePeriodSec > MAX_GRACE_PERIOD_SEC) {
            errors.add(
                ValidationError(
                    "gracePeriodSec",
                    tr("Grace period must be between 0 and %1\$s seconds", MAX_GRACE_PERIOD_SEC),
                ),
            )
        }
        if (profile.maxActiveExecutions != null &&
            profile.maxActiveExecutions !in MIN_PROFILE_MAX_ACTIVE..MAX_PROFILE_MAX_ACTIVE
        ) {
            errors.add(
                ValidationError(
                    "maxActiveExecutions",
                    tr("Profile active limit must be between %1\$s and %2\$s", MIN_PROFILE_MAX_ACTIVE, MAX_PROFILE_MAX_ACTIVE),
                ),
            )
        }
        if (profile.burstLimit != null &&
            profile.burstLimit !in MIN_PROFILE_BURST_LIMIT..MAX_PROFILE_BURST_LIMIT
        ) {
            errors.add(
                ValidationError(
                    "burstLimit",
                    tr("Profile burst limit must be between %1\$s and %2\$s", MIN_PROFILE_BURST_LIMIT, MAX_PROFILE_BURST_LIMIT),
                ),
            )
        }
        val expiresAtMs = profile.expiresAtMs
        when (profile.lifetime) {
            ProfileLifetime.UNTIL_DATE -> if (expiresAtMs == null || expiresAtMs <= 0L) {
                errors.add(ValidationError("expiresAtMs", tr("An expiry date is required for date-limited profiles")))
            }
            ProfileLifetime.NEVER, ProfileLifetime.ONCE -> Unit
        }
        if (profile.contexts.isEmpty()) {
            errors.add(ValidationError("contexts", tr("Profile must have at least one context")))
        }
        
        return errors
    }
    
    fun validateTask(task: Task): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        if (task.name.isBlank()) {
            errors.add(ValidationError("name", tr("Task name cannot be empty")))
        }
        if (task.name.length > MAX_NAME_LENGTH) {
            errors.add(ValidationError("name", tr("Task name exceeds %1\$s characters", MAX_NAME_LENGTH)))
        }
        if (task.priority < 0 || task.priority > 10) {
            errors.add(ValidationError("priority", tr("Priority must be between 0 and 10")))
        }
        if (task.actions.isEmpty()) {
            errors.add(ValidationError("actions", tr("Task must have at least one action")))
        }
        
        return errors
    }
    
    fun validateAction(action: ActionSpec): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        
        if (action.type.isBlank()) {
            errors.add(ValidationError("type", tr("Action type cannot be empty")))
        }
        val label = action.label
        if (label != null && label.length > MAX_NAME_LENGTH) {
            errors.add(ValidationError("label", tr("Action label exceeds %1\$s characters", MAX_NAME_LENGTH)))
        }
        
        return errors
    }
}
