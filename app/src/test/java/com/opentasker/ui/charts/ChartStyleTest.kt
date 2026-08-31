package com.opentasker.ui.charts

import androidx.compose.ui.graphics.Color
import com.opentasker.core.band.BandMetric
import com.opentasker.ui.theme.ThemePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settable chart values, and the promise that the defaults change nothing.
 *
 * Lifting thirty-five compiled-in constants into settings has one failure mode worth guarding: a
 * default that does not reproduce what shipped. Nobody notices at review time — the charts still
 * render — and the first sign is a screen that looks subtly wrong on a device that never touched a
 * slider.
 */
class ChartStyleTest {

    private val style = ChartStyle.DEFAULT

    @Test
    fun `the default style is the one the charts shipped with`() {
        assertEquals(132, style.previewHeight.value.toInt())
        assertEquals(320, style.detailHeight.value.toInt())
        assertEquals(ChartCurveMode.PCHIP, style.curve)
        assertEquals(24 * 3_600_000L, style.defaultSpanMs)
        assertTrue(style.showGrid && style.showDots && style.showRejected && style.showGaps)
    }

    @Test
    fun `the default series colours are the validated ones`() {
        assertEquals(ChartPalette.HEART_RATE, style.heartRate)
        assertEquals(ChartPalette.SPO2, style.spo2)
        assertEquals(ChartPalette.TEMPERATURE, style.temperature)
        assertEquals(ChartPalette.STEPS, style.steps)
        assertEquals(ChartPalette.SYSTOLIC, style.systolic)
        assertEquals(ChartPalette.DIASTOLIC, style.diastolic)
    }

    @Test
    fun `the default sleep stages are the validated ones, keyed to the RAW codes`() {
        assertEquals(ChartPalette.SLEEP_DEEP, style.sleepStage('1'))
        assertEquals(ChartPalette.SLEEP_LIGHT, style.sleepStage('2'))
        assertEquals(ChartPalette.SLEEP_REM, style.sleepStage('3'))
        assertEquals(ChartPalette.SLEEP_AWAKE, style.sleepStage('5'))
    }

    /** Code 4 has never been observed; if the firmware ever emits it, it must not silently borrow. */
    @Test
    fun `an unknown sleep code gets the unknown colour, not a stage's`() {
        assertEquals(ChartPalette.UNKNOWN, style.sleepStage('4'))
    }

    @Test
    fun `a metric's colour is looked up by the key its spec carries`() {
        MetricSpecs.ALL.forEach { spec ->
            assertNotEquals(
                "${spec.key} has no colour of its own",
                Color.Unspecified,
                style.colorFor(spec.key),
            )
        }
        assertEquals(style.heartRate, style.colorFor(BandMetric.HEART_RATE))
        // 心拍変動 and ストレス are the same firmware byte, so they are one series and one colour.
        assertEquals(style.colorFor(BandMetric.HRV), style.colorFor(BandMetric.STRESS))
    }

    @Test
    fun `percentages arrive as fractions`() {
        val prefs = ThemePrefs.DEFAULT.copy(
            chartFillOpacityPct = 50,
            chartGlowOpacityPct = 0,
            chartHypnogramBandPct = 80,
        )
        val s = ChartStyle.from(prefs)
        assertEquals(0.5f, s.fillAlpha, 1e-6f)
        assertEquals(0f, s.glowAlpha, 1e-6f)
        assertEquals(0.8f, s.hypnogramBand, 1e-6f)
    }

    @Test
    fun `the grid colour is tinted by its own opacity`() {
        val s = ChartStyle.from(ThemePrefs.DEFAULT.copy(chartGridColor = 0xFFFFFFFF.toInt(), chartGridOpacityPct = 40))
        assertEquals(0.4f, s.grid.alpha, 1e-6f)
    }

    /** A stored value from a build that knew a mode this one does not must not crash the charts. */
    @Test
    fun `an unknown curve mode falls back to PCHIP`() {
        val s = ChartStyle.from(ThemePrefs.DEFAULT.copy(chartCurveMode = "SPLINEY"))
        assertEquals(ChartCurveMode.PCHIP, s.curve)
    }

    @Test
    fun `every curve name the settings offer actually parses`() {
        ThemePrefs.CHART_CURVES.forEach { name ->
            assertEquals(
                name,
                ChartStyle.from(ThemePrefs.DEFAULT.copy(chartCurveMode = name)).curve.name,
            )
        }
    }
}
