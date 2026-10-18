package com.opentasker.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skin temperature counts its own nights (白い熊, 2026-10-18): the Watch D3 is the first Huawei
 * device with the sensor, so the twenty-odd nights before it on record carry none, and must not make
 * a one-night median look like an established normal. The first five nights are drawn yellow — a
 * value with no judgement — and the ordinary steps start on the sixth.
 */
class TemperatureCollectingTest {
    private fun reading(history: List<Double>, value: Double) = Recovery.band(
        RecoveryMarker.TEMPERATURE, value, history, Recovery.TEMP_MEANINGFUL_C,
        Recovery.temperatureConfidence(history), counted = false, oneSidedHigh = true,
    )

    @Test
    fun `the first five nights are collecting, yellow, and ungraded`() {
        val nights = listOf(33.8, 33.6, 34.0, 33.7, 33.9)
        for (i in nights.indices) {
            val r = reading(nights.take(i), nights[i])
            assertTrue("night ${i + 1}", r.temperatureCollecting)
            assertNull(r.scaleStep)
            assertEquals(COLLECTING_STEP, r.displayStep)
            assertEquals(i, r.nights)
        }
    }

    @Test
    fun `the sixth night is graded against the five before it`() {
        val history = listOf(33.8, 33.6, 34.0, 33.7, 33.9)
        val usual = reading(history, 33.9)
        assertFalse(usual.temperatureCollecting)
        assertEquals(3, usual.displayStep)
        // 0.3 °C is the smallest worthwhile change; 34.4 is 0.6 over the 33.8 median.
        val warm = reading(history, 34.4)
        assertEquals(RecoveryBand.HIGH, warm.band)
        assertTrue(warm.displayStep!! >= 4)
        // The whole 1–5 scale (白い熊, 2026-10-18): a cool night is coloured 2 or 1 — but never adverse.
        val cool = reading(history, 33.0)
        assertEquals(1, cool.displayStep)
        assertEquals(2, reading(history, 33.4).displayStep)
        assertFalse(cool.adverse)
    }

    @Test
    fun `off-wrist readings do not make a night`() {
        val night = SleepSession(startMs = 0, endMs = 60_000L * 10, runs = emptyList())
        val points = listOf(29.9, 30.6, 33.8, 33.9).mapIndexed { i, v -> ChartPoint(i * 60_000L, v) }
        assertEquals(33.85, RecoverySource.skinTemp(night, points)!!, 1e-9)
    }
}
