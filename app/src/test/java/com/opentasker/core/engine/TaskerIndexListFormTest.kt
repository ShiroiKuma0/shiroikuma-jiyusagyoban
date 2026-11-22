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
    fun aListIsReadAsWrittenSoASpaceAtEitherEndStaysOnItsPart() {
        // Tasker trims the form only to spot the comma, then hands fr.h0 the text as written.
        assertEquals("cat", numbered().expand("%arr( 1,3)"))
        assertEquals("alpha", numbered().expand("%arr(1,3 )"))
        assertEquals("cat", numbered().expand("%arr(+;+ 1,3)"))
    }

    @Test
    fun anEmptyVariableBesideAColonInAListIsJustAPartThatNamesNothing() {
        // A list part isn't a range, so a range's empty-side rule doesn't empty the whole list.
        val withEmpty = numbered().apply { set("a", "") }
        assertEquals("beta", withEmpty.expand("%arr(%a:1,2)"))
        assertEquals("alpha", withEmpty.expand("%arr(1,2:%a)"))
    }

    @Test
    fun aLetterOutsideTheBasicPlaneStillMakesItThisAppsOwnOperator() {
        val boldA = String(Character.toChars(0x1D400))
        assertEquals("", numbered().expand("%arr(A,1)"))
        assertEquals("", numbered().expand("%arr($boldA,1)"))
    }

    @Test
    fun aLetterAfterASpaceIsStillTaskersList() {
        // This app's own operators only count right after the parenthesis, and Tasker trims, then reads a list.
        assertEquals("alpha", numbered().expand("%arr( a,1)"))
        assertEquals("alpha", numbered().expand("%arr(+;+ a,1)"))
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
        // With a comma too: `-` takes away what follows when it's a number, and `1,5` isn't one, so 0.
        // Read as a list of element numbers instead, it would name nothing and give empty.
        assertEquals("10", plain.expand("%n(-1,5)"))
    }
}
