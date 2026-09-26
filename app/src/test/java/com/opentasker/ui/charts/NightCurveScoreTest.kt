package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The night curve's own score, and the columns the register was missing.
 *
 * Both come from the same morning. 白い熊, 2026-09-26, looking at a night whose heart rate fell
 * **0 bpm against a usual 11**: *"the heart-rate-through-the-night we see here it was really bad —
 * so it must have its color scoring on the left also, indicating red or worse."* The card said so in
 * words and gave the block no colour at all, under six rows that were each tinted for far less.
 */
class NightCurveScoreTest {

    /** The real night: it did not fall. Two whole smallest-worthwhile-changes short of usual. */
    @Test
    fun `a night that did not fall at all scores the bottom of the ladder`() {
        val usual = 11.0
        val lastNight = 0.0
        assertEquals(5, MarkerHistory.descentStep(usual - lastNight))
    }

    /**
     * SYMMETRIC ABOUT USUAL, and usual is 3.
     *
     * A night that fell by exactly the usual amount is the definition of normal; a ladder that
     * called it "good" would have nothing left to say about a night that beat it. The unit is the
     * heart rate's own published smallest-worthwhile-change, because a drop is a difference of two
     * heart rates.
     */
    @Test
    fun `the ladder is symmetric and exactly-usual is the middle step`() {
        val u = MarkerHistory.MEANINGFUL_DROP_BPM
        assertEquals(5.0, u, 0.0)
        assertEquals("fell exactly as usual", 3, MarkerHistory.descentStep(0.0))
        assertEquals("a shade short", 3, MarkerHistory.descentStep(u - 0.1))
        assertEquals("one short", 4, MarkerHistory.descentStep(u))
        assertEquals("two short", 5, MarkerHistory.descentStep(2 * u))
        assertEquals("a shade deeper", 3, MarkerHistory.descentStep(-u + 0.1))
        assertEquals("one deeper", 2, MarkerHistory.descentStep(-u))
        assertEquals("two deeper", 1, MarkerHistory.descentStep(-2 * u))
    }

    /**
     * IT GRADES BY VALENCE, WHICH THE ROWS ABOVE IT DELIBERATELY DO NOT.
     *
     * [DeviationStrip]'s rows grade by DISTANCE from usual and never by direction, because a row
     * that coloured a ten-hour night best-step yellow above a box explaining that the long night was
     * the thing to notice teaches a reader to ignore the colours. The descent has no such ambiguity
     * — a heart rate that fails to fall overnight is worse in one direction only — and the card
     * already prints the shallow line with a warning. So here the colour agrees with the sentence.
     */
    @Test
    fun `falling further is better and falling short is worse, unlike the rows`() {
        val deeper = MarkerHistory.descentStep(-2 * MarkerHistory.MEANINGFUL_DROP_BPM)
        val shorter = MarkerHistory.descentStep(2 * MarkerHistory.MEANINGFUL_DROP_BPM)
        assertTrue("1 is the best step and 5 the worst", deeper < shorter)
        assertEquals(1, deeper)
        assertEquals(5, shorter)
    }

    /** The comparison overload must read the shortfall the right way round, or every colour inverts. */
    @Test
    fun `the comparison overload measures the SHORTFALL, not the delta`() {
        val usual = MarkerHistory.Descent(75.0, 64.0)          // an 11 bpm drop
        val flat = MarkerHistory.DescentComparison(MarkerHistory.Descent(64.0, 64.0), usual)
        val deep = MarkerHistory.DescentComparison(MarkerHistory.Descent(79.0, 58.0), usual)
        assertEquals("fell 0 against a usual 11", 5, MarkerHistory.descentStep(flat))
        assertEquals("fell 21 against a usual 11", 1, MarkerHistory.descentStep(deep))
    }

    /**
     * THE TABLE CARRIES EVERY METRIC THE REPORT SHOWS.
     *
     * 白い熊, 2026-09-26: *"this table doesn't have everything, it lacks the going-to-bed-hr,
     * hr-through-the-night: it must have all the metrics, so it's more descriptive."* The register
     * is the screen read AFTERWARDS, when the question is what a run of nights looked like, and a
     * quantity that exists on the report and not in the record cannot be asked about at all.
     *
     * `Night curve` is the one that DRAWS rather than prints, and it is here because a swing in bpm
     * is not heart-rate-through-the-night: 14 bpm that fell early and 14 bpm that rose at four in
     * the morning are the same number and different nights.
     */
    @Test
    fun `the night table has a column for every quantity the report shows`() {
        val en = BandText.registerColumns.map { it[BandLanguage.EN] }
        for (needed in listOf("Bed HR", "HR swing", "Night curve", "Breaths")) {
            assertTrue("the table is missing $needed: $en", needed in en)
        }
        // …and the ones it always had, so this test fails if a column is lost rather than added.
        for (kept in listOf("Date", "Woke", "Night HR", "Asleep", "Deep", "Deep+REM", "Low HR", "HRV", "SpO₂")) {
            assertTrue("$kept went missing: $en", kept in en)
        }
        assertEquals(13, BandText.registerColumns.size)
        // Every heading is translated: a column that falls back to English is a column nobody
        // reviewed, and this table is read in both languages.
        for (c in BandText.registerColumns) {
            assertTrue("untranslated heading: ${c[BandLanguage.EN]}", c[BandLanguage.JA].isNotBlank())
        }
    }

    /**
     * THE TABLE'S CURVE CELL IS SCORED BY THE DESCENT, NOT BY THE SWING.
     *
     * The column shipped coloured by `hrSwingStep` and 白い熊 caught it on the first screenful
     * (2026-09-26): 09-26 never fell at all, the report's bar for that night was dark red, and the
     * table drew the same night blue — because its swing, 14 bpm against a usual 12, is perfectly
     * ordinary. **A swing cannot tell a fall from a rise.** Two numbers for one quantity is the
     * failure this project trips over most often, so the two screens now share one function.
     */
    @Test
    fun `a night that rose and a night that fell score alike on the swing and differently on the descent`() {
        val usual = MarkerHistory.Descent(75.0, 64.0)              // an 11 bpm drop
        // Both move 14 bpm, so both are "ordinary" to the swing.
        val fell = MarkerHistory.DescentComparison(MarkerHistory.Descent(78.0, 64.0), usual)
        val rose = MarkerHistory.DescentComparison(MarkerHistory.Descent(64.0, 64.0), usual)
        assertEquals("fell 14 against a usual 11", 3, MarkerHistory.descentStep(fell))
        assertEquals("never fell at all", 5, MarkerHistory.descentStep(rose))
        // …and the register must reach for the descent's step, not the swing's.
        val screen = com.opentasker.ProductionSources.read(
            "com/opentasker/ui/charts/SessionRegisterScreen.kt",
        )
        assertTrue(
            "the curve cell takes the curve's own step",
            "CurveCell(n?.hrCurve.orEmpty(), n?.hrCurveStep)" in screen,
        )
        val register = com.opentasker.ProductionSources.read(
            "com/opentasker/ui/charts/SessionRegister.kt",
        )
        assertTrue(
            "and that step comes from the report's own grading, not a second one",
            "MarkerHistory.descentStep(MarkerHistory.DescentComparison(last, usual))" in register,
        )
    }

    /**
     * Temperature stays OUT, and this test is why it will not quietly come back.
     *
     * "All the metrics" means every one this band measures. The Huawei has no skin-temperature
     * sensor, so the column was a dash on every line and was dropped on 2026-09-03; the string is
     * kept for the Hume report, which does measure it.
     */
    @Test
    fun `temperature is not a column on a band with no thermometer`() {
        assertTrue(BandText.regColTemp[BandLanguage.EN].isNotBlank())
        assertTrue(BandText.regColTemp !in BandText.registerColumns)
    }
}
