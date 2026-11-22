package com.opentasker.core.engine

import android.content.ContextWrapper
import com.opentasker.core.actions.registerActionMetadata
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Variable Clear of an element picked as the step runs, the way Tasker 6.7.8 picks it (`dq.i`):
 * `%arr(>)` the lowest element number set, `%arr(<)` the highest, 0 when none is, and `%arr(%idx)`
 * the whole number `%idx` holds, 0 when it holds none. A pick below 0 fails the step.
 */
class VariableClearPickedElementTest {

    @Before
    fun setUp() {
        registerActionMetadata()
        registerCoreRuntime()
    }

    private fun run(store: VariableStore, name: String, pattern: Boolean = false) = runBlocking {
        val args = buildMap {
            put("name", name)
            if (pattern) put("pattern", "true")
        }
        TaskRunner(ActionContext(ContextWrapper(null), store)).run(Task(id = 1, name = "Clear", actions = listOf(ActionSpec(type = "var.clear", args = args))))
    }

    private fun elements() = VariableStore().apply {
        set("Arr2", "b")
        set("Arr3", "c")
        set("Arr5", "e")
    }

    private fun left(store: VariableStore) = (0..5).filter { store.get("Arr$it") != null }

    @Test
    fun theLowestAndHighestElementSetArePicked() {
        elements().also { assertTrue(run(it, "%Arr(>)").success) }.also { assertEquals(listOf(3, 5), left(it)) }
        elements().also { assertTrue(run(it, "%Arr(<)").success) }.also { assertEquals(listOf(2, 3), left(it)) }
        // Tasker looks only at the first character.
        elements().also { assertTrue(run(it, "%Arr(<last)").success) }.also { assertEquals(listOf(2, 3), left(it)) }
    }

    @Test
    fun aVariablePicksByTheWholeNumberItHolds() {
        elements().apply { set("Idx", "3") }.also { assertTrue(run(it, "%Arr(%Idx)").success) }.also { assertEquals(listOf(2, 5), left(it)) }
        // Not a whole number, or not set at all: Tasker reads 0, so %Arr0 goes and the elements stay.
        elements().apply { set("Idx", "three"); set("Arr0", "z") }.also { assertTrue(run(it, "%Arr(%Idx)").success) }
            .also { assertEquals(listOf(2, 3, 5), left(it)) }
        elements().also { assertTrue(run(it, "%Arr(%Idx)").success) }.also { assertEquals(listOf(2, 3, 5), left(it)) }
    }

    @Test
    fun noElementSetPicksZero() {
        val store = VariableStore().apply { set("Other", "x") }
        assertTrue(run(store, "%Arr(>)").success)
        assertEquals("x", store.get("Other"))
    }

    @Test
    fun aPickBelowZeroFailsTheStepAndClearsNothing() {
        val store = elements().apply { set("Idx", "-1") }
        val report = run(store, "%Arr(%Idx)")
        assertFalse(report.success)
        assertEquals(listOf(2, 3, 5), left(store))
    }

    @Test
    fun aVariableOfFewerThanThreeLettersIsntAPickInTaskerEither() {
        // Tasker's dq.c1 wants three characters after the %, so it fails the step on %Arr(%ix).
        val store = elements().apply { set("Ix", "2") }
        assertFalse(run(store, "%Arr(%Ix)").success)
        assertEquals(listOf(2, 3, 5), left(store))
    }

    @Test
    fun withPatternMatchingTheElementIsPickedFirst() {
        elements().also { assertTrue(run(it, "%Arr(<)", pattern = true).success) }.also { assertEquals(listOf(2, 3), left(it)) }
    }
}
