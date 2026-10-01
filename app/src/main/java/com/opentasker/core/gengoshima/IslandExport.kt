package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import java.io.File

/**
 * 「000 島全体」: each island as ONE audio file with ONE subtitle file beside it, for 白い熊の辞書.
 *
 * 辞書 has a strong study player — tap any word of a subtitle for its pop-up dictionary and Anki —
 * but it plays one file at a time and has no playlist. So each island is joined into a single
 * `.ogg` ([OggConcat], no re-encoding) and given an `.srt` whose cue n is sentence n, the Japanese
 * on the first line and the English on the second, timed to the millisecond from the audio itself.
 * 辞書 picks the subtitle up by its name, which is why both share the island file name.
 *
 * Rebuilt on every generation and every 整理, but a file is only REPLACED when its content changed,
 * so an unchanged island keeps its date and nothing re-syncs it.
 */
object IslandExport {

    data class Built(val islands: Int, val rewritten: Int)

    fun oggFile(island: GengoshimaIslandEntity, s: GengoshimaSettings) =
        File(AudioTree.islandDir(island, s), "${s.islandFileName}.ogg")

    fun srtFile(island: GengoshimaIslandEntity, s: GengoshimaSettings) =
        File(AudioTree.islandDir(island, s), "${s.islandFileName}.srt")

    fun buildAll(
        islands: List<GengoshimaIslandEntity>,
        sentences: List<GengoshimaSentenceEntity>,
        s: GengoshimaSettings,
        onIsland: (String) -> Unit = {},
    ): Built {
        var built = 0
        var rewritten = 0
        for (island in islands) {
            val ready = sentences
                .filter { it.islandId == island.id && it.state == GengoshimaSentenceEntity.STATE_READY }
                .sortedBy { it.position }
                .filter { File(it.audioPath).isFile }
            val ogg = oggFile(island, s)
            val srt = srtFile(island, s)
            if (ready.isEmpty()) {
                ogg.delete()
                srt.delete()
                continue
            }
            onIsland(island.nameJa.ifBlank { island.nameEn })
            val pauseMs = (s.islandPause * 1000).toLong()
            val joined = OggConcat.join(ready.map { File(it.audioPath).readBytes() }, leadMs = pauseMs, gapMs = pauseMs)
            val subtitles = srt(ready, joined.startsMs, joined.endsMs)
            if (replaceIfChanged(ogg, joined.bytes)) rewritten++
            replaceIfChanged(srt, subtitles.toByteArray())
            built++
        }
        return Built(built, rewritten)
    }

    /** One cue per sentence: its number, its span, the Japanese, then the English. */
    internal fun srt(sentences: List<GengoshimaSentenceEntity>, starts: List<Long>, ends: List<Long>): String = buildString {
        sentences.forEachIndexed { k, sentence ->
            append(k + 1).append('\n')
            append(time(starts[k])).append(" --> ").append(time(ends[k])).append('\n')
            append(sentence.ja.ifBlank { sentence.en }).append('\n')
            if (sentence.ja.isNotBlank()) append(sentence.en).append('\n')
            append('\n')
        }
    }

    internal fun time(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val sec = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, sec, ms % 1000)
    }

    /** Write through a `.part` and rename, and only when the bytes differ. True when it wrote. */
    private fun replaceIfChanged(target: File, bytes: ByteArray): Boolean {
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) return false
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.writeBytes(bytes)
        if (target.exists()) target.delete()
        return part.renameTo(target)
    }
}
