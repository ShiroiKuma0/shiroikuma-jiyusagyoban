package com.opentasker.core.gengoshima

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three real files 白い熊 音声 rendered on the phone (the 2026-09-30 spike), joined into one stream.
 * ffprobe on the joined file, checked by hand the same day: one Opus stream, 10.50 s (= 1.78 + 4.02 + 4.70), decodes clean.
 */
class OggConcatTest {

    private fun res(n: String) = javaClass.classLoader!!.getResourceAsStream("gengoshima/$n")!!.readBytes()
    private val files = listOf(res("001.ogg"), res("002.ogg"), res("003.ogg"))

    @Test
    fun theOriginalPagesCarryTheCrcThisComputes() {
        val f = files[0]
        val stored = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN).getInt(22)
        val page = OggConcat.parse(f).first()
        val len = 27 + page.segments.size + page.body.size
        val zeroed = f.copyOfRange(0, len).also { for (i in 22..25) it[i] = 0 }
        assertEquals(stored, OggConcat.crc(zeroed))
    }

    @Test
    fun theJoinIsOneStreamWithTimeRunningOn() {
        val r = OggConcat.join(files)
        val pages = OggConcat.parse(r.bytes)
        assertEquals(1, pages.map { it.serial }.distinct().size)
        assertEquals(2, pages.first().headerType and 0x02)
        assertTrue(pages.dropLast(1).none { it.headerType and 0x04 != 0 })
        assertEquals(4, pages.last().headerType and 0x04)
        assertTrue(pages.drop(1).none { it.headerType and 0x02 != 0 })
        // Only the first file's two header packets survive.
        assertEquals(1, pages.count { String(it.body, 0, minOf(8, it.body.size), Charsets.US_ASCII) == "OpusHead" })
        val g = pages.map { it.granule }.filter { it > 0 }
        assertTrue("granules never go back", g.zipWithNext().all { (a, b) -> b >= a })
        val lasts = files.map { OggConcat.parse(it).last { p -> p.granule > 0 }.granule }
        assertEquals(lasts.sum(), g.last())
        // Each file starts where the one before it ended, and the cues cover the whole.
        assertEquals(0L, r.startsMs[0])
        assertEquals(r.endsMs[0], r.startsMs[1])
        assertEquals(r.endsMs[1], r.startsMs[2])
        File(System.getProperty("java.io.tmpdir"), "gengoshima-joined.ogg").writeBytes(r.bytes)
    }

    @Test
    fun theSubtitlesAreOneCuePerSentenceJapaneseThenEnglish() {
        val now = 0L
        fun s(pos: Int, ja: String, en: String) = com.opentasker.core.storage.GengoshimaSentenceEntity(
            islandId = 1, position = pos, en = en, ja = ja, createdAt = now, updatedAt = now,
        )
        val srt = IslandExport.srt(listOf(s(1, "私は白い熊です。", "I am 白い熊."), s(2, "", "Not yet.")), listOf(0L, 1780L), listOf(1780L, 3_661_005L))
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,780\n私は白い熊です。\nI am 白い熊.\n\n" +
                "2\n00:00:01,780 --> 01:01:01,005\nNot yet.\n\n",
            srt,
        )
    }

    /**
     * 0.5 s before the first sentence and between each, and each cue opens ON its pause: cue 1 at 0,
     * every later cue where the sentence before it ended — contiguous, each one pause + sentence.
     */
    @Test
    fun pausesAreInsertedAndEachCueStartsWithItsPause() {
        val plain = OggConcat.join(files)
        val paused = OggConcat.join(files, leadMs = 500, gapMs = 500)
        assertEquals(0L, paused.startsMs[0])
        assertEquals(paused.endsMs[0], paused.startsMs[1])
        assertEquals(paused.endsMs[1], paused.startsMs[2])
        // Each cue is its sentence plus 500 ms of pause (within flooring and cue 1's pre-skip clamp).
        for (k in 0..2) {
            val d = (paused.endsMs[k] - paused.startsMs[k]) - (plain.endsMs[k] - plain.startsMs[k]) - 500
            assertTrue("cue ${k + 1} off by $d ms", kotlin.math.abs(d) <= 5)
        }
        val g = OggConcat.parse(paused.bytes).map { it.granule }.filter { it > 0 }
        assertTrue(g.zipWithNext().all { (a, b) -> b >= a })
    }
}
