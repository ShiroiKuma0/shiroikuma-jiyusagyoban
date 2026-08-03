package com.opentasker.core.band

/**
 * The Hume Band V2 wire protocol — frame encoding, BCD, checksum.
 *
 * Deliberately Android-free so it can be JVM-tested: this repo has no Robolectric and no MockK, so
 * anything that touches `android.*` cannot be unit-tested at all. Every rule the band imposes lives
 * here, verified against real frames captured from 白い熊's band on 2026-08-02.
 *
 * The band takes no pairing, no bonding and no auth: a machine that has never seen it can connect by
 * MAC and read everything. The frames are plaintext with an additive checksum.
 */

/**
 * Frame mode. The firmware also defines a DESTRUCTIVE erase mode; it is deliberately ABSENT from this
 * enum and there is no Int-taking frame builder, so no call site in the app can express it. This KDoc
 * line is the only place in core/band that mentions it — BandSafetyGuardTest fails the build if that
 * stops being true.
 */
enum class BandReadMode(internal val raw: Int) { START(0x00), CONTINUE(0x02) }

/**
 * A readable stream on the band.
 *
 * [stride] is the record size *within* a frame, or null where a frame is one whole record (sleep) or
 * the slot is dead. [key] is the short name used in the census, the JSONL and the metric constants.
 *
 * The five slots marked dead are requested anyway: each costs one round trip, and if a firmware
 * update ever lights one up the census notices. An empty stream is never an error.
 */
enum class BandStream(
    val opcode: Int,
    val key: String,
    val stride: Int?,
    val expectedEmpty: Boolean = false,
) {
    HEART_RATE(0x55, "hr", 10),
    HRV(0x56, "hrv", 15),
    SPO2(0x66, "spo2", 10),
    TEMPERATURE(0x65, "temp", 11),
    SLEEP(0x53, "sleep", null),
    DAILY(0x51, "daily", 27),
    DETAIL(0x52, "detail", 25),

    // Dead on firmware 0.0.2.5 — see the KDoc above.
    DYNAMIC_HR(0x54, "dyn_hr", null, expectedEmpty = true),
    WORKOUT(0x5C, "workout", null, expectedEmpty = true),
    HR_ONE_SECOND(0x5E, "hr_1s", null, expectedEmpty = true),
    MANUAL_SPO2(0x60, "spo2_manual", null, expectedEmpty = true),
    TEMPERATURE_HISTORY(0x62, "temp_history", null, expectedEmpty = true);

    companion object {
        /**
         * Request order: most valuable first, so a late timeout still banks the important data, with
         * the five expected-empty slots last.
         */
        val SYNC_ORDER: List<BandStream> = listOf(
            HEART_RATE, HRV, SPO2, TEMPERATURE, SLEEP, DAILY, DETAIL,
            DYNAMIC_HR, WORKOUT, HR_ONE_SECOND, MANUAL_SPO2, TEMPERATURE_HISTORY,
        )

        fun byKey(key: String): BandStream? = entries.firstOrNull { it.key == key }
    }
}

/** An info query: a single 16-byte reply, no paging. */
enum class BandInfoQuery(val opcode: Int) {
    DEVICE_INFO(0x04),
    BATTERY(0x13),
    MAC(0x22),
    FIRMWARE(0x27),
    CLOCK(0x41),
    USER_INFO(0x42),
    STEP_GOAL(0x4B),
    ALARMS(0x57),
}

/**
 * A local wall-clock instant as the band expresses it — no timezone, because the band has none.
 * Only the date part reaches the wire; the band streams from the start of that day.
 */
data class BandLocalTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0,
)

/** What to ask the band for. The only thing [BandProtocol.encode] accepts. */
data class BandCommand(
    val opcode: Int,
    val mode: BandReadMode,
    val at: BandLocalTime?,
) {
    companion object {
        fun start(stream: BandStream, at: BandLocalTime) = BandCommand(stream.opcode, BandReadMode.START, at)

        /**
         * A CONTINUE frame carries a ZERO date — bytes [4..9] are zero, NOT a repeat of the start
         * date. Proven from the Hume app's own code: it calls the continue variant with an empty
         * date string and the encoder returns early, leaving those bytes untouched.
         */
        fun cont(stream: BandStream) = BandCommand(stream.opcode, BandReadMode.CONTINUE, null)

        fun info(query: BandInfoQuery) = BandCommand(query.opcode, BandReadMode.START, null)
    }
}

object BandProtocol {
    const val FRAME_SIZE = 16

    /** The byte values [encode] may ever place at [1]. The write chokepoint asserts membership. */
    val ALLOWED_MODE_BYTES: Set<Int> = BandReadMode.entries.map { it.raw }.toSet()

    /**
     * The ONLY frame builder. There is deliberately no encode(opcode: Int, mode: Int, …) — a caller
     * has nothing to hand this but a value built from the two-member [BandReadMode].
     */
    fun encode(command: BandCommand): ByteArray {
        val frame = ByteArray(FRAME_SIZE)
        frame[0] = (command.opcode and 0xFF).toByte()
        frame[1] = (command.mode.raw and 0xFF).toByte()
        command.at?.let { at ->
            frame[4] = toBcd(at.year % 100)
            frame[5] = toBcd(at.month)
            frame[6] = toBcd(at.day)
            frame[7] = toBcd(at.hour)
            frame[8] = toBcd(at.minute)
            frame[9] = toBcd(at.second)
        }
        frame[15] = checksum(frame)
        return frame
    }

    /** Additive: bytes 0..14 summed, low byte. */
    fun checksum(frame: ByteArray): Byte {
        var sum = 0
        for (i in 0 until FRAME_SIZE - 1) sum += frame[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    /**
     * BCD: the decimal digits are read as hex nibbles, so 26 → 0x26. The band's own encoder is
     * literally Integer.parseInt(value.toString(), 16).
     */
    fun toBcd(value: Int): Byte {
        require(value in 0..99) { "BCD holds 0..99, got $value" }
        return (((value / 10) shl 4) or (value % 10)).toByte()
    }

    /** Inverse of [toBcd]. Returns null when either nibble is not a decimal digit. */
    fun fromBcd(b: Byte): Int? {
        val v = b.toInt() and 0xFF
        val high = v shr 4
        val low = v and 0x0F
        if (high > 9 || low > 9) return null
        return high * 10 + low
    }

    /**
     * Read six BCD bytes at [off] as yyyyMMddHHmmss.
     *
     * This value — the band's own wall clock, byte for byte, with no timezone applied — is the dedupe
     * key everywhere downstream. Returns null if any nibble is not decimal or the fields are out of
     * range, which is also how a padding or sentinel slice is rejected.
     */
    fun readBcdDateTime(src: ByteArray, off: Int): Long? {
        if (off + 6 > src.size) return null
        val yy = fromBcd(src[off]) ?: return null
        val mm = fromBcd(src[off + 1]) ?: return null
        val dd = fromBcd(src[off + 2]) ?: return null
        val hh = fromBcd(src[off + 3]) ?: return null
        val mi = fromBcd(src[off + 4]) ?: return null
        val ss = fromBcd(src[off + 5]) ?: return null
        if (mm !in 1..12 || dd !in 1..31 || hh > 23 || mi > 59 || ss > 59) return null
        val year = 2000 + yy
        return year.toLong() * 10_000_000_000L +
            mm.toLong() * 100_000_000L +
            dd.toLong() * 1_000_000L +
            hh.toLong() * 10_000L +
            mi.toLong() * 100L +
            ss.toLong()
    }

    /** Read six BCD bytes at [off] as yyyyMMdd, ignoring any time part. */
    fun readBcdDate(src: ByteArray, off: Int): Long? {
        if (off + 3 > src.size) return null
        val yy = fromBcd(src[off]) ?: return null
        val mm = fromBcd(src[off + 1]) ?: return null
        val dd = fromBcd(src[off + 2]) ?: return null
        if (mm !in 1..12 || dd !in 1..31) return null
        return (2000 + yy).toLong() * 10_000L + mm.toLong() * 100L + dd.toLong()
    }

    /** Little-endian unsigned 16-bit. */
    fun le16(src: ByteArray, off: Int): Int? {
        if (off + 2 > src.size) return null
        return (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)
    }

    /** Little-endian unsigned 32-bit. */
    fun le32(src: ByteArray, off: Int): Long? {
        if (off + 4 > src.size) return null
        return (src[off].toLong() and 0xFF) or
            ((src[off + 1].toLong() and 0xFF) shl 8) or
            ((src[off + 2].toLong() and 0xFF) shl 16) or
            ((src[off + 3].toLong() and 0xFF) shl 24)
    }

    /** A frame whose last byte is 0xFF ends its stream. Observed terminators are two bytes and carry no records. */
    fun isTerminator(frame: ByteArray): Boolean =
        frame.isNotEmpty() && frame[frame.size - 1] == 0xFF.toByte()

    /**
     * Firmware version out of a `0x27` reply: four bytes, one per dotted component.
     *
     * Captured from 白い熊's band 2026-08-02: `27 00 00 02 05` -> "0.0.2.5".
     */
    fun parseFirmware(reply: ByteArray): String? {
        if (reply.size < 5) return null
        return (1..4).joinToString(".") { (reply[it].toInt() and 0xFF).toString() }
    }

    /**
     * Battery percentage out of a `0x13` reply: one byte.
     *
     * Captured 2026-08-02: `13 4c` -> 76 %.
     */
    fun parseBattery(reply: ByteArray): Int? =
        if (reply.size >= 2) reply[1].toInt() and 0xFF else null

}
