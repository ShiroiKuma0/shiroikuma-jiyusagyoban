package com.opentasker.ui.screens

import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.InvariantStatePredicate
import com.opentasker.core.storage.ProjectEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Undo and reorder work from the stored state, not a stale copy (review of A-332 and A-333). */
class UndoAndReorderStateTest {
    private fun rule(id: Long, name: String) = AutomationInvariant(
        id = id,
        name = name,
        guard = InvariantStatePredicate(key = "dnd", value = "on"),
        forbiddenWriteKey = "volume:$name",
    )

    @Test
    fun undoRestoresADeletedRuleEvenWhenANewRuleTookItsId() {
        val quiet = rule(1L, "quiet")
        val later = rule(1L, "later")

        val restored = withRestoredInvariant(listOf(rule(2L, "b"), later), quiet, index = 0)

        assertEquals(listOf("quiet", "b", "later"), restored?.map { it.name })
        // The freed id went to the new rule, so the restored one gets a fresh id instead of a clash.
        assertEquals(0L, restored?.first()?.id)
    }

    @Test
    fun undoKeepsTheOldIdWhenItIsStillFree() {
        val quiet = rule(1L, "quiet")

        assertEquals(listOf(rule(2L, "b"), quiet), withRestoredInvariant(listOf(rule(2L, "b")), quiet, index = 5))
    }

    @Test
    fun undoDoesNothingWhenTheSameRuleIsAlreadyBack() {
        val quiet = rule(1L, "quiet")

        assertNull(withRestoredInvariant(listOf(quiet.copy(id = 3L)), quiet, index = 0))
    }

    @Test
    fun movingAProjectRenumbersFromTheStoredRows() {
        val ordered = listOf(ProjectEntity(1L, "Default", 0), ProjectEntity(2L, "Home", 1), ProjectEntity(3L, "Work", 2))

        val updates = projectPositionUpdates(ordered, index = 2, targetIndex = 1)

        assertEquals(listOf(ProjectEntity(3L, "Work", 1), ProjectEntity(2L, "Home", 2)), updates)
    }

    @Test
    fun aMoveAlsoRepairsDuplicatePositionsLeftByTheOldSwap() {
        val ordered = listOf(ProjectEntity(1L, "Default", 0), ProjectEntity(2L, "Home", 1), ProjectEntity(3L, "Work", 1))

        val updates = projectPositionUpdates(ordered, index = 1, targetIndex = 2)

        // Work already sits at 1, so only Home is written, and every project ends up on its own place.
        assertEquals(listOf(ProjectEntity(2L, "Home", 2)), updates)
        val positions = ordered.associate { it.id to it.position } + updates.associate { it.id to it.position }
        assertEquals(listOf(0, 1, 2), positions.values.sorted())
    }
}
