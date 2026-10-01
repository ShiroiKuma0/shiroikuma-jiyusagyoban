package com.opentasker.core.gengoshima

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Several Ogg Opus files joined into ONE logical stream, without decoding or re-encoding a sample.
 *
 * For 言語島's whole-island file (「000 島全体.ogg」), which 白い熊の辞書 plays as one track against one
 * subtitle file — it has no playlist. Joining the sentence files byte for byte would make a CHAINED
 * Ogg (a new stream per file), which is legal but is exactly where players disagree about duration
 * and seeking. So the pages are rewritten into one stream instead:
 *
 * * the first file keeps its two header packets (OpusHead, OpusTags); the others lose theirs — every
 *   file 音声 writes has the same parameters, so one header describes them all;
 * * every page gets the first file's serial number and a fresh sequence number;
 * * every granule position after the first file is shifted by the samples before it, so time runs
 *   on without a break;
 * * BOS stays on the first page only, EOS goes on the last page only, and every CRC is recomputed.
 *
 * The later files' pre-skip (a few milliseconds of encoder warm-up) is played rather than skipped;
 * inaudible, and it keeps the arithmetic exact. [Result.starts] says where each file begins, in
 * milliseconds of the joined audio, which is what the subtitle cues are cut from.
 */
object OggConcat {

    class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val segments: ByteArray,
        val body: ByteArray,
    )

    class Result(val bytes: ByteArray, val startsMs: List<Long>, val endsMs: List<Long>)

    fun parse(file: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var p = 0
        val bb = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)
        while (p + 27 <= file.size) {
            require(file[p] == 'O'.code.toByte() && file[p + 1] == 'g'.code.toByte() &&
                file[p + 2] == 'g'.code.toByte() && file[p + 3] == 'S'.code.toByte()) { "not an Ogg page at $p" }
            val headerType = file[p + 5].toInt() and 0xFF
            val granule = bb.getLong(p + 6)
            val serial = bb.getInt(p + 14)
            val nseg = file[p + 26].toInt() and 0xFF
            val segs = file.copyOfRange(p + 27, p + 27 + nseg)
            val bodyLen = segs.sumOf { it.toInt() and 0xFF }
            val start = p + 27 + nseg
            pages += Page(headerType, granule, serial, segs, file.copyOfRange(start, start + bodyLen))
            p = start + bodyLen
        }
        return pages
    }

    /** OpusHead's pre-skip, in 48 kHz samples. */
    fun preSkip(pages: List<Page>): Int {
        val head = pages.first().body
        require(String(head, 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an Opus stream" }
        return (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
    }

    /**
     * Join [files], with [leadMs] of silence before the first and [gapMs] between each pair.
     *
     * The sentence files already end in the ~0.25 s 音声 appends, which proved too short to hear as a
     * pause once the sentences were spliced together (白い熊, 2026-10-01: "no pause between
     * sentences"). The silence is real Opus — [SILENCE], one 20 ms packet repeated — so nothing is
     * re-encoded and the timeline stays exact. Each cue STARTS where the silence before its sentence
     * starts and ends where the sentence ends (白い熊, 2026-10-01), so the cues run on without a gap
     * and every sentence is preceded, inside its own cue, by its pause.
     */
    fun join(files: List<ByteArray>, leadMs: Long = 0, gapMs: Long = 0): Result {
        require(files.isNotEmpty())
        val out = ByteArrayOutputStream()
        val parsed = files.map(::parse)
        val serial = parsed.first().first().serial
        val preSkip = preSkip(parsed.first())
        var seq = 0
        var granule = 0L
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        // The two header packets of the first file describe the whole stream.
        parsed.first().takeWhile { it.granule == 0L }.forEachIndexed { i, page ->
            out.write(page(if (i == 0) 0x02 else 0x00, 0L, serial, seq++, page.segments, page.body))
        }
        fun silence(ms: Long) {
            repeat(((ms + 10) / 20).toInt()) {
                granule += 960
                out.write(page(0x00, granule, serial, seq++, byteArrayOf(SILENCE.size.toByte()), SILENCE))
            }
        }
        val total = parsed.size
        parsed.forEachIndexed { k, pages ->
            // The cue opens on the pause, not on the first word.
            starts += ((granule - preSkip).coerceAtLeast(0)) / 48
            silence(if (k == 0) leadMs else gapMs)
            val audio = pages.dropWhile { it.granule == 0L }
            val last = pages.lastOrNull { it.granule > 0 }?.granule ?: 0L
            val offset = granule
            audio.forEachIndexed { i, page ->
                var type = page.headerType and 0x01 // keep only "continued packet"
                if (k == total - 1 && i == audio.size - 1) type = type or 0x04 // EOS
                val g = if (page.granule == -1L) -1L else page.granule + offset
                out.write(page(type, g, serial, seq++, page.segments, page.body))
            }
            granule = offset + last
            ends += ((granule - preSkip).coerceAtLeast(0)) / 48
        }
        return Result(out.toByteArray(), starts, ends)
    }

    /**
     * 20 ms of silence as one Opus packet, in the SAME configuration 音声's files use (TOC 0x68:
     * hybrid, super-wideband, 20 ms, mono), so the decoder never switches mode at a join. Encoded
     * once with libopus from digital silence at 24 kHz, 32 kbps — the steady-state packet, which
     * repeats byte for byte. (ffmpeg -f lavfi -i anullsrc=r=24000:cl=mono -c:a libopus -b:a 32k
     * -frame_duration 20 -application voip, 2026-10-01.)
     */
    internal val SILENCE: ByteArray =
        "6807c979c8c957c0a21223fa59c072d14085dfe815f0212b44ab421a555a5540"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun page(type: Int, granule: Long, serial: Int, seq: Int, segs: ByteArray, body: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(27 + segs.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("OggS".toByteArray(Charsets.US_ASCII))
        b.put(0)
        b.put(type.toByte())
        b.putLong(granule)
        b.putInt(serial)
        b.putInt(seq)
        b.putInt(0) // CRC, filled below
        b.put(segs.size.toByte())
        b.put(segs)
        b.put(body)
        val bytes = b.array()
        val crc = crc(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        return bytes
    }

    private val TABLE = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Ogg's CRC-32: polynomial 0x04C11DB7, no reflection, initial and final value zero. */
    fun crc(bytes: ByteArray): Int {
        var c = 0
        for (x in bytes) c = (c shl 8) xor TABLE[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
        return c
    }
}
