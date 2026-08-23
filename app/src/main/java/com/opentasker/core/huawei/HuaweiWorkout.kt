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
 * ## Everything here is unconfirmed, and says so
 *
 * 白い熊's band has never recorded a workout. Its own log reads `"GPSTrack":{"Count":0,"Duration":0}`
 * and every one of Huawei Health's workout queries in our capture came back empty — so this is
 * written from published protocol descriptions rather than from bytes we have seen. The parsers
 * therefore refuse rather than guess: a field that is not the expected width is dropped, and a list
 * whose count disagrees with its contents is reported as what was actually parsed. The first real
 * walk is what turns this from plausible into known, and the shapes below are the hypothesis it
 * tests.
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
    ) {
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
                null -> "unknown"
                else -> "type $type"
            }

        /** Whether this kind is one the band would have tracked outdoors. */
        val isOutdoor: Boolean get() = type in setOf(1, 2, 3, 4, 11, 14)
    }

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
            endSeconds = u(0x05)?.toLong(),
            durationSeconds = u(0x12)?.toLong() ?: u(0x09)?.toLong(),
            distanceMetres = u(0x07),
            calories = u(0x06),
            steps = u(0x08),
            elevationGainDm = u(0x0B),
            type = u(0x14),
        )
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
