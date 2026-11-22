package com.opentasker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/** A-364: the library lists' sort orders. The default leaves each list as it always was. */
class LibrarySortTest {
    private data class Item(val name: String, val editedAt: Long?)

    private val items = listOf(Item("beta", 30), Item("Alpha", null), Item("gamma", 50), Item("delta", null))

    private fun sorted(order: LibrarySortOrder) = sortLibrary(items, order, Item::name, Item::editedAt).map(Item::name)

    @Test
    fun theDefaultOrderLeavesTheListAsItCame() {
        assertEquals(listOf("beta", "Alpha", "gamma", "delta"), sorted(LibrarySortOrder.Default))
    }

    @Test
    fun nameIgnoresCase() {
        assertEquals(listOf("Alpha", "beta", "delta", "gamma"), sorted(LibrarySortOrder.Name))
    }

    @Test
    fun lastEditedPutsTheNewestFirstAndKeepsTheNeverEditedInTheirOrder() {
        assertEquals(listOf("gamma", "beta", "Alpha", "delta"), sorted(LibrarySortOrder.LastEdited))
    }
}
