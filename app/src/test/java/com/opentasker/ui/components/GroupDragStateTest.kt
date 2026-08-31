package com.opentasker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind holding a group header and dragging it.
 *
 * Worth pinning down away from the phone: the whole gesture reduces to "which sibling's midline has
 * the lifted header's centre crossed", and an off-by-one there means a group that will not move to
 * the end of the list, or one that jumps a slot on release. Both would read as the feature being
 * broken, and neither is visible from a screenshot.
 *
 * Three sibling headers, stacked 100px apart, is the whole fixture:
 *   A 0..100 (mid 50)   B 100..200 (mid 150)   C 200..300 (mid 250)
 */
class GroupDragStateTest {

    private fun stateWithThreeHeaders(): GroupDragState = GroupDragState().apply {
        headerBounds[1L] = 0f..100f
        headerBounds[2L] = 100f..200f
        headerBounds[3L] = 200f..300f
    }

    private val siblings = listOf(1L, 2L, 3L)

    @Test
    fun holdingWithoutMovingCommitsNothingSoItStaysTheSelectionGesture() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(1L, siblings)

        assertFalse("a hold with no travel is not a drag", state.groupDragMoved)
        assertNull("nothing to persist — the caller falls back to long-press", state.endGroupDrag())
    }

    @Test
    fun aTremorIsNotADrag() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(1L, siblings)
        state.moveGroup(5f)

        assertFalse(state.groupDragMoved)
        assertNull(state.endGroupDrag())
    }

    @Test
    fun draggingPastOneSiblingMidlineMovesItOneSlotDown() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(1L, siblings) // centre 50
        state.moveGroup(120f)              // centre 170: past B's midline (150), short of C's (250)

        assertTrue(state.groupDragMoved)
        assertEquals(listOf(2L, 1L, 3L), state.endGroupDrag())
    }

    @Test
    fun draggingPastEverySiblingLandsAtTheEnd() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(1L, siblings) // centre 50
        state.moveGroup(220f)              // centre 270: past both midlines

        assertEquals(listOf(2L, 3L, 1L), state.endGroupDrag())
    }

    @Test
    fun draggingUpwardsAboveEveryMidlineLandsFirst() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(3L, siblings) // centre 250
        state.moveGroup(-210f)             // centre 40: above A's midline (50)

        assertEquals(listOf(3L, 1L, 2L), state.endGroupDrag())
    }

    @Test
    fun aSiblingWithNoRecordedBoundsIsSkippedRatherThanTreatedAsAtZero() {
        // A collapsed ancestor can leave a sibling unmeasured; it must not silently become the target.
        val state = GroupDragState().apply {
            headerBounds[1L] = 0f..100f
            headerBounds[3L] = 200f..300f
        }
        state.startGroupDrag(1L, siblings)
        state.moveGroup(220f) // centre 270 — past C, while B was never measured

        assertEquals(listOf(2L, 3L, 1L), state.endGroupDrag())
    }

    @Test
    fun cancellingLeavesNothingBehind() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(1L, siblings)
        state.moveGroup(120f)
        state.cancelGroupDrag()

        assertNull(state.draggingGroupId)
        assertEquals(0f, state.groupOffsetY, 0f)
        assertEquals(-1, state.groupDropIndex)
        assertFalse(state.groupDragMoved)
    }

    @Test
    fun reorderingGroupsDoesNotDisturbAnItemDragInFlight() {
        val state = stateWithThreeHeaders()
        state.startGroupDrag(1L, siblings)
        state.moveGroup(120f)
        state.endGroupDrag()

        assertNull("the member-drag half of the state is independent", state.draggingKey)
    }
}
