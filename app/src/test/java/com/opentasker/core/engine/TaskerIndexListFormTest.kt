package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tasker's `%arr(1,3)`: a form with a comma lists element numbers (decompiled 6.7.8, `fr.h0`), in
 * the order given, skipping a part that isn't a whole number. It also works as the range of
 * `%arr(+sep+1,3)`.
 */
class TaskerIndexListFormTest {

    private val animals = listOf("alpha", "beta", "cat", "dog")

    private fun numbered() = VariableStore().apply { animals.forEachIndexed { index, value -> set("arr${index + 1}", value) } }

    @Test
    fun aCommaListReadsThoseElementsInThatOrder() {
        assertEquals("alpha,cat", numbered().expand("%arr(1,3)"))
        assertEquals("dog,alpha,alpha", numbered().expand("%arr(4,1,1)"))
    }

    @Test
    fun aPartThatIsntAWholeNumberNamesNothing() {
        // Kotlin's toIntOrNull, which Tasker uses, doesn't trim, so " 3" isn't 3.
        assertEquals("alpha", numbered().expand("%arr(1, 3)"))
        assertEquals("beta", numbered().expand("%arr(2,9)"))
        assertEquals("", numbered().expand("%arr(,)"))
    }

    @Test
    fun aCommaListIsARangeAfterTheSecondPlus() {
        assertEquals("alpha;cat", numbered().expand("%arr(+;+1,3)"))
        // The stored array Split Text keeps has no list form, so that still joins with all of it.
        val stored = VariableStore().apply { setArray("arr", animals) }
        assertEquals(animals.joinToString(";+1,3"), stored.expand("%arr(+;+1,3)"))
    }

    @Test
    fun aGapIsSkippedByNumber() {
        val gapped = VariableStore().apply {
            set("arr1", "a")
            set("arr3", "c")
        }
        assertEquals("a,c", gapped.expand("%arr(1,3)"))
    }

    @Test
    fun aPlainVariableKeepsItsOwnOperators() {
        val plain = VariableStore().apply { set("n", "10") }
        assertEquals("15", plain.expand("%n(+5)"))
    }
}
