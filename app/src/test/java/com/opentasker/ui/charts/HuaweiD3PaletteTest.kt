package com.opentasker.ui.charts

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Watch D3's cards join the Huawei report between resting heart rate and the day table, in the
 * order resting HR → blood pressure → skin temperature → arterial stiffness → ECG. Each neighbour pair
 * must stay apart for a red–green-deficient reader (白い熊): the CVD floor of the palette check.
 */
class HuaweiD3PaletteTest {
    private val order = listOf(
        "resting HR" to ChartPalette.RESTING_HEART_RATE,
        "blood pressure" to ChartPalette.BLOOD_PRESSURE,
        "skin temperature" to ChartPalette.TEMPERATURE,
        "arterial stiffness" to ChartPalette.HRV,
        "ECG" to ChartPalette.STEPS,
    )

    @Test
    fun `adjacent D3 cards stay apart under protan and deutan`() {
        order.zipWithNext().forEach { (a, b) ->
            val d = PaletteCheck.cvdSeparation(a.second.toArgb(), b.second.toArgb())
            println("${a.first} / ${b.first}: ΔE(cvd) = %.1f".format(d))
            assertTrue("${a.first} vs ${b.first}: ΔE $d", d >= 6.0)
        }
    }
}
