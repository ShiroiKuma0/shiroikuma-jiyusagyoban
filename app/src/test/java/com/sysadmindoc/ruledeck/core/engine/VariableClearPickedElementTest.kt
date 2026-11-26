package com.sysadmindoc.ruledeck.core.engine

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.actions.clearVariables
import com.sysadmindoc.ruledeck.core.actions.registerActionMetadata
import com.sysadmindoc.ruledeck.core.model.ActionSpec
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.registerCoreRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Variable Clear of an element picked as the step runs:
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
        // Only the first character counts.
        elements().also { assertTrue(run(it, "%Arr(<last)").success) }.also { assertEquals(listOf(2, 3), left(it)) }
    }

    @Test
    fun aVariablePicksByTheWholeNumberItHolds() {
        elements().apply { set("Idx", "3") }.also { assertTrue(run(it, "%Arr(%Idx)").success) }.also { assertEquals(listOf(2, 5), left(it)) }
        // Not a whole number, or not set at all: it reads 0, so %Arr0 goes and the elements stay.
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
    fun aVariableOfFewerThanThreeLettersIsntAPick() {
        // A pick's variable needs three characters after the %, so %Arr(%Ix) fails the step.
        val store = elements().apply { set("Ix", "2") }
        assertFalse(run(store, "%Arr(%Ix)").success)
        assertEquals(listOf(2, 3, 5), left(store))
    }

    @Test
    fun withPatternMatchingTheElementIsPickedFirst() {
        elements().also { assertTrue(run(it, "%Arr(<)", pattern = true).success) }.also { assertEquals(listOf(2, 3), left(it)) }
    }

    @Test
    fun elementZeroAndANumberWrittenWithZerosInFrontCountAsWholeNumbers() {
        // What follows the base is read as a whole number, so %Arr0 is element 0.
        elements().apply { set("Arr0", "z") }.also { assertTrue(run(it, "%Arr(>)").success) }.also { assertEquals(listOf(2, 3, 5), left(it)) }
        // %Arr07 counts as 7, so the pick is %Arr7, which isn't set, and both stay.
        VariableStore().apply { set("Arr07", "g"); set("Arr4", "d") }.also { assertTrue(run(it, "%Arr(<)").success) }
            .also { assertEquals("g", it.get("Arr07")); assertEquals("d", it.get("Arr4")) }
    }

    @Test
    fun aNameReadAsAPathIsntPicked() {
        // A dot, or both brackets. With Pattern Matching on, that's a pattern clear of the text as written.
        elements().also { assertTrue(run(it, "%Arr(<.x)", pattern = true).success) }.also { assertEquals(listOf(2, 3, 5), left(it)) }
        // With it off the step fails as a path, and the preview agrees.
        elements().also { assertFalse(run(it, "%Arr(<.x)").success) }.also { assertEquals(listOf(2, 3, 5), left(it)) }
        elements().also { assertEquals(0, it.clearVariables(mapOf("name" to "%Arr(<.x)"))) }.also { assertEquals(listOf(2, 3, 5), left(it)) }
        // One bracket alone isn't a path, so it picks.
        elements().also { assertTrue(run(it, "%Arr(<[)").success) }.also { assertEquals(listOf(2, 3), left(it)) }
    }

    @Test
    fun anElementNumberedBelowZeroFailsALowestPickButNotAHighestOne() {
        // %Arr-1 reads as element -1. The lowest is then below 0 and fails the step, while the highest counts as 0.
        VariableStore().apply { set("Arr-1", "m"); set("Arr0", "z") }.also { assertFalse(run(it, "%Arr(>)").success) }
            .also { assertEquals("m", it.get("Arr-1")); assertEquals("z", it.get("Arr0")) }
        VariableStore().apply { set("Arr-1", "m") }.also { assertTrue(run(it, "%Arr(<)").success) }
            .also { assertEquals("m", it.get("Arr-1")) }
        VariableStore().apply { set("Arr-1", "m"); set("Arr0", "z") }.also { assertTrue(run(it, "%Arr(<)").success) }
            .also { assertEquals("m", it.get("Arr-1")); assertEquals(null, it.get("Arr0")) }
    }

    @Test
    fun theFailureDoesntSayTheNumberTheVariableHeld() {
        val report = run(elements().apply { set("Idx", "-1234") }, "%Arr(%Idx)")
        assertFalse(report.success)
        assertFalse(report.results.toString(), "1234" in report.results.toString())
    }
}
