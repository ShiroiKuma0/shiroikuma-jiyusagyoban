package com.opentasker.ui.charts.huawei

import com.opentasker.core.band.BandMetric
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.MetricSpec
import com.opentasker.ui.charts.MetricSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules this table lives by, enforced rather than described.
 *
 * Each of these is here because the failure it prevents is silent: a tuned gate on an
 * uncharacterised signal draws convincing rejection marks, a raw device number in a card headed
 * "Calories" reads as calories, and a swapped Loc pair renders perfectly in the wrong language.
 */
class HuaweiMetricSpecsTest {

    private val cjk = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")
    private val all = HuaweiMetricSpecs.ALL + HuaweiMetricSpecs.DIAGNOSTIC

    @Test
    fun `provisional means the gates really are off`() {
        // The invariant that keeps the standing rule true after the person who wrote it has moved
        // on. It fails the day someone tunes a gate without clearing the flag — which is exactly the
        // moment the card stops matching what it claims about itself.
        for (spec in all.filter { it.provisional }) {
            assertEquals("${spec.key}: Hampel must be off", 0, spec.hampelHalfWindow)
            assertEquals("${spec.key}: slew gate must be off", null, spec.slewPerStep)
            assertTrue("${spec.key}: no band ladder may be drawn", spec.bands.isEmpty())
        }
    }

    @Test
    fun `every row is provisional until this band has been measured`() {
        assertTrue(all.all { it.provisional })
    }

    @Test
    fun `keys are prefixed, so neither band can take the other's colour`() {
        for (spec in all) {
            assertTrue("${spec.key} must carry the hw: prefix", spec.key.startsWith(HuaweiKeys.PREFIX))
        }
        val hume = MetricSpecs.ALL.map { it.key }.toSet() +
            setOf(BandMetric.HEART_RATE, BandMetric.SPO2, BandMetric.STEPS_MINUTE)
        assertTrue(
            "a Huawei key must never equal a Hume key",
            all.none { it.key in hume },
        )
    }

    @Test
    fun `the one gate that is on needed no measuring`() {
        // A physical bound rather than a tuning: nobody takes 400 steps inside one minute.
        assertEquals(400.0, HuaweiMetricSpecs.STEPS.validMax, 0.0)
        // And a recorded zero is a real observation on this band, unlike the Hume band's.
        assertFalse(HuaweiMetricSpecs.STEPS.zeroIsNoReading)
    }

    @Test
    fun `raw device units are kept off the dashboard`() {
        // A card headed "Calories" over a number that is not calories reads as right, which is the
        // worst way to be wrong.
        assertTrue(HuaweiMetricSpecs.CALORIES !in HuaweiMetricSpecs.ALL)
        assertTrue(HuaweiMetricSpecs.DISTANCE !in HuaweiMetricSpecs.ALL)
        for (spec in HuaweiMetricSpecs.DIAGNOSTIC) {
            assertEquals("${spec.key} must carry no unit", "", spec.unit)
            assertEquals("${spec.key} must read grey", ChartPalette.UNKNOWN, spec.color)
        }
    }

    @Test
    fun `an undecoded field invents nothing`() {
        val spec = HuaweiMetricSpecs.forUnknown("unknown_10")
        assertEquals("hw:unknown_10", spec.key)
        assertEquals("", spec.unit)
        assertEquals(ChartPalette.UNKNOWN, spec.color)
        assertTrue(spec.bands.isEmpty())
        // Nothing is out of range, because we do not know what in-range would mean.
        assertEquals(-Double.MAX_VALUE, spec.validMin, 0.0)
        assertEquals(Double.MAX_VALUE, spec.validMax, 0.0)
    }

    @Test
    fun `both languages are filled in, and neither is in the other's slot`() {
        // Loc is (en, ja) and both are String, so a swapped pair compiles and renders every info
        // sheet in the wrong language. Twenty of them were inverted when this table was written.
        fun check(where: String, loc: Loc) {
            assertTrue("$where: English missing", loc.en.isNotBlank())
            assertTrue("$where: Japanese missing", loc.ja.isNotBlank())
            assertFalse("$where: CJK in the English slot — the pair is swapped", cjk.containsMatchIn(loc.en))
        }
        for (spec in all + listOf(HuaweiMetricSpecs.forUnknown("unknown_10"))) {
            check("${spec.key} label", spec.label)
            check("${spec.key} whatItIs", spec.info.whatItIs)
            check("${spec.key} howMeasured", spec.info.howMeasured)
            check("${spec.key} howToRead", spec.info.howToRead)
            if (spec.info.caveat.en.isNotBlank() || spec.info.caveat.ja.isNotBlank()) {
                check("${spec.key} caveat", spec.info.caveat)
            }
        }
    }

    @Test
    fun `the steps caveat states the zero-convention inversion in both languages`() {
        // The single most dangerous like-for-like trap between the two bands, and the card is the
        // only place a reader will meet it.
        val c = HuaweiMetricSpecs.STEPS.info.caveat
        assertTrue("English caveat must say absent is not zero", c.en.contains("OPPOSITE"))
        assertTrue("Japanese caveat must say the same", c.ja.contains("正反対"))
    }

    @Test
    fun `byKey resolves a bare storage name as well as a chart key`() {
        assertEquals(HuaweiMetricSpecs.HEART_RATE, HuaweiMetricSpecs.byKey("hr"))
        assertEquals(HuaweiMetricSpecs.HEART_RATE, HuaweiMetricSpecs.byKey(HuaweiKeys.HEART_RATE))
    }
}
