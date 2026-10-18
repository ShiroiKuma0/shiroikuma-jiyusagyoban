package com.opentasker.core.huawei

/**
 * `ecg_analysis_data.bin` — every ECG recording the Watch D3 holds, in one cumulative file.
 *
 * Pulled over the ordinary file channel (`0x2C`, type `0x18`) the way Health pulls it when its ECG
 * page opens; a measurement on the watch sends nothing by itself. Decoded and checked on
 * 2026-10-17 against Health's own list (docs/huawei-watch-d3.md §3.7):
 *
 *     file header, 57 bytes   …, `hw.health.ecganalysis` at 33, padded to 57
 *     per recording           u32 start, u32 end (Unix seconds), u32 sample-byte count
 *                             44 bytes: the watch's analysis version (`V1.0.0.27`), zeros,
 *                               u16 verdict at 34, u16 mean heart rate at 36
 *                             the samples: float32 big-endian, 500 Hz, single lead
 *
 * Verdicts seen: `0x01` sinus rhythm, `0x40` inconclusive. Others (A-fib …) are kept as their code.
 * Everything is big-endian, as everything else on this device is.
 */
object HuaweiEcg {
    const val FILE = "ecg_analysis_data.bin"
    const val TYPE = 0x18
    const val SAMPLE_RATE = 500
    const val VERDICT_SINUS = 0x01
    const val VERDICT_INCONCLUSIVE = 0x40

    private const val FILE_HEADER = 57
    private const val RECORD_HEADER = 12 + 44

    data class Recording(
        val startSeconds: Long,
        val endSeconds: Long,
        val verdict: Int,
        val meanHr: Int,
        val analysisVersion: String,
        /** The watch's own float32 BE bytes, unchanged. */
        val samples: ByteArray,
    ) {
        val sampleCount: Int get() = samples.size / 4

        override fun equals(other: Any?): Boolean =
            other is Recording && startSeconds == other.startSeconds && endSeconds == other.endSeconds &&
                verdict == other.verdict && meanHr == other.meanHr &&
                analysisVersion == other.analysisVersion && samples.contentEquals(other.samples)

        override fun hashCode(): Int = (startSeconds.hashCode() * 31 + verdict) * 31 + samples.contentHashCode()
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    /**
     * Every recording in the file. Stops at the first record whose header does not add up, rather
     * than reading on into nonsense: a wrong length here would otherwise misplace every later one.
     */
    fun parse(file: ByteArray): List<Recording> {
        val out = ArrayList<Recording>()
        var i = FILE_HEADER
        while (i + RECORD_HEADER <= file.size) {
            val start = u32(file, i)
            val end = u32(file, i + 4)
            val n = u32(file, i + 8).toInt()
            if (n <= 0 || n % 4 != 0 || end < start || end - start > 600 || i + RECORD_HEADER + n > file.size) break
            val block = file.copyOfRange(i + 12, i + RECORD_HEADER)
            val version = block.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.US_ASCII)
            out += Recording(
                startSeconds = start,
                endSeconds = end,
                verdict = u16(block, 34),
                meanHr = u16(block, 36),
                analysisVersion = version,
                samples = file.copyOfRange(i + RECORD_HEADER, i + RECORD_HEADER + n),
            )
            i += RECORD_HEADER + n
        }
        return out
    }

    /** The samples as numbers, for drawing and analysis. */
    fun decode(samples: ByteArray): FloatArray =
        FloatArray(samples.size / 4) { java.lang.Float.intBitsToFloat(u32(samples, it * 4).toInt()) }
}
