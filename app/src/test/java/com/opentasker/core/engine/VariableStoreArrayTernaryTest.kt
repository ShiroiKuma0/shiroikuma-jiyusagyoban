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
