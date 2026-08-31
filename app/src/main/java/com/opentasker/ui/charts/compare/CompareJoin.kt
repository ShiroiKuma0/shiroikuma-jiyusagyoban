package com.opentasker.ui.charts.compare

import com.opentasker.ui.charts.compare.CompareData.Cell
import com.opentasker.ui.charts.compare.CompareData.Grain
import com.opentasker.ui.charts.compare.CompareData.Join
import com.opentasker.ui.charts.compare.CompareData.Quantity
import com.opentasker.ui.charts.compare.CompareData.Reading
import com.opentasker.ui.charts.compare.CompareData.Refusal
import com.opentasker.ui.charts.compare.CompareData.Result
import com.opentasker.ui.charts.compare.CompareData.Series
import com.opentasker.ui.charts.compare.CompareData.ZeroConvention

/**
 * Lining up two bands' readings without ever mixing them.
 *
 * Two mechanisms, chosen by the quantity rather than by preference:
 *
 * - **Mutual-nearest pairing** for instants. Two readings pair only if each is the other's closest
 *   within a tolerance, and each is consumed once.
 * - **Absolute-time bins** for counts, anchored to the epoch so both devices land in the same bins
 *   regardless of when either started recording.
 */
object CompareJoin {

    /**
     * How far apart two readings may be and still describe the same moment.
     *
     * Thirty seconds, and the value is doing real work. The Hume band timestamps on arbitrary
     * seconds while the Huawei band lands on minute boundaries, so 12:00:58 and 12:01:00 are two
     * seconds apart and in different minutes — bucketing by minute would leave both unpaired while
     * declaring the bands to have missed each other. Pairing by distance rather than by bucket is
     * what makes the comparison describe the wrists instead of the calendar.
     */
    const val PAIR_TOLERANCE_MS = 30_000L

    /**
     * Join two series at [grain].
     *
     * Refuses rather than produces a misleading answer when the two devices disagree about absence,
     * or when a bin-summed grain is asked of a quantity that cannot be summed.
     */
    fun join(
        huawei: Series,
        hume: Series,
        grain: Grain,
        windowStartMs: Long,
        windowEndMs: Long,
        toleranceMs: Long = PAIR_TOLERANCE_MS,
    ): Result {
        require(huawei.device == CompareData.Device.HUAWEI) { "the first series must be the Huawei band" }
        require(hume.device == CompareData.Device.HUME) { "the second series must be the Hume band" }

        if (huawei.quantity != hume.quantity) {
            return Result.Refused(
                Refusal(
                    huawei.key, Refusal.Reason.INTENSIVE_CANNOT_BIN,
                    "the two series describe different kinds of quantity",
                ),
            )
        }

        // Summing instants is meaningless. This is the guard that stops a heart rate being added up
        // into a ten-minute total because the grain selector happened to be on ten minutes.
        if (grain != Grain.MINUTE && huawei.quantity == Quantity.INTENSIVE) {
            return Result.Refused(
                Refusal(
                    huawei.key, Refusal.Reason.INTENSIVE_CANNOT_BIN,
                    "a heart rate or a percentage cannot be summed into ${grain.name.lowercase()} bins",
                ),
            )
        }

        // At minute grain, absence is compared directly between the devices, so what absence MEANS
        // has to agree. It does, on 白い熊's two bands — both drop zeros — and the check remains
        // because a firmware change on either side would make the comparison wrong invisibly.
        if (grain == Grain.MINUTE && huawei.zeroConvention != hume.zeroConvention) {
            return Result.Refused(
                Refusal(
                    huawei.key, Refusal.Reason.ZERO_CONVENTION,
                    "one band records a zero and the other omits it, so a missing minute means " +
                        "different things on each — compare at ten minutes instead, where each " +
                        "device's own total is used",
                ),
            )
        }

        val (hw, hwBad) = usable(huawei.readings, windowStartMs, windowEndMs)
        val (hu, huBad) = usable(hume.readings, windowStartMs, windowEndMs)
        if (hw.isEmpty() && hu.isEmpty()) {
            return Result.Refused(
                Refusal(huawei.key, Refusal.Reason.NO_DATA, "neither band recorded anything here"),
            )
        }

        val join = if (grain == Grain.MINUTE) {
            pairNearest(hw, hu, toleranceMs, hwBad + huBad)
        } else {
            binAbsolute(hw, hu, grain, windowStartMs, windowEndMs, hwBad + huBad)
        }
        return Result.Joined(join)
    }

    /**
     * Readings that can be compared at all, and a count of those that cannot.
     *
     * Two real hazards, both from clocks rather than from sensors, and both COUNTED rather than
     * quietly dropped: a timestamp of exactly zero is what the spring-forward gap produces, and a
     * reading outside the requested window is what travelling between zones produces. Silently
     * discarding either would make a day look cleaner than it was.
     */
    private fun usable(readings: List<Reading>, fromMs: Long, toMs: Long): kotlin.Pair<List<Reading>, Int> {
        var bad = 0
        val ok = readings.filter { r ->
            val fine = r.epochMs != 0L && r.epochMs in fromMs..toMs && r.value.isFinite()
            if (!fine) bad++
            fine
        }.sortedBy { it.epochMs }
        return ok to bad
    }

    /**
     * Pair readings that are each other's nearest within the tolerance.
     *
     * A two-pointer sweep, and the mutuality is the point. A naive "for each Hume reading take the
     * closest Huawei one" lets a single Huawei reading be claimed by three Hume readings, which
     * inflates the paired count, makes the bands look more agreeable than they are, and breaks the
     * footer identity. Here each reading is consumed at most once.
     */
    private fun pairNearest(
        hw: List<Reading>,
        hu: List<Reading>,
        toleranceMs: Long,
        impossible: Int,
    ): Join {
        val cells = ArrayList<Cell>(hw.size + hu.size)
        var i = 0
        var j = 0
        var both = 0
        var hwOnly = 0
        var huOnly = 0

        while (i < hw.size && j < hu.size) {
            val a = hw[i]
            val b = hu[j]
            val gap = a.epochMs - b.epochMs
            when {
                gap < -toleranceMs -> {
                    cells += Cell(a.epochMs, a.value, null); hwOnly++; i++
                }
                gap > toleranceMs -> {
                    cells += Cell(b.epochMs, null, b.value); huOnly++; j++
                }
                else -> {
                    // Within tolerance — but only pair if neither has a strictly closer partner
                    // waiting. Without this a burst on one side steals a reading the next one on the
                    // other side is closer to.
                    val nextHw = hw.getOrNull(i + 1)
                    val nextHu = hu.getOrNull(j + 1)
                    val mine = kotlin.math.abs(gap)
                    val hwBetter = nextHw != null && kotlin.math.abs(nextHw.epochMs - b.epochMs) < mine
                    val huBetter = nextHu != null && kotlin.math.abs(a.epochMs - nextHu.epochMs) < mine
                    when {
                        hwBetter -> { cells += Cell(a.epochMs, a.value, null); hwOnly++; i++ }
                        huBetter -> { cells += Cell(b.epochMs, null, b.value); huOnly++; j++ }
                        else -> {
                            // The pair is stamped at the Huawei reading's instant rather than at a
                            // midpoint: a midpoint is a time neither band recorded, and this axis
                            // must only show moments that happened.
                            cells += Cell(a.epochMs, a.value, b.value); both++; i++; j++
                        }
                    }
                }
            }
        }
        while (i < hw.size) { cells += Cell(hw[i].epochMs, hw[i].value, null); hwOnly++; i++ }
        while (j < hu.size) { cells += Cell(hu[j].epochMs, null, hu[j].value); huOnly++; j++ }

        cells.sortBy { it.epochMs }
        return Join(
            grain = Grain.MINUTE,
            cells = cells,
            huaweiSamples = hw.size,
            humeSamples = hu.size,
            both = both,
            huaweiOnly = hwOnly,
            humeOnly = huOnly,
            impossible = impossible,
        )
    }

    /**
     * Sum each device into absolute-time bins.
     *
     * Anchored with a floor division on the epoch, NOT on the window start. Anchoring to the window
     * would move every bin boundary when the reader changed the span, so the same walk would fall
     * into different bins depending on when they happened to be looking — and two devices whose data
     * begins at different instants would land in bins offset from each other.
     *
     * Legitimate only because a count adds; [join] refuses this path for anything intensive.
     */
    private fun binAbsolute(
        hw: List<Reading>,
        hu: List<Reading>,
        grain: Grain,
        fromMs: Long,
        toMs: Long,
        impossible: Int,
    ): Join {
        val width = grain.seconds * 1000L
        fun bin(ms: Long) = Math.floorDiv(ms, width) * width

        val hwBins = hw.groupBy { bin(it.epochMs) }.mapValues { (_, v) -> v.sumOf { it.value } }
        val huBins = hu.groupBy { bin(it.epochMs) }.mapValues { (_, v) -> v.sumOf { it.value } }

        var both = 0
        var hwOnly = 0
        var huOnly = 0
        val cells = (hwBins.keys + huBins.keys).sorted().map { at ->
            val a = hwBins[at]
            val b = huBins[at]
            when {
                a != null && b != null -> both++
                a != null -> hwOnly++
                else -> huOnly++
            }
            Cell(at, a, b)
        }

        // A bin the window cuts through cannot hold its whole total, so it is drawn but excluded
        // from every statistic. Counting it would make a partial ten minutes look like a quiet one.
        val notCounted = cells.count { it.epochMs < fromMs || it.epochMs + width > toMs }

        return Join(
            grain = grain,
            cells = cells,
            huaweiSamples = hw.size,
            humeSamples = hu.size,
            both = both,
            huaweiOnly = hwOnly,
            humeOnly = huOnly,
            impossible = impossible,
            notCounted = notCounted,
        )
    }
}
