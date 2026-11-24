package com.opentasker.ui.screens

import com.opentasker.ui.theme.UiRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The section icons of the UI page: seven colours by meaning, and no slabs in the built-in order. */
class UiPageSectionsTest {
    private val seven = setOf(
        UiRole.SECTION_BASE, UiRole.SECTION_CONTROLS, UiRole.SECTION_WORDS, UiRole.SECTION_FRAME,
        UiRole.SECTION_REACH, UiRole.SECTION_WARNING, UiRole.SECTION_FLASH,
    )

    @Test
    fun everySectionTakesOneOfTheSevenColours() {
        PAGE_SECTIONS.forEach { assertTrue("${it.id} has ${it.color}", it.color in seven) }
    }

    @Test
    fun noTwoNeighboursShareAColour() {
        PAGE_SECTIONS.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.id} and ${b.id} are both ${a.color}", a.color != b.color)
        }
    }

    @Test
    fun theFlashColourIsUsedOnceAndTheBaseDoesNotDominate() {
        assertEquals(1, PAGE_SECTIONS.count { it.color == UiRole.SECTION_FLASH })
        assertTrue(PAGE_SECTIONS.count { it.color == UiRole.SECTION_BASE } * 3 <= PAGE_SECTIONS.size)
    }

    @Test
    fun sectionIdsAreUnique() {
        assertEquals(PAGE_SECTIONS.size, PAGE_SECTIONS.map { it.id }.toSet().size)
    }
}
