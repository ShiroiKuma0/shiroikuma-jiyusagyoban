package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaDao
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.core.storage.GengoshimaTombstoneEntity
import com.opentasker.core.storage.GengoshimaVocabEntity

/**
 * 言語島 取込 — the pasted text another chat hands over: terms with their reading and translation,
 * their example sentences, and stand-alone sentences, filed under islands. Format, in full:
 * `docs/gengoshima-import-format.md`.
 *
 * ```
 * 言語島 取込 1
 * 島: 仕事 | Work | 丁寧
 * 語: 会議【かいぎ】 = meeting; conference
 *   例: 会議は三時からです。 = The meeting starts at three.
 *   注: formal
 * 文: 明日は休みを取ります。 = I am taking tomorrow off.
 * ```
 *
 * Three stages, the first two pure: [parse] the text, [plan] it against what the database holds
 * (what is new, what is already there), then [apply] the plan. The Japanese is 白い熊's own and is
 * kept verbatim: every sentence goes in as `translated` + `jaEdited` with no word split yet, which
 * the generation run voices at once in both languages and only then splits into words — it is
 * never translated again.
 */
object VocabImport {

    const val HEADER = "言語島 取込"

    data class Line(val ja: String, val en: String, val line: Int)

    /** One entry of an island block, in the order written. */
    sealed interface Item
    data class Term(
        val term: String,
        val reading: String,
        val meaning: String,
        val note: String,
        val examples: List<Line>,
        val line: Int,
    ) : Item
    data class Sentence(val text: Line) : Item

    data class Block(val nameJa: String, val nameEn: String, val register: String, val items: List<Item>, val line: Int) {
        val terms get() = items.filterIsInstance<Term>()
        /** Every sentence the block adds to its island, in file order: stand-alone ones and examples. */
        val sentenceCount get() = items.sumOf { if (it is Term) it.examples.size else 1 }
        val label get() = listOf(nameJa, nameEn).filter { it.isNotBlank() }.distinct().joinToString(" — ")
    }

    data class Problem(val line: Int, val message: String)

    data class Parsed(val blocks: List<Block>, val problems: List<Problem>) {
        val empty get() = blocks.all { it.items.isEmpty() }
    }

    /** True when [text] opens with the header — the clipboard check of the import dialog. */
    fun looksLikeImport(text: String): Boolean =
        text.lineSequence().map { clean(it) }.firstOrNull { it.isNotEmpty() }?.startsWith(HEADER) == true

    private val KEY = Regex("^(島|island|語|term|word|例|ex|example|注|note|文|sentence)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val READING = Regex("^(.+?)\\s*[【\\[（(]\\s*([^】\\]）)]+?)\\s*[】\\]）)]$")
    private val BULLET = Regex("^(?:[-*・•]\\s+|\\d+[.)]\\s+)")

    /** A line as written, minus indentation, list bullets and Markdown emphasis around the keyword. */
    private fun clean(raw: String): String =
        raw.trim().replace(BULLET, "").replace("**", "").trim()

    /** Japanese and English are split at the first ` = ` (or a full-width `＝`, a tab, a bare `=`). */
    private fun pair(rest: String): Pair<String, String>? {
        for (sep in listOf(" = ", " ＝ ", "\t", "＝", "=")) {
            val at = rest.indexOf(sep)
            if (at > 0) {
                val ja = rest.substring(0, at).trim()
                val en = rest.substring(at + sep.length).trim()
                return if (ja.isEmpty() || en.isEmpty()) null else ja to en
            }
        }
        return null
    }

    fun parse(text: String): Parsed {
        val problems = ArrayList<Problem>()
        val blocks = ArrayList<Block>()

        // The block and the term being filled; closed when the next one opens.
        var island: Triple<String, String, String>? = null
        var islandLine = 0
        var items = ArrayList<Item>()
        var term: Term? = null
        val examples = ArrayList<Line>()
        var note = ""

        fun closeTerm() {
            term?.let { items += it.copy(examples = examples.toList(), note = note) }
            term = null; examples.clear(); note = ""
        }
        fun closeBlock() {
            closeTerm()
            if (island != null || items.isNotEmpty()) {
                val (ja, en, register) = island ?: Triple("", "", "")
                blocks += Block(ja, en, register, items, islandLine)
            }
            items = ArrayList(); island = null
        }

        text.lines().forEachIndexed { index, raw ->
            val no = index + 1
            val line = clean(raw)
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//") || line.startsWith("```")) return@forEachIndexed
            if (line.startsWith(HEADER)) return@forEachIndexed
            val m = KEY.find(line)
            if (m == null) {
                problems += Problem(no, "読めない行 / not understood: ${line.take(40)}")
                return@forEachIndexed
            }
            val rest = m.groupValues[2].trim()
            when (m.groupValues[1].lowercase()) {
                "島", "island" -> {
                    closeBlock()
                    val parts = rest.split('|', '｜').map { it.trim() }
                    val first = parts.getOrElse(0) { "" }
                    if (first.isEmpty()) {
                        problems += Problem(no, "島の名前がありません / the island has no name")
                    }
                    // One name only: Japanese when it is written in Japanese, otherwise the English one.
                    val second = parts.getOrElse(1) { "" }
                    val japanese = GengoshimaLanguage.islandNameFromTopic(first)
                    island = Triple(
                        japanese,
                        second.ifEmpty { if (japanese.isEmpty()) first else "" },
                        parts.getOrElse(2) { "" },
                    )
                    islandLine = no
                }
                "語", "term", "word" -> {
                    closeTerm()
                    val p = pair(rest)
                    if (p == null) {
                        problems += Problem(no, "訳がありません / no translation: ${rest.take(40)}")
                        return@forEachIndexed
                    }
                    val r = READING.find(p.first)
                    term = Term(
                        term = (r?.groupValues?.get(1) ?: p.first).trim(),
                        reading = r?.groupValues?.get(2)?.trim().orEmpty(),
                        meaning = p.second.split(';', '；').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; "),
                        note = "",
                        examples = emptyList(),
                        line = no,
                    )
                }
                "例", "ex", "example" -> {
                    val p = pair(rest)
                    when {
                        term == null -> problems += Problem(no, "例の前に語がありません / an example with no term above it")
                        p == null -> problems += Problem(no, "訳がありません / no translation: ${rest.take(40)}")
                        else -> examples += Line(p.first, p.second, no)
                    }
                }
                "注", "note" -> {
                    if (term == null) problems += Problem(no, "注の前に語がありません / a note with no term above it")
                    else note = listOf(note, rest).filter { it.isNotEmpty() }.joinToString("\n")
                }
                "文", "sentence" -> {
                    closeTerm()
                    val p = pair(rest)
                    if (p == null) problems += Problem(no, "訳がありません / no translation: ${rest.take(40)}")
                    else items += Sentence(Line(p.first, p.second, no))
                }
            }
        }
        closeBlock()
        return Parsed(blocks, problems)
    }

    // ── plan ────────────────────────────────────────────────────────────────────────────────────

    /** What applying one block will do. [islandId] null = the island is created. */
    data class BlockPlan(
        val block: Block,
        val islandId: Long?,
        val islandLabel: String,
        val newTerms: Int,
        val knownTerms: Int,
        val newSentences: Int,
        val knownSentences: Int,
        /**
         * The lines of the text that are already entered — a term, an example or a sentence — by
         * line number, each with the island it is in. Nothing on these lines is added again.
         */
        val known: Map<Int, String> = emptyMap(),
    ) {
        /** Without an island — no `島:` line and no target chosen — the block cannot be filed. */
        val needsIsland get() = islandId == null && block.label.isEmpty()
    }

    data class Plan(val blocks: List<BlockPlan>) {
        val newIslands get() = blocks.filter { it.islandId == null && !it.needsIsland }.map { it.block.label }.distinct().size
        val newTerms get() = blocks.sumOf { it.newTerms }
        val knownTerms get() = blocks.sumOf { it.knownTerms }
        val newSentences get() = blocks.sumOf { it.newSentences }
        val knownSentences get() = blocks.sumOf { it.knownSentences }
        val ready get() = blocks.isNotEmpty() && blocks.none { it.needsIsland }
    }

    /** Sentences are the same when their Japanese is, spaces and the final 。 aside. */
    fun sentenceKey(ja: String): String = ja.filterNot { it.isWhitespace() }.trimEnd('。', '．', '.')

    fun termKey(term: String, reading: String): String = term.trim() + "\u0000" + reading.trim()

    private fun name(s: String) = s.trim().lowercase()

    private fun label(i: GengoshimaIslandEntity) = i.nameJa.ifBlank { i.nameEn }

    /** The island a block names, by either of its names against either of an island's. */
    fun islandFor(block: Block, islands: List<GengoshimaIslandEntity>): GengoshimaIslandEntity? {
        val wanted = setOf(name(block.nameJa), name(block.nameEn)) - ""
        if (wanted.isEmpty()) return null
        return islands.firstOrNull { name(it.nameJa) in wanted || name(it.nameEn) in wanted }
    }

    /**
     * [targets] overrides where a block goes (block index → island id) — the chip of the preview.
     * A block without a `島:` line needs one.
     *
     * **A term or a sentence already entered in ANY island is not entered again** (白い熊,
     * 2026-11-16) — nor is one written twice in the same text. A term is the same by spelling and
     * reading, a sentence by its Japanese ([sentenceKey]).
     */
    fun plan(
        parsed: Parsed,
        islands: List<GengoshimaIslandEntity>,
        vocab: List<GengoshimaVocabEntity>,
        sentences: List<GengoshimaSentenceEntity>,
        targets: Map<Int, Long> = emptyMap(),
    ): Plan {
        val byId = islands.associateBy { it.id }
        // What is entered, and where: the database first, then every block of this text as it is read.
        val terms = HashMap<String, String>()
        vocab.forEach { v -> terms.putIfAbsent(termKey(v.term, v.reading), byId[v.islandId]?.let(::label).orEmpty()) }
        val said = HashMap<String, String>()
        sentences.forEach { s -> if (s.ja.isNotBlank()) said.putIfAbsent(sentenceKey(s.ja), byId[s.islandId]?.let(::label).orEmpty()) }
        return Plan(
            parsed.blocks.mapIndexed { index, block ->
                val island = targets[index]?.let { byId[it] } ?: islandFor(block, islands)
                val here = island?.let(::label) ?: block.label
                val known = HashMap<Int, String>()
                var newTerms = 0; var knownTerms = 0; var newSentences = 0; var knownSentences = 0
                fun count(line: Line) {
                    val where = said.putIfAbsent(sentenceKey(line.ja), here)
                    if (where == null) newSentences++ else { knownSentences++; known[line.line] = where }
                }
                for (item in block.items) when (item) {
                    is Term -> {
                        val where = terms.putIfAbsent(termKey(item.term, item.reading), here)
                        if (where == null) newTerms++ else { knownTerms++; known[item.line] = where }
                        item.examples.forEach(::count)
                    }
                    is Sentence -> count(item.text)
                }
                BlockPlan(
                    block, island?.id,
                    island?.let { i -> listOf(i.nameJa, i.nameEn).filter { it.isNotBlank() }.distinct().joinToString(" — ") } ?: block.label,
                    newTerms, knownTerms, newSentences, knownSentences, known,
                )
            },
        )
    }

    // ── apply ───────────────────────────────────────────────────────────────────────────────────

    /** The rows one import created — what 「直前の取り込みを取り消す」 removes again. */
    @kotlinx.serialization.Serializable
    data class Receipt(
        val at: Long = 0,
        val islands: List<Long> = emptyList(),
        val vocab: List<Long> = emptyList(),
        val sentences: List<Long> = emptyList(),
    ) {
        val empty get() = islands.isEmpty() && vocab.isEmpty() && sentences.isEmpty()
        val summary get() = "島 +${islands.size} · 語 +${vocab.size} · 文 +${sentences.size}"
    }

    /** File the plan. Call inside one transaction. */
    suspend fun apply(dao: GengoshimaDao, plan: Plan, now: Long = System.currentTimeMillis()): Receipt {
        require(plan.ready) { "every block needs an island first" }
        val newIslands = ArrayList<Long>()
        val newVocab = ArrayList<Long>()
        val newSentences = ArrayList<Long>()
        val created = HashMap<String, Long>()
        // Every term and sentence there is, in any island: nothing already entered is entered again.
        val terms = HashMap<String, GengoshimaVocabEntity>()
        dao.allVocab().forEach { terms.putIfAbsent(termKey(it.term, it.reading), it) }
        val existing = HashMap<String, GengoshimaSentenceEntity>()
        dao.allSentences().forEach { if (it.ja.isNotBlank()) existing.putIfAbsent(sentenceKey(it.ja), it) }
        for (bp in plan.blocks) {
            val block = bp.block
            val islandId = bp.islandId ?: created.getOrPut(name(block.label)) {
                dao.insertIsland(
                    GengoshimaIslandEntity(
                        position = dao.lastIslandPosition() + 1,
                        nameEn = block.nameEn.ifBlank { block.nameJa },
                        nameJa = block.nameJa,
                        register = block.register,
                        createdAt = now,
                    ),
                ).also { newIslands += it }
            }

            suspend fun sentence(line: Line, vocabId: Long?) {
                val key = sentenceKey(line.ja)
                val same = existing[key]
                if (same != null) {
                    // Already entered, here or in another island: only tie it to its term, if it had none.
                    if (vocabId != null && same.vocabId == null) {
                        dao.updateSentence(same.copy(vocabId = vocabId).also { existing[key] = it })
                    }
                    return
                }
                val row = GengoshimaSentenceEntity(
                    islandId = islandId,
                    position = dao.lastSentencePosition(islandId) + 1,
                    en = line.en,
                    ja = line.ja,
                    // Translated already, by whoever wrote the text: voiced as it stands, the words after.
                    state = GengoshimaSentenceEntity.STATE_TRANSLATED,
                    jaEdited = true,
                    vocabId = vocabId,
                    createdAt = now,
                    updatedAt = now,
                )
                val id = dao.insertSentence(row)
                existing[key] = row.copy(id = id)
                newSentences += id
            }

            for (item in block.items) when (item) {
                is Sentence -> sentence(item.text, null)
                is Term -> {
                    val key = termKey(item.term, item.reading)
                    val had = terms[key]
                    val vocabId = if (had != null) {
                        // Handed in again: the translation and the note follow the newer text.
                        val note = item.note.ifEmpty { had.note }
                        if (had.meaning != item.meaning || had.note != note) {
                            dao.updateVocab(had.copy(meaning = item.meaning, note = note, updatedAt = now).also { terms[key] = it })
                        }
                        had.id
                    } else {
                        val row = GengoshimaVocabEntity(
                            islandId = islandId,
                            position = dao.lastVocabPosition(islandId) + 1,
                            term = item.term,
                            reading = item.reading,
                            meaning = item.meaning,
                            note = item.note,
                            createdAt = now,
                            updatedAt = now,
                        )
                        dao.insertVocab(row).also { terms[key] = row.copy(id = it); newVocab += it }
                    }
                    item.examples.forEach { sentence(it, vocabId) }
                }
            }
        }
        return Receipt(now, newIslands, newVocab, newSentences)
    }

    /**
     * Take one import back: its sentences (with their recordings, and a tombstone so 暗記 drops the
     * note), its terms, and the islands it created when nothing else has been put into them since.
     * Call inside one transaction; returns what was removed.
     */
    suspend fun undo(dao: GengoshimaDao, receipt: Receipt, now: Long = System.currentTimeMillis()): Receipt {
        val goneSentences = ArrayList<Long>()
        val touched = HashSet<Long>()
        for (id in receipt.sentences) {
            val s = dao.sentence(id) ?: continue
            dao.deleteSentence(id)
            dao.insertTombstone(GengoshimaTombstoneEntity(s.uuid, "sentence", now))
            if (s.audioPath.isNotEmpty()) java.io.File(s.audioPath).delete()
            if (s.enAudioPath.isNotEmpty()) java.io.File(s.enAudioPath).delete()
            touched += s.islandId
            goneSentences += id
        }
        val goneVocab = ArrayList<Long>()
        for (id in receipt.vocab) {
            val v = dao.vocab(id) ?: continue
            dao.unlinkExamples(id)
            dao.deleteVocabRow(id)
            touched += v.islandId
            goneVocab += id
        }
        val goneIslands = ArrayList<Long>()
        for (id in receipt.islands) {
            val island = dao.island(id) ?: continue
            if (dao.sentences(id).isNotEmpty() || dao.vocabOf(id).isNotEmpty()) continue
            dao.insertTombstone(GengoshimaTombstoneEntity(island.uuid, "island", now))
            dao.deleteIsland(id)
            goneIslands += id
        }
        for (islandId in touched - goneIslands.toSet()) {
            dao.sentences(islandId).forEachIndexed { k, s -> if (s.position != k + 1) dao.updateSentence(s.copy(position = k + 1)) }
            dao.vocabOf(islandId).forEachIndexed { k, v -> if (v.position != k + 1) dao.updateVocab(v.copy(position = k + 1)) }
        }
        if (goneIslands.isNotEmpty()) {
            dao.islands().forEachIndexed { k, i -> if (i.position != k + 1) dao.updateIsland(i.copy(position = k + 1)) }
        }
        return Receipt(now, goneIslands, goneVocab, goneSentences)
    }

    // ── the round trip ──────────────────────────────────────────────────────────────────────────

    /** Islands back out in the import format itself — what 書き出す writes, and what re-imports. */
    fun export(
        islands: List<GengoshimaIslandEntity>,
        vocab: List<GengoshimaVocabEntity>,
        sentences: List<GengoshimaSentenceEntity>,
    ): String = buildString {
        append(HEADER).append(" 1\n")
        for (island in islands) {
            val terms = vocab.filter { it.islandId == island.id }.sortedBy { it.position }
            val own = sentences.filter { it.islandId == island.id && it.ja.isNotBlank() }.sortedBy { it.position }
            if (terms.isEmpty() && own.isEmpty()) continue
            append("\n島: ").append(listOf(island.nameJa, island.nameEn, island.register).joinToString(" | ").trimEnd(' ', '|')).append('\n')
            for (t in terms) {
                append("\n語: ").append(t.term)
                if (t.reading.isNotEmpty()) append('【').append(t.reading).append('】')
                append(" = ").append(t.meaning).append('\n')
                own.filter { it.vocabId == t.id }.forEach { append("  例: ").append(it.ja).append(" = ").append(it.en).append('\n') }
                t.note.lines().filter { it.isNotBlank() }.forEach { append("  注: ").append(it).append('\n') }
            }
            val ids = terms.map { it.id }.toSet()
            val alone = own.filter { it.vocabId == null || it.vocabId !in ids }
            if (alone.isNotEmpty()) append('\n')
            alone.forEach { append("文: ").append(it.ja).append(" = ").append(it.en).append('\n') }
        }
    }
}
