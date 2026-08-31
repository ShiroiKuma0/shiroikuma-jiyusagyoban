package com.opentasker.core.band

/**
 * Per-stream record parsers. Pure Kotlin, no Android — these are the part that has to be right, and
 * the golden frames in BandRecordsTest are real bytes off 白い熊's band.
 *
 * Records tile from offset 0 at the stream's stride, and **each record repeats a 3-byte prefix**
 * (opcode, sequence index, 0x00) — so every field offset below is *within a record*, not within the
 * frame.
 *
 * Every record is validated by its BCD date field. A slice whose nibbles are not valid BCD, or which
 * is all 0x00 / 0xFF, is discarded rather than invented. That is what makes it safe to slice records
 * out of a frame *before* checking for the stream terminator: if a terminator ever does carry real
 * records we keep them, and if it is a pure sentinel every slice fails validation.
 */

/** The metric names used in band_samples, the JSONL and the census. */
object BandMetric {
    const val HEART_RATE = "hr"
    const val HRV = "hrv"
    const val VASCULAR = "vascular"
    const val HRV_HEART_RATE = "hrv_hr"
    const val STRESS = "stress"
    const val SYSTOLIC = "sbp"
    const val DIASTOLIC = "dbp"
    const val TEMPERATURE = "temp"
    const val SPO2 = "spo2"
    const val STEPS_MINUTE = "steps_min"
    const val STEPS_BUCKET = "steps_10m"
    const val CALORIES_BUCKET = "kcal_10m"
    const val DISTANCE_BUCKET = "dist_10m"
}

/** One measurement. [localTs] is yyyyMMddHHmmss straight off the wire — the dedupe key. */
data class BandSample(val metric: String, val localTs: Long, val value: Double)

/** One calendar day's totals. [rawExercise] and [rawTail] are stored unnamed on purpose — see below. */
data class BandDaily(
    val localDate: Long,
    val steps: Long,
    val distanceM: Double,
    val calories: Double,
    val rawExercise: Long,
    val rawTail: String,
)

/**
 * One sleep SEGMENT, not one night.
 *
 * A 0x53 frame is 130 bytes and the stage bytes start at [10], so a single record covers at most 120
 * minutes. A night is therefore several segments and the UI stitches contiguous ones. Modelling this
 * as "one row per night" is the mistake that hurts forever.
 *
 * [stages] holds the RAW codes as digit characters: 1 = deep, 2 = light, 3 = REM, 5 = awake. The Hume
 * plugin re-codes these to 1=deep 2=light 3=awake 4=REM before its own Dart layer sees them; mixing
 * the two schemes silently swaps REM and awake. Code 4 has never been observed here — it is counted
 * as unknown.
 */
data class BandSleepSegment(val startLocalTs: Long, val minutes: Int, val stages: String) {
    val deep: Int get() = stages.count { it == '1' }
    val light: Int get() = stages.count { it == '2' }
    val rem: Int get() = stages.count { it == '3' }
    val awake: Int get() = stages.count { it == '5' }
    val unknown: Int get() = stages.length - deep - light - rem - awake
}

/** Everything one frame yielded. */
data class BandParsedFrame(
    val samples: List<BandSample> = emptyList(),
    val daily: List<BandDaily> = emptyList(),
    val sleep: List<BandSleepSegment> = emptyList(),
) {
    val recordCount: Int get() = samples.size + daily.size + sleep.size
    operator fun plus(other: BandParsedFrame) =
        BandParsedFrame(samples + other.samples, daily + other.daily, sleep + other.sleep)
}

object BandRecords {

    /** True when a slice is padding rather than a record. */
    private fun isBlank(slice: ByteArray): Boolean =
        slice.all { it == 0.toByte() } || slice.all { it == 0xFF.toByte() }

    /**
     * Parse one notification frame for [stream].
     *
     * Sleep is special: the whole frame is one record. Everything else tiles at the stream's stride.
     */
    fun parse(stream: BandStream, frame: ByteArray): BandParsedFrame = when (stream) {
        BandStream.SLEEP -> parseSleep(frame)
        BandStream.DAILY -> parseTiled(frame, 27, ::parseDaily)
        BandStream.DETAIL -> parseTiled(frame, 25, ::parseDetail)
        BandStream.HEART_RATE -> parseTiled(frame, 10) { r -> simple(r, BandMetric.HEART_RATE) }
        BandStream.SPO2 -> parseTiled(frame, 10) { r -> simple(r, BandMetric.SPO2) }
        BandStream.HRV -> parseTiled(frame, 15, ::parseHrv)
        BandStream.TEMPERATURE -> parseTiled(frame, 11, ::parseTemperature)
        else -> BandParsedFrame()
    }

    private fun parseTiled(
        frame: ByteArray,
        stride: Int,
        one: (ByteArray) -> BandParsedFrame,
    ): BandParsedFrame {
        var out = BandParsedFrame()
        var off = 0
        while (off + stride <= frame.size) {
            val slice = frame.copyOfRange(off, off + stride)
            if (!isBlank(slice)) out += one(slice)
            off += stride
        }
        return out
    }

    /** [3..8] BCD datetime · [9] value. Shared by heart rate and SpO₂. */
    private fun simple(r: ByteArray, metric: String): BandParsedFrame {
        val ts = BandProtocol.readBcdDateTime(r, 3) ?: return BandParsedFrame()
        val v = r[9].toInt() and 0xFF
        if (v == 0 || v == 0xFF) return BandParsedFrame()
        return BandParsedFrame(samples = listOf(BandSample(metric, ts, v.toDouble())))
    }

    /**
     * [3..8] datetime · [9] HRV ms · [10] vascular age · [11] heart rate · [12] stress
     * · [13] systolic · [14] diastolic.
     *
     * Zero fields are omitted rather than stored: a real frame carries HR 0 / SBP 0 / DBP 0 when the
     * band did not take those, and storing them as measurements would drag every average down.
     */
    private fun parseHrv(r: ByteArray): BandParsedFrame {
        val ts = BandProtocol.readBcdDateTime(r, 3) ?: return BandParsedFrame()
        val out = mutableListOf<BandSample>()
        fun add(metric: String, raw: Int) {
            if (raw != 0 && raw != 0xFF) out += BandSample(metric, ts, raw.toDouble())
        }
        add(BandMetric.HRV, r[9].toInt() and 0xFF)
        add(BandMetric.VASCULAR, r[10].toInt() and 0xFF)
        add(BandMetric.HRV_HEART_RATE, r[11].toInt() and 0xFF)
        add(BandMetric.STRESS, r[12].toInt() and 0xFF)
        add(BandMetric.SYSTOLIC, r[13].toInt() and 0xFF)
        add(BandMetric.DIASTOLIC, r[14].toInt() and 0xFF)
        return BandParsedFrame(samples = out)
    }

    /** [3..8] datetime · [9..10] LE16 tenths of a degree. */
    private fun parseTemperature(r: ByteArray): BandParsedFrame {
        val ts = BandProtocol.readBcdDateTime(r, 3) ?: return BandParsedFrame()
        val raw = BandProtocol.le16(r, 9) ?: return BandParsedFrame()
        if (raw == 0 || raw == 0xFFFF) return BandParsedFrame()
        return BandParsedFrame(samples = listOf(BandSample(BandMetric.TEMPERATURE, ts, raw / 10.0)))
    }

    /**
     * [3..8] datetime · [9..10] steps LE16 · [11..12] kcal LE16 ÷100 · [13..14] km LE16 ÷100
     * · [15..24] TEN per-minute step counts.
     *
     * The ten per-minute counts run FORWARD from the record's timestamp (t+0 … t+9). The hand-off
     * left this as its one unconfirmed field — backward would shift every step sample by nine
     * minutes — and it is now settled against 87 real records:
     *
     * · Slot 0 is non-zero in 87 of 87. Twenty of those records have exactly ONE non-zero slot, and
     *   in all twenty it is slot 0. Backward would put a lone sample at a uniformly random index, so
     *   that is a 1-in-10^20 coincidence. Forward explains it structurally: the band opens the
     *   bucket when the first step of it lands, which is also why the timestamps carry arbitrary
     *   seconds (:31, :26, :22 …) instead of sitting on a ten-minute grid.
     * · The per-minute counts sum to the record's own [9..10] step total in all 87, and the
     *   per-minute total for a day matches the 0x51 daily total exactly.
     * · No two consecutive records are less than ten minutes apart, so forward buckets never overlap.
     *
     * The vendor SDK (`ResolveUtil.getDetailData`) confirms every offset above but emits the ten
     * counts as an opaque space-separated string, so it could not answer the direction question.
     */
    private fun parseDetail(r: ByteArray): BandParsedFrame {
        val ts = BandProtocol.readBcdDateTime(r, 3) ?: return BandParsedFrame()
        val steps = BandProtocol.le16(r, 9) ?: return BandParsedFrame()
        val kcal = BandProtocol.le16(r, 11) ?: 0
        val km = BandProtocol.le16(r, 13) ?: 0
        val out = mutableListOf(
            BandSample(BandMetric.STEPS_BUCKET, ts, steps.toDouble()),
            BandSample(BandMetric.CALORIES_BUCKET, ts, kcal / 100.0),
            BandSample(BandMetric.DISTANCE_BUCKET, ts, km / 100.0),
        )
        for (i in 0 until 10) {
            val idx = 15 + i
            if (idx >= r.size) break
            val perMinute = r[idx].toInt() and 0xFF
            if (perMinute == 0) continue
            out += BandSample(BandMetric.STEPS_MINUTE, addMinutes(ts, i), perMinute.toDouble())
        }
        return BandParsedFrame(samples = out)
    }

    /**
     * [2..4] BCD date · [5..8] steps LE32 · [9..12] unknown LE32 · [13..16] distance LE32 ÷100
     * · [17..20] calories LE32 ÷100 · [21..26] unknown tail.
     *
     * Note the date sits at [2], not [3]: this stream's prefix is two bytes, not three.
     *
     * [9..12] is labelled ExerciseMinutes by the vendor SDK but reads ~0.4× steps against real
     * frames, so the label is wrong; [21..22] is claimed to be the step goal but reads 0x0000 while
     * the 0x4B query reports 10000. Both are stored raw under neutral names and nothing is built on
     * them.
     */
    private fun parseDaily(r: ByteArray): BandParsedFrame {
        val date = BandProtocol.readBcdDate(r, 2) ?: return BandParsedFrame()
        val steps = BandProtocol.le32(r, 5) ?: return BandParsedFrame()
        val rawExercise = BandProtocol.le32(r, 9) ?: 0
        val distance = BandProtocol.le32(r, 13) ?: 0
        val calories = BandProtocol.le32(r, 17) ?: 0
        val tail = r.copyOfRange(21, minOf(27, r.size)).joinToString("") { "%02x".format(it) }
        return BandParsedFrame(
            daily = listOf(
                BandDaily(
                    localDate = date,
                    steps = steps,
                    // The raw LE32 is hundredths of a KILOMETRE (607 -> 6.07 km); stored as
                    // metres, which is what the JSONL archive carries.
                    distanceM = distance / 100.0 * 1000.0,
                    calories = calories / 100.0,
                    rawExercise = rawExercise,
                    rawTail = tail,
                ),
            ),
        )
    }

    /** [3..8] BCD start · [9] minute count (≤120) · [10..] one stage byte per minute. */
    private fun parseSleep(frame: ByteArray): BandParsedFrame {
        val start = BandProtocol.readBcdDateTime(frame, 3) ?: return BandParsedFrame()
        val declared = if (frame.size > 9) frame[9].toInt() and 0xFF else 0
        if (declared <= 0) return BandParsedFrame()
        val available = maxOf(0, frame.size - 10)
        val minutes = minOf(declared, available, 120)
        if (minutes <= 0) return BandParsedFrame()
        val stages = buildString {
            for (i in 0 until minutes) append((frame[10 + i].toInt() and 0xFF).toString())
        }
        return BandParsedFrame(sleep = listOf(BandSleepSegment(start, minutes, stages)))
    }

    /**
     * Add whole minutes to a yyyyMMddHHmmss value.
     *
     * Via java.time.LocalDateTime — a LOCAL date-time, so no zone is invented for a value the band
     * expresses without one, and month and year rollover are handled instead of hand-rolled. Doing
     * the arithmetic on the digits directly is where "day 32" comes from when a detail record starts
     * within ten minutes of a month end. java.time is available natively at minSdk 26 and is not an
     * android.* import, so this file stays JVM-testable.
     */
    internal fun addMinutes(localTs: Long, minutes: Int): Long {
        if (minutes == 0) return localTs
        val moved = java.time.LocalDateTime.of(
            (localTs / 10_000_000_000L).toInt(),
            ((localTs / 100_000_000L) % 100).toInt(),
            ((localTs / 1_000_000L) % 100).toInt(),
            ((localTs / 10_000L) % 100).toInt(),
            ((localTs / 100L) % 100).toInt(),
            (localTs % 100).toInt(),
        ).plusMinutes(minutes.toLong())
        return moved.year.toLong() * 10_000_000_000L +
            moved.monthValue.toLong() * 100_000_000L +
            moved.dayOfMonth.toLong() * 1_000_000L +
            moved.hour.toLong() * 10_000L +
            moved.minute.toLong() * 100L +
            moved.second.toLong()
    }
}
