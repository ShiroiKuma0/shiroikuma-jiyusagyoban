package com.sysadmindoc.ruledeck.core.validation

import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.ContextSpec
import com.sysadmindoc.ruledeck.core.model.ContextType
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.ProfileLifetime
import com.sysadmindoc.ruledeck.core.model.Task
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidationTest {
    @Test
    fun validateProfileRejectsEmptyNameMissingTaskAndEmptyContexts() {
        val errors = InputValidation.validateProfile(
            Profile(
                name = " ",
                enterTaskId = 0,
                contexts = emptyList()
            )
        )

        assertTrue(errors.any { it.field == "name" })
        assertTrue(errors.any { it.field == "enterTaskId" })
        assertTrue(errors.any { it.field == "contexts" })
    }

    @Test
    fun validateProfileRejectsCooldownOutsideSupportedRange() {
        val errors = InputValidation.validateProfile(
            Profile(
                name = "Too frequent",
                enterTaskId = 1,
                contexts = listOf(ContextSpec(ContextType.STATE)),
                cooldownSec = InputValidation.MAX_COOLDOWN_SEC + 1
            )
        )

        assertTrue(errors.any { it.field == "cooldownSec" })
    }

    @Test
    fun validateProfileRequiresValidLifecycleFields() {
        val errors = InputValidation.validateProfile(
            Profile(
                name = "Limited",
                enterTaskId = 1,
                contexts = listOf(ContextSpec(ContextType.STATE)),
                priority = InputValidation.MAX_PROFILE_PRIORITY + 1,
                gracePeriodSec = InputValidation.MAX_GRACE_PERIOD_SEC + 1,
                lifetime = ProfileLifetime.UNTIL_DATE,
            ),
        )

        assertTrue(errors.any { it.field == "priority" })
        assertTrue(errors.any { it.field == "gracePeriodSec" })
        assertTrue(errors.any { it.field == "expiresAtMs" })
    }

    @Test
    fun validateProfileRejectsUnboundedConcurrencyOverrides() {
        val errors = InputValidation.validateProfile(
            Profile(
                name = "Limited",
                enterTaskId = 1,
                contexts = listOf(ContextSpec(ContextType.STATE)),
                maxActiveExecutions = InputValidation.MAX_PROFILE_MAX_ACTIVE + 1,
                burstLimit = InputValidation.MAX_PROFILE_BURST_LIMIT + 1,
            ),
        )

        assertTrue(errors.any { it.field == "maxActiveExecutions" })
        assertTrue(errors.any { it.field == "burstLimit" })
    }

    @Test
    fun validateTaskRejectsInvalidPriorityAndEmptyActions() {
        val errors = InputValidation.validateTask(
            Task(
                name = "Bad priority",
                priority = 11,
                actions = emptyList()
            )
        )

        assertTrue(errors.any { it.field == "priority" })
        assertTrue(errors.any { it.field == "actions" })
    }

    @Test
    fun validateActionRejectsMissingType() {
        val errors = InputValidation.validateAction(ActionSpec(type = " "))

        assertTrue(errors.any { it.field == "type" })
    }
}
