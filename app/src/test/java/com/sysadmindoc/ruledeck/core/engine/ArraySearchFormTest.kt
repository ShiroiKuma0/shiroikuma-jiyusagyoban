package com.sysadmindoc.ruledeck.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The array searches: `%arr(#?pattern)` reads the numbers of the elements that match,
 * `%arr($?pattern)` their values, and `%arr($sep?pattern)` the values joined by `sep`. An element
 * matches when it equals the pattern or Matches says so, and when none does the search reads 0.
 */
class ArraySearchFormTest {
    private val animals = listOf("alpha", "beta", "cat", "bat")

    private fun numbered() = VariableStore().apply { animals.forEachIndexed { index, value -> set("arr${index + 1}", value) } }

    @Test
    fun aPositionSearchReadsTheNumbersOfTheElementsThatMatch() {
        assertEquals("3,4", numbered().expand("%arr(#?*at)"))
        assertEquals("2", numbered().expand("%arr(#?beta)"))
        assertEquals("2,3,4", numbered().expand("%arr(#?c*/b*)"))
        assertEquals("0", numbered().expand("%arr(#?nope)"))
    }

    @Test
    fun aValueSearchReadsTheValuesThatMatchJoinedByWhatsBeforeTheQuestionMark() {
        assertEquals("cat,bat", numbered().expand("%arr(\$?*at)"))
        assertEquals("cat; bat", numbered().expand("%arr(\$; ?*at)"))
        assertEquals("0", numbered().expand("%arr(\$?nope)"))
    }

    @Test
    fun aRegexPatternStartsAtItsTildeR() {
        assertEquals("1,2", numbered().expand("%arr(#?~Ra$)"))
        // With ~R in the text the split is the ? before it, so a ? inside the regex stays in it.
        assertEquals("beta,bat", numbered().expand("%arr(\$?~Rb.?t)"))
    }

    @Test
    fun aVariableInThePatternIsFilledInFirst() {
        assertEquals("3,4", numbered().apply { set("pat", "*at") }.expand("%arr(#?%pat)"))
    }

    @Test
    fun aJoinReadsAValueSearchAfterItsPlusDollar() {
        assertEquals("cat;bat", numbered().expand("%arr(+;+\$?*at)"))
        // The form splits at the first +$?, so the joiner can hold a +.
        assertEquals("cata+bbat", numbered().expand("%arr(+a+b+\$?*at)"))
    }

    @Test
    fun aVariableWithNoElementsFindsNothing() {
        assertEquals("0", VariableStore().apply { set("arr", "cat") }.expand("%arr(#?cat)"))
        assertEquals("0", VariableStore().expand("%arr(\$?cat)"))
    }

    @Test
    fun aJoinedSearchOnAVariableWithNoElementsFindsNothingToo() {
        // The range is read as %arr($?cat) on its own, so the base's own value never counts.
        assertEquals("0", VariableStore().apply { set("arr", "cat") }.expand("%arr(+;+\$?cat)"))
        assertEquals("0", VariableStore().apply { set("n", "5") }.expand("%n(+;+\$?5)"))
    }

    @Test
    fun aSearchFindsElementZeroAndANumberWrittenWithALeadingZero() {
        // A search reads whatever follows the base as a number, where the other array forms skip these.
        val variables = VariableStore().apply {
            set("arr0", "cat")
            set("arr2", "cat")
            set("arr07", "dog")
        }
        assertEquals("0,2", variables.expand("%arr(#?cat)"))
        assertEquals("7", variables.expand("%arr(#?dog)"))
        // A value search reads each value back by its number, and number 0 reads as nothing there,
        // like any element that isn't set, so only element 2's value shows.
        assertEquals("cat", variables.expand("%arr(\$?cat)"))
    }

    @Test
    fun aValueSearchReadsTheElementItsNumberNames() {
        val variables = VariableStore().apply {
            set("arr7", "cat")
            set("arr07", "dog")
        }
        // %arr07 matches as number 7, and the value read back for 7 is %arr7's.
        assertEquals("7", variables.expand("%arr(#?dog)"))
        assertEquals("cat", variables.expand("%arr(\$?dog)"))
        variables.set("arr07", "cat")
        // Both are number 7, so both count.
        assertEquals("7,7", variables.expand("%arr(#?cat)"))
        assertEquals("cat,cat", variables.expand("%arr(\$?cat)"))
    }

    @Test
    fun aKeptListWithAnElementZeroReadsAsItDidBeforeSearches() {
        fun kept() = VariableStore().apply {
            setArray("parts", listOf("cat", "bat"))
            set("parts", "cat")
        }
        val withZero = kept().apply { set("parts0", "cat") }
        assertEquals(kept().expand("%parts(#?cat)"), withZero.expand("%parts(#?cat)"))
    }

    @Test
    fun aValueSearchFillsInItsTrimmedTextSoAVariablesSpacesStay() {
        // The text is trimmed and then filled in, so a space a variable ends with is part of the pattern.
        val variables = VariableStore().apply {
            set("arr1", "cat ")
            set("arr2", "cat")
            set("arr3", " ")
            set("pat", "cat ")
            set("space", " ")
        }
        assertEquals("cat ", variables.expand("%arr(\$?%pat)"))
        assertEquals(" ", variables.expand("%arr(\$?%space)"))
        assertEquals("cat", variables.expand("%arr(\$?cat )"))
    }

    @Test
    fun aDoesntMatchRegexReadsTheElementsItDoesntFind() {
        assertEquals("1,2", numbered().expand("%arr(#?!~Rt$)"))
        assertEquals("alpha,beta", numbered().expand("%arr(\$?!~Rt$)"))
    }

    @Test
    fun aLineBreakOtherThanNewlineEndsTheJoinerAndThePattern() {
        val twice = { value: String -> VariableStore().apply { set("arr1", value); set("arr2", value) } }
        // `.` stops at a carriage return, so the split is the ? before it and the pattern ends there.
        assertEquals("bab", twice("b").expand("%arr(\$a?b\r?c)"))
        // A newline is what `(.|\n)` lets through, so the joiner can hold one.
        assertEquals("y?x\ny", twice("y").expand("%arr(\$?x\n?y)"))
    }

    @Test
    fun theListsSplitTextKeepsDontSearchYet() {
        // Not searched yet: these read the way they did before searches came in (see the roadmap's
        // array leftovers), joining the list with the form like any other form a kept list doesn't read.
        val kept = VariableStore().apply { setArray("parts", listOf("cat", "bat")) }
        assertEquals("cat#?catbat", kept.expand("%parts(#?cat)"))
        assertEquals("cat;+\$?catbat", kept.expand("%parts(+;+\$?cat)"))
    }
}
