package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both languages, everywhere.
 *
 * The [Loc] type makes a *missing* translation a compile error — there is no single-argument
 * constructor. What it cannot catch is a **blank** one, or a Japanese string pasted into the English
 * slot, so that is what this checks: every string the 「健康」 window can display, in both languages,
 * walked from the metric table rather than listed by hand. A metric added without English text fails
 * here rather than shipping half-translated.
 */
class BandStringsTest {

    private fun everyLoc(): List<Pair<String, Loc>> = buildList {
        MetricSpecs.ALL.forEach { spec ->
            add("${spec.key}.label" to spec.label)
            add("${spec.key}.whatItIs" to spec.info.whatItIs)
            add("${spec.key}.howMeasured" to spec.info.howMeasured)
            add("${spec.key}.howToRead" to spec.info.howToRead)
            spec.bands.forEachIndexed { i, rung -> add("${spec.key}.band$i" to rung.label) }
        }
        listOf(
            "bp" to MetricSpecs.BLOOD_PRESSURE_INFO,
            "sleep" to MetricSpecs.SLEEP_INFO,
        ).forEach { (key, info) ->
            add("$key.whatItIs" to info.whatItIs)
            add("$key.howMeasured" to info.howMeasured)
            add("$key.howToRead" to info.howToRead)
            add("$key.caveat" to info.caveat)
        }
        SleepShape.ROWS.forEach { add("stage.$it" to SleepShape.labelOf(it)) }
        add("stage.unknown" to SleepShape.labelOf('4'))
    }

    @Test
    fun `no displayed string is blank in either language`() {
        everyLoc().forEach { (name, loc) ->
            assertTrue("$name is blank in English", loc.en.isNotBlank())
            assertTrue("$name is blank in Japanese", loc.ja.isNotBlank())
        }
    }

    /** A CJK character in the English slot means a translation was skipped by pasting. */
    @Test
    fun `the English slot contains no Japanese`() {
        val cjk = Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]")
        everyLoc().forEach { (name, loc) ->
            // "REM" is the same word in both; everything else must actually differ.
            if (name.endsWith("stage.3")) return@forEach
            assertFalse(
                "$name still holds Japanese in its English slot: ${loc.en.take(40)}",
                cjk.containsMatchIn(loc.en),
            )
        }
    }

    @Test
    fun `the language tag parses tolerantly and never throws`() {
        assertEquals(BandLanguage.EN, BandLanguage.parse("en-US"))
        assertEquals(BandLanguage.EN, BandLanguage.parse("en"))
        assertEquals(BandLanguage.EN, BandLanguage.parse("EN_us"))
        assertEquals(BandLanguage.JA, BandLanguage.parse("ja-JP"))
        assertEquals(BandLanguage.JA, BandLanguage.parse("ja"))
        assertEquals(BandLanguage.JA, BandLanguage.parse("日本語"))
        // A typo in the settings task must not stop the window opening.
        assertEquals(BandLanguage.DEFAULT, BandLanguage.parse("klingon"))
        assertEquals(BandLanguage.DEFAULT, BandLanguage.parse(""))
        assertEquals(BandLanguage.DEFAULT, BandLanguage.parse(null))
    }

    @Test
    fun `the default is English, as asked for`() {
        assertEquals(BandLanguage.EN, BandLanguage.DEFAULT)
        assertEquals("en-US", BandLanguage.EN.tag)
        assertEquals("ja-JP", BandLanguage.JA.tag)
    }

    @Test
    fun `indexing a Loc picks the matching side`() {
        val l = Loc("Heart Rate", "心拍")
        assertEquals("Heart Rate", l[BandLanguage.EN])
        assertEquals("心拍", l[BandLanguage.JA])
        assertNotEquals(l[BandLanguage.EN], l[BandLanguage.JA])
    }

    /**
     * The headroom line names a BLE stream, and the raw protocol keys are not display text.
     *
     * `hrv` printed beside a card no longer called HRV read as a contradiction — the whole reason the
     * map exists. Every stream the census can nominate as shallowest must have a name.
     */
    @Test
    fun `every stream the headroom line can name has a label in both languages`() {
        val nameable = listOf("hr", "hrv", "spo2", "temp", "detail", "sleep", "daily")
        nameable.forEach { key ->
            BandLanguage.entries.forEach { lang ->
                assertTrue("$key has no $lang label", BandText.stream(key, lang).isNotBlank())
            }
            // Japanese never coincides with a protocol key, so it is the reliable proof that a real
            // label was found rather than the fallback returning the key. (English "sleep" happens to
            // equal its key, which is correct and not evidence of anything.)
            assertNotEquals(
                "$key fell through to the raw protocol key",
                key, BandText.stream(key, BandLanguage.JA),
            )
        }
        // The 0x56 stream must not reintroduce the label the band state index was renamed away from.
        assertEquals("band state", BandText.stream("hrv", BandLanguage.EN))
        assertFalse(BandText.stream("hrv", BandLanguage.EN).contains("HRV"))
    }

    /** A stream a firmware update lights up must appear as itself, not be silently mislabelled. */
    @Test
    fun `an unknown stream key passes through unchanged`() {
        assertEquals("workout", BandText.stream("workout", BandLanguage.EN))
        assertEquals("hr_1s", BandText.stream("hr_1s", BandLanguage.JA))
    }

    @Test
    fun `the health index names every component in both languages`() {
        val r = HealthIndex.compute(
            HealthIndexInputs(55.0, 8.0, 96.0, 480, 0.35),
        )
        r.components.forEach {
            assertTrue("${it.key} label", it.label.en.isNotBlank() && it.label.ja.isNotBlank())
            assertTrue("${it.key} scale", it.scale.en.isNotBlank() && it.scale.ja.isNotBlank())
        }
        BandLanguage.entries.forEach { lang ->
            assertTrue(r.band[lang].isNotBlank())
        }
    }

    @Test
    fun `a missing component explains itself in both languages`() {
        val r = HealthIndex.compute(HealthIndexInputs(null, null, null, null, null))
        r.components.forEach {
            val reason = it.missingReason
            assertTrue("${it.key} must say why it is missing", reason != null)
            assertTrue(reason!!.en.isNotBlank() && reason.ja.isNotBlank())
        }
    }
}
