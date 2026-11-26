package com.sysadmindoc.ruledeck.core.engine

import com.sysadmindoc.ruledeck.core.actions.ActionArgumentSensitivity
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Preflight plays Variable Split and Join and the array actions on its own variables, the same work
 * their runs do, so a later step reads the array the run would leave.
 */
class PreflightPlaysArrayActionsTest {

    @Before
    fun setUp() {
        registerCoreRuntime()
    }

    private fun step(type: String, vararg args: Pair<String, String>) = ActionSpec(type = type, args = mapOf(*args))

    private fun probe(read: String) = step("var.set", "name" to "%Seen", "value" to read)

    /** What each Variable Set probe reads, in order. */
    private fun reads(vararg actions: ActionSpec, secret: Boolean = false): List<String> =
        PreflightRunner.preflightTask(
            task = Task(name = "T", actions = actions.toList()),
            inputs = PreflightInputs(
                globalVariables = mapOf("Csv" to "cherry,apple,banana"),
                secretGlobalNames = if (secret) setOf("Csv") else emptySet(),
            ),
        ).tasks.single().steps.filter { it.actionType == "var.set" }.map { it.expandedArguments["value"].orEmpty() }

    private val split = step("var.split", "name" to "%Fruits", "values" to "%Csv", "splitter" to ",")

    @Test
    fun aLaterStepReadsTheArrayTheStepsBeforeItLeave() {
        val seen = reads(
            split,
            probe("%Fruits(#):%Fruits(2)"),
            step("array.push", "array" to "%Fruits", "position" to "1", "value" to "z"),
            probe("%Fruits(1):%Fruits(#)"),
            step("array.pop", "array" to "%Fruits", "position" to "1", "to_var" to "%Got"),
            probe("%Got"),
            step("array.process", "array" to "%Fruits", "type" to "sort_alpha"),
            probe("%Fruits(+,)"),
            step("var.join", "name" to "%Fruits", "joiner" to "-"),
            probe("%Fruits"),
            step("array.clear", "array" to "%Fruits"),
            probe("%Fruits(#)"),
        )

        assertEquals(listOf("3:apple", "z:4", "z", "apple,banana,cherry", "apple-banana-cherry", "0"), seen)
    }

    @Test
    fun aPushTheRunWouldStopChangesNothing() {
        // %Pos is set later, so Preflight only warns that it's empty here, and the run stops the step.
        val seen = reads(
            split,
            step("array.push", "array" to "%Fruits", "position" to "%Pos", "value" to "z"),
            probe("%Fruits(#)"),
            step("var.set", "name" to "%Pos", "value" to "1"),
        )

        assertEquals("3", seen.first())
    }

    @Test
    fun elementsSplitFromASecretStaySecret() {
        val seen = reads(split, probe("%Fruits(2)"), secret = true)

        assertEquals(ActionArgumentSensitivity.REDACTED, seen.single())
    }

    @Test
    fun aValuePushedFromASecretStaysSecret() {
        // Push marks nothing itself; the run marks every write of a step that read a secret.
        val seen = reads(
            step("array.push", "array" to "%Codes", "position" to "1", "value" to "%Csv"),
            probe("%Codes(1)"),
            secret = true,
        )

        assertEquals(ActionArgumentSensitivity.REDACTED, seen.single())
    }
}
