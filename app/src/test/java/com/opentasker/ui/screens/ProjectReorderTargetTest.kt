package com.opentasker.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A move that can't happen returns null, so the view model skips both the write and "Moved" (A-333). */
class ProjectReorderTargetTest {
    @Test
    fun theEndsOfTheListDontMoveOutward() {
        assertNull(projectReorderTarget(index = 0, direction = -1, count = 3))
        assertNull(projectReorderTarget(index = 2, direction = 1, count = 3))
        assertNull(projectReorderTarget(index = 0, direction = 1, count = 1))
    }

    @Test
    fun aMoveGoesOneStepWhateverTheDirectionsSize() {
        assertEquals(0, projectReorderTarget(index = 1, direction = -1, count = 3))
        assertEquals(2, projectReorderTarget(index = 1, direction = 1, count = 3))
        assertEquals(1, projectReorderTarget(index = 0, direction = 5, count = 3))
    }

    @Test
    fun aMissingProjectOrNoDirectionIsNotAMove() {
        assertNull(projectReorderTarget(index = -1, direction = 1, count = 3))
        assertNull(projectReorderTarget(index = 3, direction = -1, count = 3))
        assertNull(projectReorderTarget(index = 1, direction = 0, count = 3))
    }
}
