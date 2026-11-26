package com.sysadmindoc.ruledeck.core.engine

import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * An import placeholder's note names variables to explain the step (Get Location's names %LOC), and
 * the placeholder never runs, so preview leaves its text as written instead of reading them.
 */
class ImportPlaceholderPreflightTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun placeholderArgs(type: String): Map<String, String> {
        val task = Task(
            id = 1,
            name = "Imported",
            actions = listOf(
                ActionSpec(type = "var.set", args = mapOf("name" to "%where", "value" to "home")),
                ActionSpec(type = type, args = mapOf("summary" to "Steps after it read %where")),
            ),
        )
        return PreflightRunner.preflightTask(task).tasks.single().steps.last().expandedArguments
    }

    @Test
    fun aMacroDroidPlaceholdersNoteKeepsItsVariableNames() {
        assertEquals("Steps after it read %where", placeholderArgs("macrodroid.unsupported")["summary"])
    }

    @Test
    fun aPlaceholdersRunOnlyIfIsNotReadAndTheStepStaysBlocked() {
        val task = Task(
            id = 1,
            name = "Imported",
            actions = listOf(
                ActionSpec(type = "var.set", args = mapOf("name" to "%where", "value" to "home")),
                // The import keeps the original step's Run only if on the placeholder. Read, this one would skip the step.
                ActionSpec(type = "macrodroid.unsupported", args = mapOf("summary" to "Get Location"), condition = "%where ~ away"),
            ),
        )

        val step = PreflightRunner.preflightTask(task).tasks.single().steps.last()

        assertNull(step.condition)
        assertEquals(PreflightStepStatus.BLOCKED, step.status)
    }
}
