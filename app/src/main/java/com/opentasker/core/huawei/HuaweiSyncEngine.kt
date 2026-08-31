package com.opentasker.core.huawei

/**
 * Turning the band's records into storable samples, and driving the count-then-index fetch.
 *
 * Android-free so the conversion — the part that can silently corrupt data — is unit-tested.
 *
 * **The band's history protocol is stateful in a way nothing documents.** A count query
 * (`0x07/0x0A`) sets the working window, and record indices are relative to *that* window. Ask for
 * everything since the epoch and then request record 1 and the band answers with error 106489; ask
 * for a 24-hour window first and the identical request succeeds. So a count always immediately
 * precedes its fetches, and the window is never changed mid-run.
 */
object HuaweiSyncEngine {

    /** Metric names. Kept distinct from the Hume band's on purpose — the two are never pooled. */
    const val METRIC_STEPS = "steps"
    const val METRIC_CALORIES = "calories"
    const val METRIC_DISTANCE = "distance"
    const val METRIC_HEART_RATE = "hr"
    const val METRIC_SPO2 = "spo2"
    const val METRIC_RESTING_HR = "resting_hr"

    /** One row destined for `huawei_samples`, without the sync id the caller stamps on. */
    data class Sample(val metric: String, val epochSeconds: Long, val value: Double)

    /**
     * Flatten a record into samples.
     *
     * Only fields the band actually recorded become rows: an empty minute produces nothing at all.
     * A zero it *did* record is kept, because zero steps in a minute is a real observation and
     * discarding it would misrepresent the day.
     *
     * Feature bits we do not understand are carried through as `unknown_XX` rather than dropped, so
     * a firmware change shows up in the data instead of vanishing silently.
     */
    fun toSamples(record: HuaweiRecords.StepRecord): List<Sample> {
        val out = ArrayList<Sample>()
        for (m in record.minutes) {
            m.steps?.let { out += Sample(METRIC_STEPS, m.epochSeconds, it.toDouble()) }
            m.calories?.let { out += Sample(METRIC_CALORIES, m.epochSeconds, it.toDouble()) }
            m.distance?.let { out += Sample(METRIC_DISTANCE, m.epochSeconds, it.toDouble()) }
            m.heartRate?.let { out += Sample(METRIC_HEART_RATE, m.epochSeconds, it.toDouble()) }
            m.spo2?.let { out += Sample(METRIC_SPO2, m.epochSeconds, it.toDouble()) }
            // A resting heart rate of ZERO is the band's null, not a measurement — unlike steps,
            // where zero is a real count. It sets the field in the same minutes as the live heart
            // rate and fills it with 0 until it has actually computed one: 426 of 431 readings on
            // 白い熊's 2026-08-23 were zeros, which the chart then drew as a day-long absence.
            m.restingHeartRate?.takeIf { it > 0 }?.let {
                out += Sample(METRIC_RESTING_HR, m.epochSeconds, it.toDouble())
            }
            for ((bit, value) in m.unknown) {
                out += Sample("unknown_%02X".format(bit), m.epochSeconds, value.toDouble())
            }
        }
        return out
    }

    /** Result of one history run. */
    data class Fetch(
        val requestedFrom: Long,
        val requestedTo: Long,
        val recordCount: Int,
        val recordsFetched: Int,
        val samples: List<Sample>,
        /** Indices the band refused or dropped. Empty is the expected case; anything else matters. */
        val missing: List<Int>,
        /** The index each returned record claims for ITSELF — the band's own numbering. */
        val returnedIndices: List<Int> = emptyList(),
    )

    /**
     * Count, then fetch every record in `[from, to]`.
     *
     * A record that fails is recorded in [Fetch.missing] rather than aborting the run — banking
     * what we have beats losing a whole sync to one bad index. But it is never silently ignored:
     * the caller writes the gap into `huawei_syncs`.
     */
    suspend fun fetchHistory(
        session: HuaweiSession,
        from: Long,
        to: Long,
        maxRecords: Int = 4096,
        /** Called as each record is attempted, so a caller can show progress rather than dead air. */
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Fetch {
        val countFrame = session.request(
            HuaweiCommands.SVC_FITNESS,
            HuaweiCommands.FIT_STEP_COUNT,
            HuaweiCommands.fitnessCount(from, to),
            timeoutMs = 25_000,
        )
        val count = HuaweiRecords.parseCount(session.decrypt(countFrame)) ?: 0

        val samples = ArrayList<Sample>()
        val missing = ArrayList<Int>()
        val returnedIndices = ArrayList<Int>()
        var fetched = 0
        // Record indices are ZERO-BASED: a window of N records is `0 until N`.
        //
        // Measured, not assumed. This ran as `1..count` until 2026-08-22, which refused exactly one
        // record on every sync — 15/16, then 3/4 — and the visible error was the harmless half of
        // the bug: asking for index `count` failed loudly, while record 0 was skipped in silence,
        // losing the oldest record of every window with nothing to show for it. A probe over
        // `0..count` settled it: `refused [2] · records claim [0,1]` for a count of 2.
        //
        // The reference implementation never established this — it only ever pulled record #1 to
        // look at the shape — so there was nothing to copy and no reason to think it was known.
        val total = minOf(count, maxRecords)
        for (index in 0 until total) {
            onProgress(index, total)
            val record = runCatching {
                val frame = session.request(
                    HuaweiCommands.SVC_FITNESS,
                    HuaweiCommands.FIT_STEP_RECORD,
                    HuaweiCommands.fitnessRecord(index),
                    timeoutMs = 25_000,
                )
                HuaweiRecords.parseStepRecord(session.decrypt(frame))
            }.getOrNull()
            if (record == null) {
                missing += index
                continue
            }
            fetched++
            returnedIndices += record.index
            samples += toSamples(record)
        }
        return Fetch(from, to, count, fetched, samples, missing, returnedIndices)
    }

    /**
     * Deduplicate before writing.
     *
     * Overlapping windows are normal — we deliberately re-ask for a margin so a boundary minute is
     * never lost — so the same `(metric, minute)` can arrive twice in one run. The LAST value wins,
     * matching the REPLACE conflict strategy the table uses.
     */
    fun dedupe(samples: List<Sample>): List<Sample> {
        val byKey = LinkedHashMap<Pair<String, Long>, Sample>()
        for (s in samples) byKey[s.metric to s.epochSeconds] = s
        return byKey.values.toList()
    }
}
