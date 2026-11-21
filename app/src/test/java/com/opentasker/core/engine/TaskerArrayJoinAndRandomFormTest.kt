package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasker's `%arr(+sep)`, `%arr(+sep+range)` and `%arr(*)` forms, as its user guide gives them:
 * `%arr(+=)` reads alpha=beta=cat=dog, `%arr(+>+2:4)` reads beta>cat>dog and `%arr(*)` reads one
 * element at random.
 */
class TaskerArrayJoinAndRandomFormTest {

    private val animals = listOf("alpha", "beta", "cat", "dog")

    /** Tasker's own array: numbered variables %arr1 to %arr4. */
    private fun numbered() = VariableStore().apply { animals.forEachIndexed { index, value -> set("arr${index + 1}", value) } }

    /** The same elements in the stored array that Split Text and the array actions keep, 0-based. */
    private fun stored() = VariableStore().apply { setArray("arr", animals) }

    @Test
    fun aPlusJoinsEveryElementWithWhatFollowsIt() {
        assertEquals("alpha=beta=cat=dog", numbered().expand("%arr(+=)"))
        assertEquals("alpha=beta=cat=dog", stored().expand("%arr(+=)"))
        assertEquals("alpha, beta, cat, dog", numbered().expand("%arr(+, )"))
    }

    @Test
    fun aSecondPlusBeforeARangeJoinsOnlyThatRange() {
        assertEquals("beta>cat>dog", numbered().expand("%arr(+>+2:4)"))
        assertEquals("alpha-beta", numbered().expand("%arr(+-+:2)"))
        // The stored array counts from 0, as its other range forms do.
        assertEquals("beta>cat>dog", stored().expand("%arr(+>+1:3)"))
    }

    @Test
    fun theSeparatorCanComeFromAVariable() {
        val variables = numbered().apply { set("sep", ";") }
        assertEquals("alpha;beta;cat;dog", variables.expand("%arr(+%sep)"))
    }

    @Test
    fun aStarReadsOneElementAtRandom() {
        val numbered = numbered()
        val stored = stored()
        val seen = (1..200).map { numbered.expand("%arr(*)") }.toSet()
        assertTrue(seen.toString(), seen.all { it in animals })
        assertEquals(animals.toSet(), seen)
        assertTrue((1..50).map { stored.expand("%arr(*)") }.all { it in animals })
    }

    @Test
    fun anEmptyArrayReadsEmpty() {
        val variables = VariableStore().apply { set("other", "x") }
        assertEquals("", variables.expand("%arr(+=)"))
        assertEquals("", variables.expand("%arr(*)"))
    }

    @Test
    fun aPlainNumberKeepsItsOwnMathsOperators() {
        // %n has no elements, so (+5) still adds and (*2) still multiplies.
        val variables = VariableStore().apply { set("n", "10") }
        assertEquals("15", variables.expand("%n(+5)"))
        assertEquals("20", variables.expand("%n(*2)"))
    }
}
