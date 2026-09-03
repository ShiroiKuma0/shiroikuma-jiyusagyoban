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
         * The five readings the night table prints beside the two banded markers, and the 1–5 step
         * each is coloured with.
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
         *  * **Deep, deep+REM and RMSSD are WITHIN-PERSON**, banded against the nights before them,
         *    because no population ladder fits them. This band's "deep" is not polysomnography's N3
         *    — 白い熊's nights run 30–40 % of sleep where the literature's N3 is 13–23 %, so an
         *    absolute ladder would score every night he has ever recorded as extreme. RMSSD norms
         *    are so age-dependent that a population mean would paint the whole column one colour.
         *
         * **None of the five is COUNTED.** [adverseCount] is still the three markers it always was;
         * a colour here says where a value sits, never that the night was adverse. The standing
         * ruling that deep/REM and blood oxygen are excluded from the counting rule is untouched —
         * showing a value and scoring a night are different acts.
         */
        val deepMinutes: Double? = null,
        val deepRemShare: Double? = null,
        val lowestHr: Double? = null,
        val spo2: Double? = null,
        val hrvMs: Double? = null,
        /** The within-person steps for the three that have no published ladder. */
        val deepStep: Int? = null,
        val deepRemStep: Int? = null,
        val hrvStep: Int? = null,
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
