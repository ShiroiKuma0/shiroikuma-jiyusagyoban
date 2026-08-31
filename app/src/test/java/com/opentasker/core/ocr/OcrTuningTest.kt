package com.opentasker.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings-to-engine conversion. These knobs reach the detector from integer sliders, and a
 * mis-scaled one does not throw — it quietly detects nothing, or crops every line in half, and reads
 * as a bad model rather than a bad setting.
 */
class OcrTuningTest {

    @Test
    fun `the defaults are the measured values`() {
        val tuning = OcrTuning.DEFAULT

        assertEquals(1600, tuning.longSide)
        assertEquals(0.30f, tuning.binaryThreshold, 0.0001f)
        assertEquals(0.60f, tuning.boxScoreThreshold, 0.0001f)
        assertEquals(1.5f, tuning.unclipRatio, 0.0001f)
    }

    @Test
    fun `slider integers convert to the fractions the detector expects`() {
        // 45 must become 0.45, not 45 or 4.5 — the whole point of one conversion in one place.
        val tuning = OcrTuning.from(longSide = 1280, binaryPercent = 45, boxScorePercent = 25, unclipTenths = 22)

        assertEquals(1280, tuning.longSide)
        assertEquals(0.45f, tuning.binaryThreshold, 0.0001f)
        assertEquals(0.25f, tuning.boxScoreThreshold, 0.0001f)
        assertEquals(2.2f, tuning.unclipRatio, 0.0001f)
    }

    @Test
    fun `values below the range are clamped rather than passed through`() {
        // A 0 unclip ratio would decapitate every line; a 0 long side would produce no detection at all.
        val tuning = OcrTuning.from(longSide = 0, binaryPercent = 0, boxScorePercent = 0, unclipTenths = 0)

        assertEquals(OcrTuning.LONG_SIDE_MIN, tuning.longSide)
        assertEquals(OcrTuning.BINARY_PERCENT_MIN / 100f, tuning.binaryThreshold, 0.0001f)
        assertEquals(OcrTuning.BOX_SCORE_PERCENT_MIN / 100f, tuning.boxScoreThreshold, 0.0001f)
        assertEquals(OcrTuning.UNCLIP_TENTHS_MIN / 10f, tuning.unclipRatio, 0.0001f)
    }

    @Test
    fun `values above the range are clamped too`() {
        val tuning = OcrTuning.from(
            longSide = 99_999, binaryPercent = 500, boxScorePercent = 500, unclipTenths = 500,
        )

        assertEquals(OcrTuning.LONG_SIDE_MAX, tuning.longSide)
        assertEquals(OcrTuning.BINARY_PERCENT_MAX / 100f, tuning.binaryThreshold, 0.0001f)
        assertEquals(OcrTuning.BOX_SCORE_PERCENT_MAX / 100f, tuning.boxScoreThreshold, 0.0001f)
        assertEquals(OcrTuning.UNCLIP_TENTHS_MAX / 10f, tuning.unclipRatio, 0.0001f)
    }

    @Test
    fun `the stored defaults round-trip through the conversion`() {
        // What the settings store holds out of the box must rebuild DEFAULT exactly, or a fresh install
        // silently runs on different thresholds from the ones every measurement was taken with.
        val rebuilt = OcrTuning.from(
            OcrTuning.DEFAULT_LONG_SIDE,
            OcrTuning.DEFAULT_BINARY_PERCENT,
            OcrTuning.DEFAULT_BOX_SCORE_PERCENT,
            OcrTuning.DEFAULT_UNCLIP_TENTHS,
        )

        assertEquals(OcrTuning.DEFAULT, rebuilt)
    }
}
