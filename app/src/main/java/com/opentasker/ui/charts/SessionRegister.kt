package com.opentasker.ui.charts

import com.opentasker.core.band.TrainingSessions

/**
 * 運動と回復 — every marked session, paired with the night that followed it.
 *
 * ## The pairing, and why that direction
 *
 * A session on day N is matched to the night that STARTS after it, not the one before. That is the
 * direction the measured effect runs: training raises the *following* night's heart rate, by up to
 * 15 % when it ends close to bedtime and by nothing at all once four hours separate them (Leota et
 * al. 2025, n = 14 689, >4 M person-nights). The current 回復 card can only ever answer "how was last
 * night"; this answers "what did that session cost", which is the question worth asking of training.
 *
 * ## What is computed, and what deliberately is not
 *
 * Per session: duration, MET-minutes, the peak heart rate inside the window, and the following
 * night's three counted markers with their deltas. All of that is measurement.
 *
 * The only aggregate is [Contrast]: the median nocturnal heart rate on nights **after a session**
 * against nights **after none**, with its two sample sizes printed. It is a within-person contrast,
 * which is the strongest form available to a single-person record, and it appears only once both
 * sides have [MIN_CONTRAST_NIGHTS] nights behind them.
 *
 * There is **no correlation coefficient, no trend line and no verdict**. With a handful of sessions
 * those would be noise dressed as insight, and the failure mode of every training app is exactly
 * that. When there are dozens of sessions the contrast will still be the honest summary; it will
 * simply have tighter numbers behind it.
 *
 * Pure Kotlin: nights, sessions and the spot readings all arrive as arguments.
 */
object SessionRegister {

    /** A night is "after" a session if it starts within this long of the session ending. */
    const val PAIRING_WINDOW_MS = 20 * 3_600_000L

    /** Below this on either side, the contrast is not a comparison. */
    const val MIN_CONTRAST_NIGHTS = 4

    /** One session and the night that followed it. */
    data class Entry(
        val session: TrainingSessions.Session,
        val metMinutes: Double,
        val peakHr: Double?,
        val night: NightReading?,
    )

    /** One night, banded against the nights before it. */
    data class NightReading(
        val startMs: Long,
        val nocturnalHr: MarkerReading,
        val sleep: MarkerReading,
        val felt: MarkerReading,
        val adverseCount: Int,
    )

    /** One square of the grid. */
    data class DayCell(
        val epochDay: Long,
        /** MET-minutes of sessions starting that day, or null when there were none. */
        val sessionLoad: Double?,
        /** Markers off on the night that STARTED that day, or null when there was no night. */
        val adverseCount: Int?,
    )

    /** Nights after a session against nights after none. */
    data class Contrast(
        val afterSession: Double,
        val afterRest: Double,
        val nAfterSession: Int,
        val nAfterRest: Int,
    ) {
        val delta: Double get() = afterSession - afterRest
    }

    data class Register(
        val entries: List<Entry>,
        val days: List<DayCell>,
        val contrast: Contrast?,
    )

    /**
     * Band every night against the ones before it, so the register can show what each night looked
     * like AT THE TIME rather than against today's baseline.
     *
     * That distinction matters: a night judged against a baseline that already contains it, or that
     * contains three later months, is not the night 白い熊 would have been shown.
     */
    fun readNights(
        history: List<RecoverySource.NightMetrics>,
        feltFor: (RecoverySource.NightMetrics) -> Double?,
    ): List<NightReading> = history.indices.map { i ->
        val prior = history.subList(maxOf(0, i - Recovery.BASELINE_NIGHTS), i)
        val confidence = Recovery.confidenceFor(prior.size)
        val night = history[i]
        val hr = Recovery.bandNocturnalHr(night.nocturnalHr, prior.mapNotNull { it.nocturnalHr }, confidence)
        val sleep = Recovery.band(
            RecoveryMarker.SLEEP, night.sleepMinutes, prior.mapNotNull { it.sleepMinutes },
            Recovery.SLEEP_MEANINGFUL_MIN, confidence, counted = true,
        )
        val felt = Recovery.band(
            RecoveryMarker.FELT, feltFor(night), prior.mapNotNull(feltFor),
            Recovery.FELT_MEANINGFUL_STEPS, confidence, counted = true,
        )
        NightReading(
            startMs = night.startMs,
            nocturnalHr = hr,
            sleep = sleep,
            felt = felt,
            adverseCount = listOf(hr, sleep, felt).count { it.adverse },
        )
    }

    fun build(
        sessions: List<TrainingSessions.Session>,
        nights: List<NightReading>,
        spotPoints: List<ChartPoint>,
        restingHr: Double?,
        zoneOffsetMs: Long,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): Register {
        fun dayOf(ms: Long) = (ms + zoneOffsetMs) / 86_400_000L

        val entries = sessions.sortedByDescending { it.startMs }.map { s ->
            val inside = spotPoints.filter { it.tMs in s.startMs until s.endMs }
            Entry(
                session = s,
                metMinutes = restingHr?.let { RecoverySource.sessionLoad(s, spotPoints, it) } ?: 0.0,
                peakHr = inside.maxOfOrNull { it.value },
                // The first night to START after the session ends, within the pairing window.
                night = nights.firstOrNull { it.startMs >= s.endMs && it.startMs - s.endMs <= PAIRING_WINDOW_MS },
            )
        }

        val loadByDay = sessions.groupBy { dayOf(it.startMs) }
            .mapValues { (_, v) ->
                v.sumOf { s -> restingHr?.let { RecoverySource.sessionLoad(s, spotPoints, it) } ?: 0.0 }
            }
        val adverseByDay = nights.associate { dayOf(it.startMs) to it.adverseCount }
        val days = (fromEpochDay..toEpochDay).map { d ->
            DayCell(d, loadByDay[d], adverseByDay[d])
        }

        // Which nights followed a session, by the same pairing rule the entries use.
        val nightsAfterSession = entries.mapNotNull { it.night?.startMs }.toSet()
        val after = nights.filter { it.startMs in nightsAfterSession }.mapNotNull { it.nocturnalHr.value }
        val rest = nights.filter { it.startMs !in nightsAfterSession }.mapNotNull { it.nocturnalHr.value }
        val contrast = if (after.size >= MIN_CONTRAST_NIGHTS && rest.size >= MIN_CONTRAST_NIGHTS) {
            Contrast(
                afterSession = Recovery.median(after) ?: 0.0,
                afterRest = Recovery.median(rest) ?: 0.0,
                nAfterSession = after.size,
                nAfterRest = rest.size,
            )
        } else {
            null
        }

        return Register(entries, days, contrast)
    }
}
