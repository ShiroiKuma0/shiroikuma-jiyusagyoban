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

    /**
     * The two directions between a grid day and the key its rating is filed under.
     *
     * The grid counts in epoch days because that is what the load and the nights are bucketed by; a
     * rating is keyed `yyyyMMdd` because that is the shape the band's own daily records use. Tapping a
     * tile has to cross between them, so the conversion lives here, next to the grid it serves, rather
     * than being written out twice in the screen.
     *
     * Both are the LOCAL date: the caller's `zoneOffsetMs` has already been added by the time a day
     * index reaches these, exactly as it has for every other cell on the row.
     */
    fun dateKeyOf(epochDay: Long): Long = java.time.LocalDate.ofEpochDay(epochDay)
        .let { it.year * 10_000L + it.monthValue * 100L + it.dayOfMonth }

    /** `yyyyMMdd` → epoch day. Null rather than a guess when the key is not a real date. */
    fun epochDayOf(dateKey: Long): Long? = runCatching {
        java.time.LocalDate.of(
            (dateKey / 10_000L).toInt(),
            ((dateKey / 100L) % 100L).toInt(),
            (dateKey % 100L).toInt(),
        ).toEpochDay()
    }.getOrNull()

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
        /** When it ended — the morning that names it. See [RecoverySource.NightMetrics.endMs]. */
        val endMs: Long,
        val nocturnalHr: MarkerReading,
        val sleep: MarkerReading,
        val felt: MarkerReading,
        /** Reported but never counted, exactly as on the card — see [Recovery] for why. */
        val temperature: MarkerReading,
        val adverseCount: Int,
        /**
         * The readings the night table prints beside the two banded markers, and the 1–5 step each
         * is coloured with. Five of them since 2026-09-03; eight since 2026-09-26.
         *
         * ## Two different references, because there is no single honest one
         *
         * 白い熊 asked for every column to carry a colour (2026-09-03). Each is given the best
         * reference that actually exists for that quantity, and the ⓘ panel names which is which:
         *
         *  * **The low and the blood oxygen are PUBLISHED** — Jensen's resting-rate decades and the
         *    clinical pulse-oximetry ranges. See [RecoveryReference], including the caveats: a
         *    sleeping floor sits below a daytime resting rate, and a wrist oximeter's error is about
         *    twice the swing it measures.
         *  * **Deep, deep+REM, RMSSD, the going-to-bed rate, the swing and breathing are
         *    WITHIN-PERSON**, banded against the nights before them,
         *    because no population ladder fits them. This band's "deep" is not polysomnography's N3
         *    — 白い熊's nights run 30–40 % of sleep where the literature's N3 is 13–23 %, so an
         *    absolute ladder would score every night he has ever recorded as extreme. RMSSD norms
         *    are so age-dependent that a population mean would paint the whole column one colour.
         *
         * **None of them is COUNTED.** [adverseCount] is still the three markers it always was;
         * a colour here says where a value sits, never that the night was adverse. The standing
         * ruling that deep/REM and blood oxygen are excluded from the counting rule is untouched —
         * showing a value and scoring a night are different acts.
         */
        val deepMinutes: Double? = null,
        val deepRemShare: Double? = null,
        val lowestHr: Double? = null,
        val spo2: Double? = null,
        val hrvMs: Double? = null,
        /**
         * Going to bed, through the night, and breathing — the three the table used to leave out.
         *
         * 白い熊, 2026-09-26: *"this table doesn't have everything, it lacks the going-to-bed-hr,
         * hr-through-the-night: it must have all the metrics, so it's more descriptive."* They were
         * on the deviation strip and on the 回復 card and nowhere in the record, so a night could be
         * read two ways depending on which screen was open — and the register is the one that is
         * read AFTERWARDS, when the question is what a run of nights looked like.
         *
         * All three are banded WITHIN-PERSON like deep, deep+REM and RMSSD, and for the same
         * reason: there is no published ladder for a wrist band's going-to-bed rate, for the spread
         * of a binned night curve, or for an estimated respiratory rate. None of them is counted.
         */
        val bedtimeHr: Double? = null,
        val hrSwing: Double? = null,
        val respirationBpm: Double? = null,
        /**
         * The night's heart rate in twelfths — the SHAPE, drawn in its own cell.
         *
         * A number cannot be "heart rate through the night": the swing says how far the curve
         * moved and nothing whatever about when, which is most of what a night looks like. So the
         * table carries the curve itself, one tiny line per row, on a scale shared by every row so
         * the column can be read downwards. (白い熊, 2026-09-26, having asked for this metric in
         * the table and found a number where the shape belonged.)
         */
        val hrCurve: List<Double> = emptyList(),
        /**
         * The curve's own 1–5 step, by the DESCENT — the same grading the report's bar uses.
         *
         * NOT the swing's step, which is what the column was first coloured with and was wrong in a
         * way 白い熊 spotted immediately (2026-09-26: *"the night-curve the last two days as a
         * minimum must be red"*). The swing says how far the curve moved against usual; a night
         * whose heart rate went UP 14 bpm and a night that fell 14 both score "ordinary" on it, and
         * 09-26 — which never fell at all — came out blue in the table while the report's bar for
         * the same night was dark red. Two numbers for one quantity, which is the failure this
         * project trips over most.
         *
         * `MarkerHistory.descentStep` now, against the median descent of the nights BEFORE this one
         * — the register's own "at the time" convention, so a night is scored against what was
         * known when it happened rather than against a baseline that contains it.
         */
        val hrCurveStep: Int? = null,
        /** The within-person steps for the six that have no published ladder. */
        val deepStep: Int? = null,
        val deepRemStep: Int? = null,
        val hrvStep: Int? = null,
        val bedtimeHrStep: Int? = null,
        val hrSwingStep: Int? = null,
        val respirationStep: Int? = null,
    )

    /** One square of the grid. */
    data class DayCell(
        val epochDay: Long,
        /** MET-minutes of sessions starting that day, or null when there were none. */
        val sessionLoad: Double?,
        /** Markers off on the night that STARTED that day, or null when there was no night. */
        val adverseCount: Int?,
        /** The 1–5 rating for that night, so the grid shows the score itself and not only a count. */
        val felt: Int?,
        /**
         * Whether that morning carries a written note — see `DayNotes`.
         *
         * A flag rather than the text: a tile is a seventh of a phone's width and could not show a
         * sentence if it had one. What the grid has to answer is "which days did I write something
         * about", so the tile is marked and the note itself is read in the editor behind it.
         */
        val hasNote: Boolean = false,
    )

    /**
     * One line of the night table: a recorded night, a rating with no night, or both.
     *
     * The two sets are NOT the same. A night comes from the band's sleep sessions; a rating comes from
     * 白い熊 tapping 1–5. Listing only the first hides any rating whose date the band never recorded —
     * it is stored, counted in the baseline, and invisible. This row type exists so that cannot
     * happen: the table is driven by the union of both. (白い熊, 2026-08-11: "where's the fourth data
     * point?")
     */
    data class NightRow(
        /** `yyyyMMdd` — the night's start date, which is also the key the rating is filed under. */
        val dateKey: Long,
        val night: NightReading?,
        /** Straight from the store, so a rating with no night still shows its score. */
        val felt: Int?,
        /**
         * That morning's written note, whole — the table has a full row's width to print it in.
         *
         * A note with neither a night nor a rating still gets a line, by the same argument the
         * ratings won one: something 白い熊 authored that the table does not list is stored and
         * invisible, which is the one state this row type exists to make impossible.
         */
        val note: String? = null,
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
        /**
         * Every night on record, newest first.
         *
         * Carried out whole rather than folded into [days] because the grid can only ever show a
         * count, and a count is not a reading: three grey dots say the same thing for a night rated 3
         * and a night never rated at all. The screen lists these so every stored value has somewhere
         * it is actually printed — which is the whole point of keeping them. (白い熊, 2026-08-11:
         * "it is indistinguishable what the individual day scores are".)
         */
        val nights: List<NightReading>,
        /** The night table's lines, newest first: every night AND every rating, unioned. */
        val rows: List<NightRow>,
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
        val temperature = Recovery.band(
            RecoveryMarker.TEMPERATURE, night.skinTemp, prior.mapNotNull { it.skinTemp },
            Recovery.TEMP_MEANINGFUL_C, confidence, counted = false, oneSidedHigh = true,
        )
        NightReading(
            startMs = night.startMs,
            endMs = night.endMs,
            nocturnalHr = hr,
            sleep = sleep,
            felt = felt,
            temperature = temperature,
            // Three, not four: temperature is reported and never counted, so the register's count and
            // the card's headline can never disagree about the same night.
            adverseCount = listOf(hr, sleep, felt).count { it.adverse },
            deepMinutes = night.deepMinutes,
            deepRemShare = night.deepRemShare,
            lowestHr = night.lowestHr,
            spo2 = night.spo2,
            hrvMs = night.hrvMs,
            // Banded against this night's own past, exactly as the counted markers are — the same
            // function, the same confidence ladder — but taken only as far as `scaleStep`, which is
            // a colour. Nothing here reaches `adverseCount`.
            deepStep = Recovery.band(
                RecoveryMarker.DEEP, night.deepMinutes, prior.mapNotNull { it.deepMinutes },
                Recovery.DEEP_MEANINGFUL_MIN, confidence, counted = false,
            ).scaleStep,
            deepRemStep = Recovery.band(
                RecoveryMarker.DEEP_REM, night.deepRemShare, prior.mapNotNull { it.deepRemShare },
                Recovery.DEEP_REM_MEANINGFUL_SHARE, confidence, counted = false,
            ).scaleStep,
            hrvStep = Recovery.band(
                RecoveryMarker.HRV, night.hrvMs, prior.mapNotNull { it.hrvMs },
                Recovery.HRV_MEANINGFUL_MS, confidence, counted = false,
            ).scaleStep,
            bedtimeHr = night.bedtimeHr,
            hrSwing = night.hrSwing,
            respirationBpm = night.respirationBpm,
            hrCurve = night.hrCurve,
            hrCurveStep = curveStep(night, prior),
            // The going-to-bed rate and the swing are both heart rates in bpm, so they take the
            // heart rate's own published smallest-worthwhile-change rather than a figure invented
            // for them — the same call RecoveryBuild makes for the strip's swing row, so a colour
            // means the same thing on both screens.
            bedtimeHrStep = Recovery.band(
                RecoveryMarker.BEDTIME_HR, night.bedtimeHr, prior.mapNotNull { it.bedtimeHr },
                Recovery.HR_MEANINGFUL_BPM, confidence, counted = false,
            ).scaleStep,
            hrSwingStep = Recovery.band(
                RecoveryMarker.HR_SWING, night.hrSwing, prior.mapNotNull { it.hrSwing },
                Recovery.HR_MEANINGFUL_BPM, confidence, counted = false,
            ).scaleStep,
            respirationStep = Recovery.band(
                RecoveryMarker.RESPIRATION, night.respirationBpm,
                prior.mapNotNull { it.respirationBpm },
                Recovery.RESPIRATION_MEANINGFUL_BPM, confidence, counted = false,
            ).scaleStep,
        )
    }

    /** Bed-time level to the night's floor, from the binned curve — the report's own definition. */
    private fun descentOf(n: RecoverySource.NightMetrics): MarkerHistory.Descent? =
        n.hrCurve.takeIf { it.isNotEmpty() }?.let { MarkerHistory.Descent(it.first(), it.min()) }

    /**
     * How far this night's fall came short of the usual one, as a 1–5 step.
     *
     * The medians are taken over the froms and the tos SEPARATELY, exactly as
     * [MarkerHistory.descent] does for the report — the two screens must not compute "usually" two
     * ways. Null when there is no curve, or no earlier night to compare it against: an uncoloured
     * cell is the honest answer there, never a reassuring middle step.
     */
    private fun curveStep(
        night: RecoverySource.NightMetrics,
        prior: List<RecoverySource.NightMetrics>,
    ): Int? {
        val last = descentOf(night) ?: return null
        val earlier = prior.mapNotNull(::descentOf)
        val usual = MarkerHistory.descent(earlier.map { it.from }, earlier.map { it.to }) ?: return null
        return MarkerHistory.descentStep(MarkerHistory.DescentComparison(last, usual))
    }

    fun build(
        sessions: List<TrainingSessions.Session>,
        nights: List<NightReading>,
        spotPoints: List<ChartPoint>,
        restingHr: Double?,
        zoneOffsetMs: Long,
        fromEpochDay: Long,
        toEpochDay: Long,
        /** Every stored rating, so one filed against a date with no night is still listed. */
        ratings: Map<Long, Int> = emptyMap(),
        /**
         * Every written note, `yyyyMMdd` → text, keyed exactly as the ratings are.
         *
         * Unioned into the rows for the same reason the ratings are: a morning 白い熊 wrote about but
         * did not score, on a date the band recorded nothing for, would otherwise be stored and
         * unreachable.
         */
        notes: Map<Long, String> = emptyMap(),
        /** An instant → its `yyyyMMdd` local date. Applied to a night's END, which names it. */
        dateOfNight: (Long) -> Long = { 0L },
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
        // A night sits on the MORNING it ended, never the evening it began — that is the day 白い熊
        // woke on and rated, and it is the only placement that survives a bedtime either side of
        // midnight. Keyed on the start, a night begun at 23:09 sat one tile left of the morning it
        // was rated on while one begun at 00:21 sat on it, so the same habit drew two different
        // pictures. (白い熊, 2026-08-16 — the band's own archive: 13 nights begun before midnight,
        // one after.) The load bar below is deliberately NOT moved: it is the training done on that
        // calendar day, and its effect shows up in the NEXT morning's score.
        val adverseByDay = nights.associate { dayOf(it.endMs) to it.adverseCount }
        val feltByDay = nights.mapNotNull { n ->
            n.felt.value?.let { dayOf(n.endMs) to it.toInt() }
        }.toMap()
        // A rating filed against a date the band recorded no night for has no [NightReading] to carry
        // it, so its tile would sit empty while the table below printed the score — the same hole the
        // rows fixed, one level up. Now that a tile is also where a rating is TAPPED, an empty tile
        // after a tap would read as the tap having done nothing at all. The store is therefore the
        // fallback, and a tile means one thing either way: the rating filed for the morning of that
        // day — the night that ended on it, recorded or not.
        val ratingByDay = ratings.mapNotNull { (date, r) -> epochDayOf(date)?.let { it to r } }.toMap()
        // Notes are keyed by the same morning as the ratings, so they cross into grid days by exactly
        // the same conversion — never by the night's start, which is a day earlier for most nights.
        val notedDays = notes.keys.mapNotNull(::epochDayOf).toSet()
        val days = (fromEpochDay..toEpochDay).map { d ->
            DayCell(d, loadByDay[d], adverseByDay[d], feltByDay[d] ?: ratingByDay[d], d in notedDays)
        }

        // The union, so a rating whose date the band never recorded is still a line. Newest first.
        val nightByDate = nights.associateBy { dateOfNight(it.endMs) }
        val rows = (nightByDate.keys + ratings.keys + notes.keys).sortedDescending().map { date ->
            NightRow(date, nightByDate[date], ratings[date], notes[date])
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

        return Register(entries, days, nights.sortedByDescending { it.startMs }, rows, contrast)
    }
}
