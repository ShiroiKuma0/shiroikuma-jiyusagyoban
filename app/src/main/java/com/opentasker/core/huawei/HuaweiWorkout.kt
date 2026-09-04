package com.opentasker.core.huawei

/**
 * Recorded exercises, and the pointer to their GPS tracks.
 *
 * ## Why this is a separate thing from the history fetch
 *
 * The per-minute records the sync walks are a continuous grid of whatever the band happened to
 * measure. A workout is not in that grid: it is an object with its own number, its own summary, and
 * — if the band saw satellites — a track that is not a record at all but a **file**, pulled over the
 * same `0x2C` channel as sleep and the RR intervals.
 *
 * ## What the band actually sends, measured
 *
 * This was written from published descriptions while 白い熊's band had never recorded a workout, and
 * it stayed that way until 2026-09-03, when the probe asked the band about ten of them — nine walks
 * and one strength-training session — and dumped every tag whole. What follows is no longer a
 * hypothesis:
 *
 * - **The summary carries thirty-one tags** and this decoded nine, dropping the rest in silence.
 *   Two of the dropped ones are the answer to "what did this workout cost me": `0x0A` is the mean
 *   speed in decimetres per second, and `0x66` is the twenty-five-point heart-rate curve the band
 *   records AFTER the workout ends — the cooldown.
 * - **`WORKOUT_SAMPLES` (0x0A) is the heart rate**, one reading every five seconds, and it had never
 *   been sent. Its blocks are addressable by index, so a long walk gives up its whole stream.
 * - **A workout without a track is still a workout.** Type 140 has no distance, no steps and no
 *   satellites, and the sync used to discard it for exactly that reason.
 *
 * The stream decode is not taken on trust: for walk 8 the sample count times the interval is the
 * stated duration to the second, the per-interval steps sum to 2910 against a stated 2892, and the
 * speeds integrate to 2267 m against a stated 2270. Three independent figures agreeing is why this
 * file no longer hedges.
 */
object HuaweiWorkout {

    /**
     * One workout, as the list reports it.
     *
     * [hasTrack] is the only field that decides whether a `_gps.bin` exists — asking the file
     * service for a track that was never recorded costs a round trip and answers "empty", which is
     * indistinguishable from a transfer that failed.
     */
    data class Entry(
        val number: Int,
        val sampleBlocks: Int = 0,
        val paceBlocks: Int = 0,
        val hasTrack: Boolean = false,
    )

    /** A workout's own summary. Every field is optional, because a band may simply not fill one. */
    data class Summary(
        val number: Int,
        val startSeconds: Long? = null,
        val endSeconds: Long? = null,
        val durationSeconds: Long? = null,
        val distanceMetres: Int? = null,
        val calories: Int? = null,
        val steps: Int? = null,
        /** Decimetres in the file; kept in the file's own unit and converted where it is shown. */
        val elevationGainDm: Int? = null,
        val type: Int? = null,
        /** Mean speed in decimetres per second — the band's own figure, tag `0x0A`. */
        val meanSpeedDmS: Int? = null,
        /**
         * The heart rate AFTER the workout, twenty-five readings, tag `0x66`.
         *
         * Its first value is the workout's last heart rate in all ten workouts measured, and it
         * falls away from there — 116 → 96 after the strength session, 109 → 91 after the walk of
         * the 25th, and barely at all after the two that ended mid-effort. That is a recovery
         * curve, and it is the one number a band can give that training load cannot: how fast the
         * heart lets go.
         *
         * The spacing between the points is NOT proven. Every other stream in this service is on a
         * five-second tick, which would make this the standard two-minute recovery window, but the
         * band never says so and neither does this file: the drop is reported, the elapsed time is
         * not.
         */
        val recovery: List<Int> = emptyList(),
    ) {
        /** How far the heart fell once the work stopped, in bpm. Null when nothing was recorded. */
        val recoveryDrop: Int?
            get() = recovery.filter { it > 0 }.takeIf { it.size >= 2 }?.let { it.first() - it.last() }
        /**
         * The sport, as far as we can name it.
         *
         * Only the outdoor kinds matter here, because only they carry a track. An unrecognised code
         * is returned as its number rather than mapped to "other": a band that starts reporting 27
         * should make that visible, not swallow it.
         */
        val kind: String
            get() = when (type) {
                1 -> "run"
                2 -> "walk"
                3 -> "cycle"
                4 -> "mountain hike"
                5 -> "indoor run"
                6 -> "pool swim"
                7 -> "indoor cycle"
                8 -> "open-water swim"
                11 -> "trail run"
                13 -> "indoor walk"
                14 -> "hike"
                // Measured, not read off a list: this is the code on the strength-training session
                // 白い熊 recorded on 2026-09-03 — 43 minutes, 217 kcal, no distance, no steps, and
                // a heart rate between 86 and 134. Every other code above came from a published
                // description; this one came from the band.
                STRENGTH -> "strength"
                // The band calls this one **Free exercise** — its catch-all for an activity with
                // no sport of its own, which is what 0xFF says. 白い熊 uses it for 機能訓練, so that
                // is what this app calls it; the band's own name is recorded here so the mapping
                // is visible rather than mysterious.
                FREE_EXERCISE -> "rehab"
                null -> "unknown"
                else -> "type $type"
            }

        /** Whether this kind is one the band would have tracked outdoors. */
        val isOutdoor: Boolean get() = type in setOf(1, 2, 3, 4, 11, 14)

        /** Lifting. Its own screen, because it shares nothing with a walk but the heart rate. */
        val isStrength: Boolean get() = type == STRENGTH

        /** 機能訓練. The band's Free exercise, which is what 白い熊 records rehab under. */
        val isRehab: Boolean get() = type == FREE_EXERCISE

        /** Neither of the trackless kinds is a walk, and none of them has a route. */
        val hasRoute: Boolean get() = !isStrength && !isRehab
    }

    /** The sport code the band puts on a strength-training session. */
    const val STRENGTH = 140

    /**
     * The band's **Free exercise** — measured on workout 23, 2026-09-04.
     *
     * `0xFF` is a sentinel rather than a sport, which is exactly what "free exercise" means: an
     * activity the band declines to classify. It records the same things a strength session does —
     * twenty minutes, no distance, 64 kcal, a five-second heart rate from 82 to 113 and a recovery
     * curve — so nothing in the decoder needed changing to read it.
     */
    const val FREE_EXERCISE = 255

    /**
     * Parse the reply to a workout-list request.
     *
     * The band answers with a container holding a count and then one sub-container per workout. The
     * count is read but NOT trusted as the number of entries: it is reported alongside what was
     * actually parsed, so a disagreement surfaces instead of being silently rounded away.
     */
    fun parseList(reply: List<HuaweiProtocol.Tlv>): List<Entry> {
        val outer = reply.firstOrNull { it.tag == 0x81 }?.value ?: return emptyList()
        return containers(outer, 0x85).mapNotNull { body ->
            val f = fields(body)
            val number = f[0x06]?.let { be(it) } ?: return@mapNotNull null
            Entry(
                number = number,
                sampleBlocks = f[0x07]?.let { be(it) } ?: 0,
                paceBlocks = f[0x08]?.let { be(it) } ?: 0,
                hasTrack = (f[0x0E]?.let { be(it) } ?: 0) == 1,
            )
        }
    }

    /** Parse the reply to a summary request. Returns null when the container is not there at all. */
    fun parseSummary(reply: List<HuaweiProtocol.Tlv>): Summary? {
        val body = reply.firstOrNull { it.tag == 0x81 }?.value ?: return null
        val f = fields(body)
        val number = f[0x02]?.let { be(it) } ?: return null
        fun u(tag: Int) = f[tag]?.let { be(it) }
        return Summary(
            number = number,
            startSeconds = u(0x04)?.toLong(),
            // Tag 0x05 is a real epoch the band sends, not a value derived here — but it is the
            // band's own arithmetic: measured on walk 8 it is exactly `start + duration`, where
            // duration is time-with-the-recorder-running. The GPS track for that same walk spans
            // 2 h 08 m of wall clock across three recording chunks, so this "end" is not when the
            // walk finished. Anything wanting the walk's real span must read the track, not this.
            endSeconds = u(0x05)?.toLong(),
            durationSeconds = u(0x12)?.toLong() ?: u(0x09)?.toLong(),
            distanceMetres = u(0x07),
            calories = u(0x06),
            steps = u(0x08),
            elevationGainDm = u(0x0B),
            type = u(0x14),
            meanSpeedDmS = u(0x0A),
            recovery = f[0x66]?.map { it.toInt() and 0xFF } ?: emptyList(),
        )
    }

    /**
     * One block of a workout's per-sample stream.
     *
     * The band pages this: a walk of forty minutes arrives as four blocks of at most 136 records,
     * each addressed by index. The header says everything needed to read the body, which is why
     * nothing here is assumed — [stride] and [fields] come off the wire, not out of a table.
     */
    data class SampleBlock(
        val number: Int,
        val block: Int,
        val startSeconds: Long,
        val intervalSeconds: Int,
        /** Heart rate per sample, `0` where the band recorded none (the first reading always is). */
        val heart: List<Int>,
        /** Speed in decimetres per second. Empty unless the stream carries it — a lift does not. */
        val speedDmS: List<Int>,
        /** Steps taken during that interval. Empty unless the stream carries it. */
        val steps: List<Int>,
    )

    /**
     * Parse one block of the per-sample stream.
     *
     * The fourteen-byte header at tag `0x04` is
     * `number(2) block(2) startEpoch(4) interval(1) count(2) stride(1) fields(2)`, and tag `0x05`
     * is `count × stride` bytes of records. Both walk and lift put the heart rate in the record's
     * first byte; a walk's stride of 4 adds a two-byte speed and a one-byte step count, which is
     * what its field bitmap `0x0007` means against the lift's `0x0001`.
     *
     * Every quantity is read from the header rather than inferred, so a firmware that adds a fourth
     * field lengthens the stride and this still reads the heart rate correctly instead of walking
     * off the end of the record.
     */
    fun parseSamples(reply: List<HuaweiProtocol.Tlv>): SampleBlock? {
        val body = reply.firstOrNull { it.tag == 0x81 }?.value ?: return null
        val f = fields(body)
        val head = f[0x04]?.takeIf { it.size >= 14 } ?: return null
        val data = f[0x05] ?: return null
        fun word(at: Int) = ((head[at].toInt() and 0xFF) shl 8) or (head[at + 1].toInt() and 0xFF)
        val count = word(9)
        val stride = head[11].toInt() and 0xFF
        val bitmap = word(12)
        // A stride that does not divide the body is a decode we do not understand, and half a
        // heart-rate curve is worse than none: it would be drawn, and believed.
        if (stride < 1 || count < 1 || count * stride > data.size) return null

        val hasSpeed = bitmap and 0x02 != 0 && stride >= 3
        val hasSteps = bitmap and 0x04 != 0 && stride >= 4
        val heart = ArrayList<Int>(count)
        val speed = ArrayList<Int>(if (hasSpeed) count else 0)
        val steps = ArrayList<Int>(if (hasSteps) count else 0)
        for (i in 0 until count) {
            val at = i * stride
            heart += data[at].toInt() and 0xFF
            if (hasSpeed) speed += ((data[at + 1].toInt() and 0xFF) shl 8) or (data[at + 2].toInt() and 0xFF)
            if (hasSteps) steps += data[at + 3].toInt() and 0xFF
        }
        return SampleBlock(
            number = word(0),
            block = word(2),
            startSeconds = (head.copyOfRange(4, 8).let { HuaweiProtocol.bytesToInt(it) }).toLong() and 0xFFFFFFFFL,
            intervalSeconds = head[8].toInt() and 0xFF,
            heart = heart,
            speedDmS = speed,
            steps = steps,
        )
    }

    /**
     * One split from the pace table — a kilometre, or a mile.
     *
     * The band keeps both, flagged by [mile], so a walk of 2.27 km arrives as two kilometre rows
     * and two mile rows plus the running total. The last row of each unit is the partial one and
     * carries [partialDecimetres]: 2700 dm is the final 270 m of that walk, 6610 dm the final
     * 0.41 of a mile.
     */
    data class Split(
        val index: Int,
        val mile: Boolean,
        val seconds: Int,
        val cumulativeSeconds: Int,
        val partialDecimetres: Int? = null,
    )

    /** Parse the pace reply: one `0x83` container per split. Absent for a workout without distance. */
    fun parsePace(reply: List<HuaweiProtocol.Tlv>): List<Split> {
        val body = reply.firstOrNull { it.tag == 0x81 }?.value ?: return emptyList()
        return containers(body, 0x83).mapNotNull { row ->
            val f = fields(row)
            Split(
                index = f[0x04]?.let { be(it) } ?: return@mapNotNull null,
                mile = (f[0x05]?.let { be(it) } ?: 0) == 1,
                seconds = f[0x06]?.let { be(it) } ?: return@mapNotNull null,
                cumulativeSeconds = f[0x07]?.let { be(it) } ?: 0,
                partialDecimetres = f[0x09]?.let { be(it) },
            )
        }
    }

    // --- TLV helpers -------------------------------------------------------------------------
    //
    // Local rather than shared: these read NESTED containers, which the frame-level parser has no
    // reason to know about, and the tags here (0x81, 0x85) are this service's own.

    private fun containers(payload: ByteArray, tag: Int): List<ByteArray> =
        runCatching { HuaweiProtocol.parseTlvs(payload) }.getOrNull()
            ?.filter { it.tag == tag }?.map { it.value } ?: emptyList()

    private fun fields(payload: ByteArray): Map<Int, ByteArray> =
        runCatching { HuaweiProtocol.parseTlvs(payload) }.getOrNull()
            ?.associate { it.tag to it.value } ?: emptyMap()

    /** Big-endian, like every integer in the TLV layer — and unlike everything inside a track file. */
    private fun be(v: ByteArray): Int? =
        if (v.isEmpty() || v.size > 4) null else HuaweiProtocol.bytesToInt(v)
}
