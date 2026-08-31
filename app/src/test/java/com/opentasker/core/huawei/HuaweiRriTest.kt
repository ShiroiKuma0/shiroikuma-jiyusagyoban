package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The RR-interval decoder, checked against Huawei Health's own lists for the same wrist and hours.
 *
 * The fixture is the real file pulled from 白い熊's band on 2026-08-22 at 17:31. Its ground truth is
 * two photographs of Health: a heart-rate list and an HRV list, both for that afternoon.
 */
class HuaweiRriTest {

    private fun windows() = HuaweiRri.parse(
        checkNotNull(javaClass.getResourceAsStream("/huawei/rrisqi-2026-08-22.bin")).readBytes(),
    )

    /** Local wall-clock minute of a window start, for matching Health's list. */
    private fun HuaweiRri.Window.hhmm(): String =
        java.time.Instant.ofEpochSecond(startSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))

    @Test
    fun `the file holds nine windows of about a minute each`() {
        val w = windows()
        assertEquals(9, w.size)
        assertTrue(w.all { it.endSeconds - it.startSeconds in 50..60 })
        // Consecutive and increasing — a stride that drifted would show up here first.
        w.zipWithNext { a, b -> assertTrue(b.startSeconds > a.endSeconds) }
    }

    @Test
    fun `mean RR reproduces the heart rate Health displayed`() {
        // Health's heart-rate list for the same minutes, photographed 2026-08-22.
        val health = mapOf(
            "14:40" to 83, "15:04" to 79, "15:10" to 76, "16:27" to 89,
            "16:37" to 79, "16:40" to 78, "17:17" to 83, "17:24" to 80,
        )
        val byTime = windows().associateBy { it.hhmm() }
        var worst = 0.0
        health.forEach { (time, bpm) ->
            val w = checkNotNull(byTime[time]) { "no window at $time" }
            val got = checkNotNull(w.heartRate)
            worst = maxOf(worst, abs(got - bpm))
        }
        // The field is quantised to 20 ms, which at ~720 ms is itself worth ~2.3 bpm — so the
        // residual IS the grid. Anything past 5 bpm would mean the field is not what we think.
        assertTrue("worst heart-rate error $worst bpm", worst < 5.0)
    }

    @Test
    fun `the publish threshold separates exactly the windows Health listed`() {
        // Health showed HRV for 15:04, 15:10 and 17:24 and omitted the other six.
        val shown = setOf("15:04", "15:10", "17:24")
        windows().forEach { w ->
            assertEquals(
                "window ${w.hhmm()} (${w.validIntervals} intervals) on the wrong side of the threshold",
                w.hhmm() in shown, w.publishable,
            )
        }
    }

    @Test
    fun `every field survives, named or not`() {
        val w = windows().first()
        assertEquals(10, w.raw.size)
        assertEquals(w.validIntervals.toDouble(), w.raw.getValue(1), 0.001)
        assertEquals(w.meanRrMs, w.raw.getValue(6), 0.001)
        // Fields 3 and 6 are the 20 ms-quantised pair, and 3 is always the smaller.
        windows().forEach {
            assertEquals(0.0, it.raw.getValue(3) % 20, 0.001)
            assertEquals(0.0, it.raw.getValue(6) % 20, 0.001)
            assertTrue(it.raw.getValue(3) < it.raw.getValue(6))
        }
    }

    @Test
    fun `rubbish decodes to nothing rather than to invented windows`() {
        assertTrue(HuaweiRri.parse(ByteArray(0)).isEmpty())
        assertTrue(HuaweiRri.parse(ByteArray(600)).isEmpty())
    }
}
