package com.opentasker.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class CountTextTest {
    @Test
    fun oneIsSingularAndEveryOtherCountIsPlural() {
        assertEquals("1 variable", countOf(1, "variable"))
        assertEquals("2 variables", countOf(2, "variable"))
        assertEquals("0 variables", countOf(0, "variable"))
        assertEquals("1 more second", countOf(1L, "more second"))
    }

    @Test
    fun anIrregularPluralIsGivenOutright() {
        assertEquals("3 entries", countOf(3, "entry", "entries"))
        assertEquals("1 entry", countOf(1, "entry", "entries"))
    }

    @Test
    fun aCountLeadingASentenceTakesWasOrWere() {
        assertEquals("1 scene was", countWas(1, "scene"))
        assertEquals("2 scenes were", countWas(2, "scene"))
        assertEquals("0 secret variables were", countWas(0, "secret variable"))
        assertEquals("3 entries were", countWas(3, "entry", "entries"))
    }
}
