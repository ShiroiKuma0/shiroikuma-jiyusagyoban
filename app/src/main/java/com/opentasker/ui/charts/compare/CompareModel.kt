package com.opentasker.ui.charts.compare

import com.opentasker.core.band.BandMetric
import com.opentasker.core.storage.AppDatabase
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.Loc
import com.opentasker.ui.charts.compare.CompareData.Device
import com.opentasker.ui.charts.compare.CompareData.Grain
import com.opentasker.ui.charts.compare.CompareData.Quantity
import com.opentasker.ui.charts.compare.CompareData.Reading
import com.opentasker.ui.charts.compare.CompareData.Result
import com.opentasker.ui.charts.compare.CompareData.Series
import com.opentasker.ui.charts.compare.CompareData.ZeroConvention
import com.opentasker.ui.charts.huawei.HuaweiKeys
import androidx.compose.ui.graphics.Color

/**
 * Which metrics can be put side by side, and how.
 *
 * ## The card order is load-bearing, not aesthetic
 *
 * Cards run in the dashboard's own order — steps, then heart rate, then blood oxygen — and NOT
 * grouped by how comparable they are. Measured: steps magenta against SpO₂ aqua is **ΔE 1.6 under
 * deuteranopia**, worse than the violet/blue pair `ChartPalette` already records as a failure at 1.9.
 * Putting heart rate's blue between them restores the floor to CVD ΔE 15.9. Reordering these cards
 * by tier would put the two indistinguishable colours next to each other, so the order is pinned by
 * a test.
 */
object CompareModel {

    /**
     * One comparable metric.
     *
     * [provisional] marks a metric whose Huawei-side gates are still placeholders — it earns the
     * caution chip rather than being presented as directly comparable.
     */
    data class Row(
        val title: Loc,
        val huaweiKey: String,
        val humeKey: String,
        val quantity: Quantity,
        val zeroConvention: ZeroConvention,
        val grain: Grain,
        val unit: String,
        val color: Color,
        val threshold: Double,
        val provisional: Boolean = true,
        val decimals: Int = 0,
    )

    /**
     * The comparable set, in the dashboard's order.
     *
     * Steps is compared at MINUTE grain, which the original design forbade: it assumed the two bands
     * disagreed about what a missing minute meant. Measured on 白い熊's own devices on 2026-08-23,
     * they agree — both omit a minute with no steps — so the comparison is legitimate. The join still
     * checks, because the fact worth encoding is that it was verified rather than assumed.
     */
    val ROWS = listOf(
        Row(
            title = Loc("Steps", "歩数"),
            huaweiKey = HuaweiKeys.STEPS,
            humeKey = BandMetric.STEPS_MINUTE,
            quantity = Quantity.EXTENSIVE,
            zeroConvention = ZeroConvention.ABSENT_IS_ZERO,
            grain = Grain.MINUTE,
            unit = "歩",
            color = ChartPalette.STEPS,
            threshold = 5.0,
        ),
        Row(
            title = Loc("Heart Rate", "心拍"),
            huaweiKey = HuaweiKeys.HEART_RATE,
            humeKey = BandMetric.HEART_RATE,
            quantity = Quantity.INTENSIVE,
            zeroConvention = ZeroConvention.ABSENT_IS_UNMEASURED,
            grain = Grain.MINUTE,
            unit = "bpm",
            color = ChartPalette.HEART_RATE,
            // Five beats: below the difference two devices on one wrist routinely show, so a pair
            // inside it is agreement rather than a coincidence.
            threshold = 5.0,
        ),
        Row(
            title = Loc("Blood Oxygen", "血中酸素"),
            huaweiKey = HuaweiKeys.SPO2,
            humeKey = BandMetric.SPO2,
            quantity = Quantity.INTENSIVE,
            zeroConvention = ZeroConvention.ABSENT_IS_UNMEASURED,
            grain = Grain.MINUTE,
            unit = "%",
            color = ChartPalette.SPO2,
            threshold = 2.0,
        ),
    )

    /** One finished comparison, ready to draw. */
    data class Card(
        val row: Row,
        val result: Result,
        val footer: List<String>,
        val tier: CompareTier,
    )

    /**
     * Read both tables and join every row.
     *
     * The two DAOs are read separately and never mixed — the only place a value from one meets a
     * value from the other is inside a difference.
     */
    suspend fun build(db: AppDatabase, fromMs: Long, toMs: Long): List<Card> {
        val fromSec = fromMs / 1000
        val toSec = toMs / 1000
        return ROWS.map { row ->
            val huawei = db.huaweiSampleDao()
                .range(HuaweiKeys.storageKey(row.huaweiKey), fromSec, toSec)
                .map { Reading(it.epochSeconds * 1000L, it.value) }
            val hume = db.bandSampleDao()
                .rangeAsc(row.humeKey, fromMs, toMs)
                .map { Reading(it.epochMs, it.value) }

            val result = CompareJoin.join(
                Series(Device.HUAWEI, row.huaweiKey, row.quantity, row.zeroConvention, huawei),
                Series(Device.HUME, row.humeKey, row.quantity, row.zeroConvention, hume),
                row.grain, fromMs, toMs,
            )
            when (result) {
                is Result.Refused -> Card(row, result, emptyList(), CompareTier.REFUSED)
                is Result.Joined -> {
                    val join = result.join
                    val delta = CompareStats.delta(join, row.threshold)
                    val scale = join.cells
                        .flatMap { listOfNotNull(it.huawei, it.hume) }
                        .let { v ->
                            if (v.isEmpty()) "—"
                            else "${fmt(v.min(), row.decimals)}–${fmt(v.max(), row.decimals)} ${row.unit}"
                        }
                    Card(
                        row = row,
                        result = result,
                        footer = CompareStats.footer(
                            join, delta, row.unit, scale,
                            CompareStats.clockOffsetSeconds(join),
                        ),
                        tier = CompareTier.of(join, row.provisional),
                    )
                }
            }
        }
    }

    private fun fmt(v: Double, decimals: Int) = "%.${decimals}f".format(v)
}
