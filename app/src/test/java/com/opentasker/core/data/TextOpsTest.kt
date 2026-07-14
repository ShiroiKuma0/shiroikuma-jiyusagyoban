package com.opentasker.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextOpsTest {
    @Test
    fun matchReturnsFullMatchThenGroups() {
        assertEquals(
            listOf("2026-07-14", "2026", "07", "14"),
            TextOps.match("date: 2026-07-14 end", "(\\d{4})-(\\d{2})-(\\d{2})"),
        )
    }

    @Test
    fun matchReturnsEmptyOnNoMatchAndNullOnBadPattern() {
        assertEquals(emptyList<String>(), TextOps.match("abc", "\\d+"))
        assertNull(TextOps.match("abc", "(")) // invalid regex
    }

    @Test
    fun replaceAllSupportsGroupReferences() {
        assertEquals("14/07/2026", TextOps.replaceAll("2026-07-14", "(\\d{4})-(\\d{2})-(\\d{2})", "$3/$2/$1"))
        assertEquals("a_b_c", TextOps.replaceAll("a b c", " ", "_"))
        assertNull(TextOps.replaceAll("x", "(", "y"))
    }

    @Test
    fun splitLiteralAndRegex() {
        assertEquals(listOf("a", "b", "c"), TextOps.split("a,b,c", ",", isRegex = false))
        assertEquals(listOf("a", "b", "c"), TextOps.split("a1b22c", "\\d+", isRegex = true))
        assertNull(TextOps.split("abc", "", isRegex = false)) // empty literal delimiter
    }

    @Test
    fun joinConcatenatesWithDelimiter() {
        assertEquals("a|b|c", TextOps.join(listOf("a", "b", "c"), "|"))
        assertEquals("", TextOps.join(emptyList(), ","))
    }

    @Test
    fun substringClampsBounds() {
        assertEquals("ell", TextOps.substring("hello", 1, 4))
        assertEquals("llo", TextOps.substring("hello", 2, null))
        assertEquals("hello", TextOps.substring("hello", -5, 999)) // clamped
        assertEquals("", TextOps.substring("hello", 3, 1)) // end < start -> clamped empty
    }
}
