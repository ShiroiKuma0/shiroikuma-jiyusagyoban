package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import java.io.File

/**
 * Where 言語島's files go, and how the tree is kept equal to the database.
 *
 * The layout is 白い熊's to set (`%Gengoshima_Dir`, the two name patterns, the whole-island file name,
 * the number width, the truncation length); the defaults make a tree a file manager reads at a glance:
 *
 *     [227][727] 言語島/
 *       001 自己紹介 — Self-introduction/
 *         001 私は白い熊です.ogg
 *         002 毎朝散歩します.ogg
 *
 * **Names are a view.** The database holds each sentence's current path, so 整理 ([reconcile]) moves
 * files to wherever the patterns now say, deletes what nothing references, and never re-voices
 * anything. These are generated study files, not builds, so tidying them is right.
 */
object AudioTree {

    /** The characters no file name may hold, each swapped for its full-width twin so it still reads. */
    private val FORBIDDEN = mapOf(
        '/' to '／', '\\' to '＼', ':' to '：', '*' to '＊', '?' to '？',
        '"' to '”', '<' to '＜', '>' to '＞', '|' to '｜',
    )

    /** Sentence-final marks dropped from a file name: `私は白い熊です。` names `私は白い熊です.ogg`. */
    private val TRAILING = Regex("[。．.！!？?、,\\s]+$")

    /** Well under ext4's 255-byte limit, leaving room for `.ogg.part` and a temp prefix. */
    const val MAX_NAME_BYTES = 150

    fun number(n: Int, width: Int): String = n.toString().padStart(width, '0')

    /** One name component: safe characters, one line, no trailing punctuation, at most [maxChars]. */
    fun field(text: String, maxChars: Int): String {
        val clean = buildString {
            for (c in text.trim()) {
                when {
                    c in FORBIDDEN -> append(FORBIDDEN.getValue(c))
                    c.isISOControl() || c == '\n' || c == '\r' -> append(' ')
                    else -> append(c)
                }
            }
        }.replace(Regex("\\s+"), " ").replace(TRAILING, "")
        val cps = clean.codePoints().toArray()
        return if (cps.size <= maxChars) clean else String(cps, 0, maxChars)
    }

    /**
     * Fill a pattern and tidy what an empty field leaves behind — `{no} {name_ja} — {name_en}` on an
     * island not yet named in Japanese gives `001 Self-introduction`, not `001  — Self-introduction`.
     */
    internal fun fill(pattern: String, values: Map<String, String>): String {
        var out = pattern
        values.forEach { (k, v) -> out = out.replace("{$k}", v) }
        out = out.replace(Regex("\\s+"), " ").trim()
        // A separator with nothing on one side of it.
        out = out.replace(Regex("(\\s*[—–-]\\s*){2,}"), " — ")
        out = out.replace(Regex("^[\\s—–-]+|[\\s—–-]+$"), "")
        out = out.replace(Regex("^(\\d+)\\s*[—–-]\\s*"), "$1 ")
        out = out.trimEnd('.', ' ')
        return capBytes(out)
    }

    /** Cut to [MAX_NAME_BYTES] of UTF-8 without splitting a character. */
    internal fun capBytes(name: String): String {
        if (name.toByteArray().size <= MAX_NAME_BYTES) return name
        val cps = name.codePoints().toArray()
        var n = cps.size
        while (n > 1 && String(cps, 0, n).toByteArray().size > MAX_NAME_BYTES) n--
        return String(cps, 0, n).trimEnd()
    }

    fun islandDirName(island: GengoshimaIslandEntity, s: GengoshimaSettings): String {
        val ja = field(island.nameJa, s.nameMaxChars)
        // An island named the same in both (one taken from 暗記's decks) is not named twice.
        val en = field(island.nameEn, s.nameMaxChars).takeIf { it != ja }.orEmpty()
        return fill(
            s.islandDirPattern,
            mapOf(
                "no" to number(island.position, s.numberWidth),
                "name_ja" to ja.ifEmpty { en },
                "name_en" to if (ja.isEmpty()) "" else en,
            ),
        ).ifEmpty { number(island.position, s.numberWidth) }
    }

    fun sentenceFileName(sentence: GengoshimaSentenceEntity, s: GengoshimaSettings): String {
        val ja = field(sentence.ja, s.nameMaxChars)
        val en = field(sentence.en, s.nameMaxChars)
        val base = fill(
            s.sentenceFilePattern,
            mapOf(
                "no" to number(sentence.position, s.numberWidth),
                "ja" to ja.ifEmpty { en },
                "en" to en,
            ),
        ).ifEmpty { number(sentence.position, s.numberWidth) }
        return capBytes(base) + ".ogg"
    }

    /** The English reading's file — the same fields, its own pattern (default `{no} {ja} [en]`). */
    fun sentenceEnFileName(sentence: GengoshimaSentenceEntity, s: GengoshimaSettings): String {
        val ja = field(sentence.ja, s.nameMaxChars)
        val en = field(sentence.en, s.nameMaxChars)
        val base = fill(
            s.sentenceFileEnPattern,
            mapOf("no" to number(sentence.position, s.numberWidth), "ja" to ja.ifEmpty { en }, "en" to en),
        ).ifEmpty { number(sentence.position, s.numberWidth) + " en" }
        return capBytes(base) + ".ogg"
    }

    fun sentenceEnFile(island: GengoshimaIslandEntity, sentence: GengoshimaSentenceEntity, s: GengoshimaSettings): File =
        File(islandDir(island, s), sentenceEnFileName(sentence, s))

    fun islandDir(island: GengoshimaIslandEntity, s: GengoshimaSettings): File =
        File(s.dir, islandDirName(island, s))

    fun sentenceFile(island: GengoshimaIslandEntity, sentence: GengoshimaSentenceEntity, s: GengoshimaSettings): File =
        File(islandDir(island, s), sentenceFileName(sentence, s))

    /** What [reconcile] did, for the run log and the notification. */
    data class Tidy(val moved: Int, val deleted: Int, val lost: Int)

    /**
     * 整理: make the tree equal to the database.
     *
     * 1. Each island's directory is renamed to its current name if it has moved (a reorder, a new
     *    Japanese name, a changed pattern) — one rename, not a copy.
     * 2. Each voiced sentence's file is moved to its current name. Moves go through a temporary
     *    name first, so swapping 001 and 002 cannot overwrite either.
     * 3. A file nothing references (`*.ogg` / `*.part` in an island directory, bar the whole-island
     *    file) is deleted, and so is an island directory no island owns — but only when it holds
     *    nothing except such files, so a directory 白い熊 put there by hand is never touched.
     * 4. A sentence whose file has gone missing loses its path, so the next generation re-voices it.
     *
     * [save] is called for every row whose path or directory changed.
     */
    suspend fun reconcile(
        islands: List<GengoshimaIslandEntity>,
        sentences: List<GengoshimaSentenceEntity>,
        s: GengoshimaSettings,
        saveIsland: suspend (GengoshimaIslandEntity) -> Unit,
        saveSentence: suspend (GengoshimaSentenceEntity) -> Unit,
    ): Tidy {
        val root = File(s.dir)
        root.mkdirs()
        var moved = 0
        var deleted = 0
        var lost = 0

        // 1. directories
        val dirs = HashMap<Long, File>()
        for (island in islands) {
            val want = islandDir(island, s)
            val had = island.dirName?.let { File(root, it) }
            if (had != null && had.name != want.name && had.isDirectory && !want.exists()) {
                if (had.renameTo(want)) moved++
            }
            want.mkdirs()
            dirs[island.id] = want
            if (island.dirName != want.name) saveIsland(island.copy(dirName = want.name))
        }

        // The old path of a sentence whose island directory was just renamed now lives under the
        // new directory name; follow it there before deciding whether the file is lost.
        fun current(path: String, island: GengoshimaIslandEntity): File {
            val f = File(path)
            if (f.exists()) return f
            val inNewDir = File(dirs[island.id] ?: return f, f.name)
            return inNewDir
        }

        // 2. files, in two phases
        val byIsland = islands.associateBy { it.id }
        // Japanese and English alike: each is a (current path, wanted path) pair on the same row.
        data class Move(val sentenceId: Long, val english: Boolean, val from: File, val to: File)
        val moves = ArrayList<Move>()
        val keep = HashSet<String>()
        val rows = sentences.associateBy { it.id }.toMutableMap()
        for (sentence in sentences) {
            val island = byIsland[sentence.islandId] ?: continue
            for (english in listOf(false, true)) {
                val path = if (english) sentence.enAudioPath else sentence.audioPath
                if (path.isEmpty()) continue
                val from = current(path, island)
                val to = if (english) sentenceEnFile(island, sentence, s) else sentenceFile(island, sentence, s)
                val row = rows.getValue(sentence.id)
                if (!from.exists()) {
                    lost++
                    val cleared = if (english) row.copy(enAudioPath = "", enAudioHash = "")
                    else row.copy(audioPath = "", audioHash = "", state = GengoshimaSentenceEntity.STATE_TRANSLATED)
                    rows[sentence.id] = cleared
                    saveSentence(cleared)
                    continue
                }
                keep += to.absolutePath
                if (from.absolutePath != to.absolutePath) {
                    moves += Move(sentence.id, english, from, to)
                } else if (path != to.absolutePath) {
                    val fixed = if (english) row.copy(enAudioPath = to.absolutePath) else row.copy(audioPath = to.absolutePath)
                    rows[sentence.id] = fixed
                    saveSentence(fixed)
                }
            }
        }
        val staged = moves.map { m ->
            val tmp = File(m.from.parentFile, ".move-${m.sentenceId}${if (m.english) "-en" else ""}.ogg")
            m.from.renameTo(tmp)
            m to tmp
        }
        for ((m, tmp) in staged) {
            m.to.parentFile?.mkdirs()
            if (m.to.exists()) m.to.delete()
            if (tmp.renameTo(m.to)) {
                moved++
                val row = rows.getValue(m.sentenceId)
                val updated = if (m.english) row.copy(enAudioPath = m.to.absolutePath) else row.copy(audioPath = m.to.absolutePath)
                rows[m.sentenceId] = updated
                saveSentence(updated)
            }
        }

        // 3. strays
        val wholeIsland = setOf("${s.islandFileName}.ogg", "${s.islandFileName}.srt")
        val owned = dirs.values.map { it.absolutePath }.toSet()
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val files = dir.listFiles().orEmpty()
            val ours = files.all { it.isFile && (it.name.endsWith(".ogg") || it.name.endsWith(".part") || it.name.endsWith(".srt")) }
            if (dir.absolutePath !in owned) {
                if (ours && Regex("^\\d+ ").containsMatchIn(dir.name)) {
                    deleted += files.size
                    dir.deleteRecursively()
                }
                return@forEach
            }
            files.filter { it.isFile && it.name !in wholeIsland }
                .filter { it.name.endsWith(".ogg") || it.name.endsWith(".part") }
                .filter { it.absolutePath !in keep }
                .forEach { if (it.delete()) deleted++ }
        }
        return Tidy(moved, deleted, lost)
    }
}
