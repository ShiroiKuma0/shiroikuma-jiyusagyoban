package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tasker's array searches (decompiled 6.7.8, `dq.X`, `dq.c0`, `fr.m0`): `%arr(#?pattern)` reads the
 * numbers of the elements that match, `%arr($?pattern)` their values, and `%arr($sep?pattern)` the
 * values joined by `sep`. An element matches when it equals the pattern or Tasker's Matches says
 * so, and when none does the search reads 0.
 */
class TaskerArraySearchTest {
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
        // Tasker splits at the first +$?, so the joiner can hold a +.
        assertEquals("cata+bbat", numbered().expand("%arr(+a+b+\$?*at)"))
    }

    @Test
    fun aVariableWithNoElementsFindsNothing() {
        assertEquals("0", VariableStore().apply { set("arr", "cat") }.expand("%arr(#?cat)"))
        assertEquals("0", VariableStore().expand("%arr(\$?cat)"))
    }
}
