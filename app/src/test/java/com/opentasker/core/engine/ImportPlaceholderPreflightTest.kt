package com.opentasker.core.engine

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * An import placeholder's note names variables to explain the step (Get Location's names %LOC), and
 * the placeholder never runs, so preview leaves its text as written instead of reading them
 * (verifier 28).
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
    fun aTaskerPlaceholdersNoteKeepsItsVariableNames() {
        assertEquals("Steps after it read %where", placeholderArgs("tasker.unsupported")["summary"])
    }

    @Test
    fun aMacroDroidPlaceholdersNoteKeepsItsVariableNames() {
        assertEquals("Steps after it read %where", placeholderArgs("macrodroid.unsupported")["summary"])
    }
}
