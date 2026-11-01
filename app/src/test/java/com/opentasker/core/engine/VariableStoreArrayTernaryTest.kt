package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableStoreArrayTernaryTest {
    @Test
    fun nestedWriteRejectsOutOfRangeIndexWithoutGrowing() {
        val store = VariableStore()
        // A pathological index must fail closed rather than growing a ~2-billion-entry list.
        assertFalse(store.setAtPath("Arr[2000000000]", "x"))
        assertFalse(store.setAtPath("Arr[100001]", "x"))
        // A reasonable index still works.
        assertTrue(store.setAtPath("Arr[3]", "x"))
    }

    @Test
    fun setArrayAtIndexRejectsOutOfRangeIndex() {
        val store = VariableStore()
        assertFalse(store.setArrayAtIndex("a", 100_001, "x"))
        assertFalse(store.setArrayAtIndex("a", -1, "x"))
        assertTrue(store.setArrayAtIndex("a", 3, "x"))
        assertEquals(4, store.getArrayItems("a")?.size)
    }

    @Test
    fun anIndexHeldInAVariableReadsThatItem() {
        val store = VariableStore()
        store.setArray("titles", listOf("買い物", "秘密", "力"))
        // The index means exactly what a literal one does (this store counts from 0).
        store.set("n", "2")
        assertEquals("力", store.expand("%titles(%n)"))
        assertEquals(store.expand("%titles(2)"), store.expand("%titles(%n)"))
        // A literal index and a join separator still behave as before.
        assertEquals("秘密", store.expand("%titles(1)"))
        assertEquals("買い物|秘密|力", store.expand("%titles(|)"))
        // An index variable that is unset or not a number reads as empty, never as a join.
        assertEquals("", store.expand("%titles(%missing)"))
    }

    @Test
    fun anIndexReadsTheArrayEvenBesideAPlainValueOfTheSameName() {
        val store = VariableStore()
        // What text.match leaves behind: %h = the whole match, the array h = [whole, group 1, …].
        store.set("h", "⇐ SIM2 · +420700000002")
        store.setArray("h", listOf("⇐ SIM2 · +420700000002", "SIM2", "+420700000002"))
        store.set("n", "1")
        assertEquals("+420700000002", store.expand("%h(2)"))
        assertEquals("SIM2", store.expand("%h(%n)"))
        assertEquals("3", store.expand("%h(#)"))
        assertEquals("⇐ SIM2 · +420700000002", store.expand("%h"))
        // Arithmetic on a plain number is untouched by an array of the same name.
        store.set("k", "5")
        store.setArray("k", listOf("x"))
        assertEquals("6", store.expand("%k(+1)"))
    }

    @Test
    fun ternaryHandlesPlainCondition() {
        val store = VariableStore().apply { set("A", "7") }
        assertEquals("big", store.expand("(%A > 5) ? big : small"))
        store.set("A", "3")
        assertEquals("small", store.expand("(%A > 5) ? big : small"))
    }

    @Test
    fun ternaryHandlesConditionContainingParens() {
        // Previously the (cond) regex stopped at the first ')', so an operator expression with
        // parens inside the condition silently fell through. %A(+1) = 6 > 5 -> "big".
        val store = VariableStore().apply { set("A", "5") }
        assertEquals("big", store.expand("(%A(+1) > 5) ? big : small"))
    }

    @Test
    fun nonTernaryParenTextIsLeftIntact() {
        val store = VariableStore()
        assertEquals("(hello world)", store.expand("(hello world)"))
    }
}
