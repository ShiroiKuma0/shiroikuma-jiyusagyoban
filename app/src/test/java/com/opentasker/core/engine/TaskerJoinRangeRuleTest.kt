package com.opentasker.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When `%arr(+sep+...)` reads a range after the second `+` and when all of it is the separator.
 * Tasker 6.7.8 (decompiled `dq.X`) takes a range only when the text after the first `+` holds
 * another `+` and also a `:` or `,` with a digit, a `+$?`, or ends in `+` and one digit, all as
 * Android's regex reads them. Anything else joins with all of it, so `%arr(+;+12)` joins with
 * `;+12` rather than reading element 12.
 */
class TaskerJoinRangeRuleTest {

    private val animals = listOf("alpha", "beta", "cat", "dog")

    private fun numbered() = VariableStore().apply { animals.forEachIndexed { index, value -> set("arr${index + 1}", value) } }

    private fun stored() = VariableStore().apply { setArray("arr", animals) }

    @Test
    fun oneDigitAfterTheSecondPlusIsAnElement() {
        assertEquals("beta", numbered().expand("%arr(+;+2)"))
    }

    @Test
    fun twoDigitsWithNoColonOrCommaAreTheSeparator() {
        assertEquals("alpha;+12beta;+12cat;+12dog", numbered().expand("%arr(+;+12)"))
        assertEquals("alpha;+12beta;+12cat;+12dog", stored().expand("%arr(+;+12)"))
    }

    @Test
    fun aColonWithNoDigitIsTheSeparator() {
        assertEquals("alphax+:betax+:catx+:dog", numbered().expand("%arr(+x+:)"))
    }

    @Test
    fun aPlusRightAfterTheFirstIsTheSeparator() {
        // `.+\+\d` needs something before the second `+`.
        assertEquals("alpha+2beta+2cat+2dog", numbered().expand("%arr(++2)"))
    }

    @Test
    fun aRangeStillJoinsOnlyThatRange() {
        assertEquals("beta, cat", numbered().expand("%arr(+, +2:3)"))
        assertEquals("alpha;beta", numbered().expand("%arr(+;+1:2)"))
    }

    @Test
    fun aDigitEarlierMakesAnEmptyRangeThatJoinsEverything() {
        // `a:1+` passes Tasker's test, so the separator is `a:1` and the empty range is every element.
        assertEquals("alphaa:1betaa:1cata:1dog", numbered().expand("%arr(+a:1+)"))
    }

    @Test
    fun aCarriageReturnInTheSeparatorHidesTheDigit() {
        // Tasker's `(.|\n)*\d(.|\n)*` can't cross a `\r`, so a CRLF separator before a range joins with all of it.
        assertEquals("beta\ncat", numbered().apply { set("sep", "\n") }.expand("%arr(+%sep+2:3)"))
        assertEquals(
            listOf("alpha", "beta", "cat", "dog").joinToString("\r\n+2:3"),
            numbered().apply { set("sep", "\r\n") }.expand("%arr(+%sep+2:3)"),
        )
    }

    @Test
    fun aSearchRangeIsNotReadYetSoItJoinsWithAllOfIt() {
        // Tasker splits at `+$?`, not the last `+`, so `2:3` is part of the search, not a range.
        assertEquals(animals.joinToString(";+\$?a+2:3"), numbered().expand("%arr(+;+\$?a+2:3)"))
    }

    @Test
    fun aCommaAndADigitMakeARangeOfTwoDigits() {
        val twelve = VariableStore().apply { (1..12).forEach { set("arr$it", "v$it") } }
        assertEquals("v12", twelve.expand("%arr(+,+12)"))
    }

    @Test
    fun aDigitInAnyScriptCounts() {
        // Tasker runs its test on Android's regex, where \d is any script's decimal digit, and reads
        // the range with Integer.parseInt, which takes them too.
        val three = Char(0x663).toString()
        val four = Char(0x664).toString()
        assertEquals("cat;dog", numbered().expand("%arr(+;+$three,$four)"))
        assertEquals("cat", numbered().expand("%arr(+x+$three)"))
    }

    @Test
    fun aVerticalTabOrFormFeedHidesTheDigit() {
        // On Android `.` stops at a vertical tab and a form feed too, so neither lets a range through.
        listOf(Char(0x0B), Char(0x0C)).forEach { stop ->
            assertEquals(
                animals.joinToString("$stop+2:3"),
                numbered().apply { set("sep", stop.toString()) }.expand("%arr(+%sep+2:3)"),
            )
        }
    }
}
