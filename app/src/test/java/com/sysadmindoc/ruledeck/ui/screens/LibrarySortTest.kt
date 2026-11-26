package com.sysadmindoc.ruledeck.ui.screens

import com.sysadmindoc.ruledeck.core.storage.EditHistoryDao
import com.sysadmindoc.ruledeck.core.storage.VariableEditHistoryIdentity
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

    @Test
    fun theNewestOfAHistoryRowAndAStampWins() {
        val merged = newestEdits(history = mapOf("task:1" to 10L, "task:2" to 50L), stamps = mapOf("task:1" to 40L, "task:3" to 5L))

        assertEquals(mapOf("task:1" to 40L, "task:2" to 50L, "task:3" to 5L), merged)
    }

    @Test
    fun aVariableTheHistoryIdCantBeMadeForHasNoEditTime() {
        val id = VariableEditHistoryIdentity.entityId(1, "Foo")
        val state = EditHistoryAvailabilityState(lastEditedByKey = mapOf(EditHistoryAvailabilityState.key(EditHistoryDao.TYPE_VARIABLE, id) to 99L))

        assertEquals(99L, state.lastEditedVariableAt(1, "Foo"))
        // A name that isn't normalized or a project id of 0 made the list throw while drawing.
        assertEquals(null, state.lastEditedVariableAt(0, "Foo"))
        assertEquals(null, state.lastEditedVariableAt(1, "%Foo bar"))
    }
}
