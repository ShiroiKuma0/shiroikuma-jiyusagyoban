package com.opentasker.core.engine.variables

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrayStoreTest {
    @Test
    fun evictsLeastRecentlyUsedNotArbitraryEntry() {
        val store = ArrayStore()
        for (i in 1..500) store.put("a$i", listOf("$i"))
        // Touch a1 so it is the most-recently-used; a2 becomes the eviction candidate.
        store.get("a1", 0)
        store.put("a501", listOf("new"))

        assertTrue("recently-used entry must survive", store.contains("a1"))
        assertFalse("least-recently-used entry must be evicted", store.contains("a2"))
        assertTrue(store.contains("a501"))
    }

    @Test
    fun basicAccessorsReturnStoredValues() {
        val store = ArrayStore()
        store.put("list", listOf("x", "y", "z"))
        assertEquals(3, store.length("list"))
        assertEquals("y", store.get("list", 1))
        assertEquals("x,y,z", store.join("list", ","))
        assertEquals("", store.get("list", 9))
    }
}
