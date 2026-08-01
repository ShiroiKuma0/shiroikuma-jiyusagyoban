package com.opentasker.ui.screens

import com.opentasker.core.model.ActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActionReorderTest {
    private val actions = listOf("one", "two", "three").map { ActionSpec(type = it) }

    @Test
    fun reorderMovesOneActionWithoutReconstructingTheOthers() {
        val reordered = reorderActions(actions, fromIndex = 2, toIndex = 0)

        assertEquals(listOf("three", "one", "two"), reordered.map(ActionSpec::type))
        assertEquals(actions[0], reordered[1])
        assertEquals(actions[1], reordered[2])
    }

    @Test
    fun reorderRejectsStaleIndices() {
        assertThrows(IllegalArgumentException::class.java) { reorderActions(actions, 3, 0) }
        assertThrows(IllegalArgumentException::class.java) { reorderActions(actions, 0, -1) }
    }
}
