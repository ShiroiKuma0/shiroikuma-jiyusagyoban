package com.opentasker.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTargetContractTest {
    @Test
    fun validatesVariableNamesForExternalExtras() {
        assertTrue(AutomationTargetContract.isValidVariableName("User"))
        assertTrue(AutomationTargetContract.isValidVariableName("task_value_1"))
        assertFalse(AutomationTargetContract.isValidVariableName("1bad"))
        assertFalse(AutomationTargetContract.isValidVariableName("bad-name"))
        assertFalse(AutomationTargetContract.isValidVariableName(""))
    }

    @Test
    fun buildsDocumentedVariableExtraNames() {
        assertEquals(
            "com.opentasker.var.User",
            AutomationTargetContract.variableExtraName("User"),
        )
    }

    @Test
    fun rejectsInvalidVariableExtraNames() {
        val error = runCatching {
            AutomationTargetContract.variableExtraName("bad-name")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
