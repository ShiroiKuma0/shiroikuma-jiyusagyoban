package com.opentasker.ui.charts.compare

import com.opentasker.ui.charts.compare.CompareData.Device
import com.opentasker.ui.charts.compare.CompareData.Grain
import com.opentasker.ui.charts.compare.CompareData.Quantity
import com.opentasker.ui.charts.compare.CompareData.Reading
import com.opentasker.ui.charts.compare.CompareData.Refusal
import com.opentasker.ui.charts.compare.CompareData.Result
import com.opentasker.ui.charts.compare.CompareData.Series
import com.opentasker.ui.charts.compare.CompareData.ZeroConvention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join, which is where a two-band comparison goes quietly wrong if anywhere.
 *
 * Every test here defends one specific way of producing a chart that looks right and is not.
 */
class CompareJoinTest {

    private val t0 = 1_787_400_000_000L          // 2026-08-22, in milliseconds
    private val windowEnd = t0 + 86_400_000L

    private fun hw(
        readings: List<Reading>,
        quantity: Quantity = Quantity.INTENSIVE,
        zero: ZeroConvention = ZeroConvention.ABSENT_IS_UNMEASURED,
    ) = Series(Device.HUAWEI, "hw:hr", quantity, zero, readings)

    private fun hume(
        readings: List<Reading>,
        quantity: Quantity = Quantity.INTENSIVE,
        zero: ZeroConvention = ZeroConvention.ABSENT_IS_UNMEASURED,
    ) = Series(Device.HUME, "hr", quantity, zero, readings)

    private fun at(seconds: Long, v: Double) = Reading(t0 + seconds * 1000L, v)

    private fun joined(r: Result) = (r as Result.Joined).join

    @Test
    fun `readings seconds apart across a minute boundary still pair`() {
        // The case that motivated pairing by distance rather than by bucket: the Hume band lands on
        // arbitrary seconds, the Huawei band on minute boundaries. 12:00:58 and 12:01:00 describe
        // the same moment and would fall in different minute buckets.
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(60, 70.0))),
                hume(listOf(at(58, 72.0))),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        assertEquals(1, join.both)
        assertEquals(0, join.huaweiOnly)
        assertEquals(0, join.humeOnly)
        assertEquals(-2.0, join.cells.single().delta!!, 1e-9)
    }

    @Test
    fun `beyond the tolerance they are two separate one-band cells`() {
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(0, 70.0))),
                hume(listOf(at(45, 72.0))),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        assertEquals(0, join.both)
        assertEquals(1, join.huaweiOnly)
        assertEquals(1, join.humeOnly)
        // A one-band cell is a result, not an absence: it must be present and it must have no delta.
        assertEquals(2, join.cells.size)
        assertTrue(join.cells.all { it.delta == null })
    }

    @Test
    fun `one reading is never claimed by two`() {
        // A naive nearest-match lets a single Huawei reading pair with three Hume readings, which
        // inflates agreement and breaks the footer identity. Each may be consumed once.
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(60, 70.0))),
                hume(listOf(at(45, 71.0), at(60, 72.0), at(75, 73.0))),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        assertEquals(1, join.both)
        assertEquals(2, join.humeOnly)
        assertEquals(3, join.humeSamples)
    }

    @Test
    fun `the closer partner wins when two are within tolerance`() {
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(0, 70.0), at(20, 80.0))),
                hume(listOf(at(21, 81.0))),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        // 20 s is one second from the Hume reading; 0 s is twenty-one. The pair must be the closer.
        val paired = join.cells.single { it.hasBoth }
        assertEquals(80.0, paired.huawei!!, 1e-9)
        assertEquals(1, join.huaweiOnly)
    }

    @Test
    fun `the footer identity holds — nothing is dropped or double-counted`() {
        val join = joined(
            CompareJoin.join(
                hw((0..40).map { at(it * 60L, 70.0 + it) }),
                hume((0..25).map { at(it * 97L, 72.0 + it) }),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        // The reader's proof that the two bands were never pooled.
        assertEquals(join.humeSamples, join.both + join.humeOnly)
        assertEquals(join.huaweiSamples, join.both + join.huaweiOnly)
        assertEquals(join.cells.size, join.both + join.humeOnly + join.huaweiOnly)
    }

    @Test
    fun `cells come out in time order`() {
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(300, 70.0), at(0, 71.0))),
                hume(listOf(at(600, 72.0))),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        assertEquals(join.cells.map { it.epochMs }.sorted(), join.cells.map { it.epochMs })
    }

    @Test
    fun `a heart rate is refused at any binned grain`() {
        for (grain in listOf(Grain.TEN_MINUTES, Grain.DAY)) {
            val r = CompareJoin.join(
                hw(listOf(at(0, 70.0))), hume(listOf(at(0, 71.0))), grain, t0, windowEnd,
            )
            val refusal = (r as Result.Refused).refusal
            assertEquals(Refusal.Reason.INTENSIVE_CANNOT_BIN, refusal.reason)
        }
    }

    @Test
    fun `steps are summed into absolute bins, anchored to the epoch`() {
        val steps = Quantity.EXTENSIVE
        val zero = ZeroConvention.ABSENT_IS_ZERO
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(0, 10.0), at(60, 20.0), at(700, 5.0)), steps, zero),
                hume(listOf(at(30, 12.0), at(705, 6.0)), steps, zero),
                Grain.TEN_MINUTES, t0, windowEnd,
            ),
        )
        // First bin: 10+20 against 12. Second: 5 against 6.
        val first = join.cells.first()
        assertEquals(30.0, first.huawei!!, 1e-9)
        assertEquals(12.0, first.hume!!, 1e-9)
        assertEquals(18.0, first.delta!!, 1e-9)
        // Bins are anchored by flooring the epoch, so a boundary is a multiple of the width.
        assertTrue(join.cells.all { it.epochMs % (Grain.TEN_MINUTES.seconds * 1000L) == 0L })
    }

    @Test
    fun `a disagreement about what absence means is refused at minute grain`() {
        val r = CompareJoin.join(
            hw(listOf(at(0, 0.0)), Quantity.EXTENSIVE, ZeroConvention.ABSENT_IS_UNMEASURED),
            hume(listOf(at(0, 0.0)), Quantity.EXTENSIVE, ZeroConvention.ABSENT_IS_ZERO),
            Grain.MINUTE, t0, windowEnd,
        )
        assertEquals(Refusal.Reason.ZERO_CONVENTION, (r as Result.Refused).refusal.reason)
    }

    @Test
    fun `the same disagreement is allowed at ten minutes, where each band totals its own`() {
        val r = CompareJoin.join(
            hw(listOf(at(0, 3.0)), Quantity.EXTENSIVE, ZeroConvention.ABSENT_IS_UNMEASURED),
            hume(listOf(at(0, 4.0)), Quantity.EXTENSIVE, ZeroConvention.ABSENT_IS_ZERO),
            Grain.TEN_MINUTES, t0, windowEnd,
        )
        assertTrue("binning is each device's own total, so the convention stops mattering", r is Result.Joined)
    }

    @Test
    fun `both of 白い熊's bands drop zeros, so per-minute steps do compare`() {
        // Measured 2026-08-23. The earlier design assumed these conventions were opposite and
        // refused this comparison outright; they are not, and the refusal above is kept only against
        // a future firmware change.
        val r = CompareJoin.join(
            hw(listOf(at(0, 12.0)), Quantity.EXTENSIVE, ZeroConvention.ABSENT_IS_ZERO),
            hume(listOf(at(5, 11.0)), Quantity.EXTENSIVE, ZeroConvention.ABSENT_IS_ZERO),
            Grain.MINUTE, t0, windowEnd,
        )
        assertEquals(1, joined(r).both)
    }

    @Test
    fun `clock hazards are counted, never silently dropped`() {
        val join = joined(
            CompareJoin.join(
                hw(listOf(Reading(0L, 70.0), at(0, 71.0), Reading(windowEnd + 60_000L, 72.0))),
                hume(listOf(at(0, 73.0))),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        // A zero timestamp is the spring-forward gap; a reading past the window is zone travel.
        assertEquals(2, join.impossible)
        assertEquals(1, join.huaweiSamples)
    }

    @Test
    fun `an empty window is refused rather than drawn as agreement`() {
        val r = CompareJoin.join(hw(emptyList()), hume(emptyList()), Grain.MINUTE, t0, windowEnd)
        assertEquals(Refusal.Reason.NO_DATA, (r as Result.Refused).refusal.reason)
    }

    @Test
    fun `one band alone still joins, and says so in the counts`() {
        val join = joined(
            CompareJoin.join(
                hw(listOf(at(0, 70.0), at(60, 71.0))),
                hume(emptyList()),
                Grain.MINUTE, t0, windowEnd,
            ),
        )
        assertEquals(0, join.both)
        assertEquals(2, join.huaweiOnly)
        assertEquals(2, join.cells.size)
    }

    @Test
    fun `a partial bin is drawn but not counted`() {
        val steps = Quantity.EXTENSIVE
        val zero = ZeroConvention.ABSENT_IS_ZERO
        // A window that starts mid-bin: the first bin cannot hold its whole total.
        val from = t0 + 300_000L
        val join = joined(
            CompareJoin.join(
                hw(listOf(Reading(from + 60_000L, 10.0)), steps, zero),
                hume(listOf(Reading(from + 61_000L, 11.0)), steps, zero),
                Grain.TEN_MINUTES, from, from + 3_600_000L,
            ),
        )
        assertTrue("the cut bin must be flagged", join.notCounted >= 1)
    }
}
