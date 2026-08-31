package com.opentasker.core.huawei

/**
 * Parsing for the band's history records — service `0x07`, count-then-index.
 *
 * Android-free and therefore JVM-testable. Verified against records pulled off 白い熊's own band on
 * firmware 6.0.0.125.
 *
 * **The shape is a sparse per-minute grid.** One record covers a block of minutes; each minute is a
 * sub-container carrying an offset and a feature bitmap, and a field is present only if the band
 * actually recorded it that minute. Most minutes are empty — a night of wear yields records where
 * two or three sub-containers out of thirty-four hold anything at all. That sparsity is the data,
 * not a fault, and it must not be filled in or smoothed away.
 */
object HuaweiRecords {

    /** Feature bits in the first bitmap. */
    private const val BIT_STEPS = 0x02
    private const val BIT_CALORIES = 0x04
    private const val BIT_DISTANCE = 0x08
    private const val BIT_HEART_RATE = 0x40

    /** Feature bits in the second bitmap, present only when bit 0x80 is set in the first. */
    private const val BIT2_SPO2 = 0x01
    private const val BIT2_RESTING_HR = 0x02

    /**
     * Bits whose value is ONE byte rather than two. Everything else in bitmap 1 is a big-endian
     * short; getting this wrong shifts every later field in the minute.
     */
    private val SINGLE_BYTE_BITS = setOf(0x20, 0x40)

    /** One minute of the grid. Absent fields stay null — never zero, which is a real reading. */
    data class Minute(
        val epochSeconds: Long,
        val steps: Int? = null,
        val calories: Int? = null,
        val distance: Int? = null,
        val heartRate: Int? = null,
        val spo2: Int? = null,
        val restingHeartRate: Int? = null,
        /** Values whose bit we do not yet understand, kept rather than dropped. */
        val unknown: Map<Int, Int> = emptyMap(),
    ) {
        /** True when the band recorded nothing this minute. */
        val isEmpty: Boolean
            get() = steps == null && calories == null && distance == null &&
                heartRate == null && spo2 == null && restingHeartRate == null &&
                unknown.isEmpty()
    }

    /** One record: a block of minutes sharing a base timestamp. */
    data class StepRecord(
        val index: Int,
        val baseEpochSeconds: Long,
        val minutes: List<Minute>,
    ) {
        val withData: List<Minute> get() = minutes.filterNot { it.isEmpty }
    }

    /** The count returned by a `0x0A`/`0x0C` query, or null if the reply had none. */
    fun parseCount(tlvs: List<HuaweiProtocol.Tlv>): Int? {
        val container = tlvs.firstOrNull { it.tag == 0x81 }?.value ?: return null
        return HuaweiProtocol.parseTlvs(container)
            .firstOrNull { it.tag == 0x02 }
            ?.let { HuaweiProtocol.bytesToInt(it.value) }
    }

    /**
     * Parse a step record (`0x07/0x0B`).
     *
     * Layout inside tag `0x81`: `0x02` index, `0x03` base timestamp, then repeated `0x84`
     * sub-containers of `{0x05 minuteOffset, 0x06 data}`.
     */
    fun parseStepRecord(tlvs: List<HuaweiProtocol.Tlv>): StepRecord? {
        val container = tlvs.firstOrNull { it.tag == 0x81 }?.value ?: return null
        val inner = HuaweiProtocol.parseTlvs(container)
        val index = inner.firstOrNull { it.tag == 0x02 }
            ?.let { HuaweiProtocol.bytesToInt(it.value) } ?: return null
        val base = inner.firstOrNull { it.tag == 0x03 }
            ?.let { HuaweiProtocol.bytesToInt(it.value).toLong() and 0xFFFFFFFFL } ?: return null

        val minutes = inner.filter { it.tag == 0x84 }.mapNotNull { sub ->
            val fields = HuaweiProtocol.parseTlvs(sub.value)
            val offset = fields.firstOrNull { it.tag == 0x05 }
                ?.let { HuaweiProtocol.bytesToInt(it.value) } ?: return@mapNotNull null
            val data = fields.firstOrNull { it.tag == 0x06 }?.value ?: ByteArray(0)
            decodeMinute(base + 60L * offset, data)
        }
        return StepRecord(index, base, minutes)
    }

    /**
     * Decode one minute's feature-bitmap payload.
     *
     * A truncated payload yields whatever was decodable rather than throwing: a single malformed
     * minute must not cost the whole record.
     */
    fun decodeMinute(epochSeconds: Long, data: ByteArray): Minute {
        if (data.isEmpty()) return Minute(epochSeconds)
        var i = 0
        val bitmap1 = data[i++].toInt() and 0xFF
        var bitmap2 = 0
        if (bitmap1 and 0x80 != 0) {
            if (i >= data.size) return Minute(epochSeconds)
            bitmap2 = data[i++].toInt() and 0xFF
        }

        var steps: Int? = null
        var calories: Int? = null
        var distance: Int? = null
        var heartRate: Int? = null
        var spo2: Int? = null
        var restingHr: Int? = null
        val unknown = LinkedHashMap<Int, Int>()

        // Bit 0x80 announces the SECOND bitmap; it is a flag, never a field. Reading a value for
        // it shifts every later field in the minute. (Gadgetbridge escapes this only by accident:
        // its loop counter is a signed byte that goes negative at 0x80 and exits.)
        var bit = 1
        while (bit <= 0x40) {
            if (bitmap1 and bit != 0) {
                val wide = bit !in SINGLE_BYTE_BITS
                val need = if (wide) 2 else 1
                if (i + need > data.size) break
                val value = if (wide) {
                    ((data[i++].toInt() and 0xFF) shl 8) or (data[i++].toInt() and 0xFF)
                } else {
                    data[i++].toInt() and 0xFF
                }
                when (bit) {
                    BIT_STEPS -> steps = value
                    BIT_CALORIES -> calories = value
                    BIT_DISTANCE -> distance = value
                    BIT_HEART_RATE -> heartRate = value
                    else -> unknown[bit] = value
                }
            }
            bit = bit shl 1
        }

        if (bitmap2 != 0) {
            var b2 = 1
            while (b2 <= 0x80) {
                if (bitmap2 and b2 != 0) {
                    if (i >= data.size) break
                    val value = data[i++].toInt() and 0xFF
                    when (b2) {
                        BIT2_SPO2 -> spo2 = value
                        BIT2_RESTING_HR -> restingHr = value
                        else -> unknown[b2 or 0x100] = value   // 0x100 marks "second bitmap"
                    }
                }
                b2 = b2 shl 1
            }
        }

        return Minute(epochSeconds, steps, calories, distance, heartRate, spo2, restingHr, unknown)
    }
}
