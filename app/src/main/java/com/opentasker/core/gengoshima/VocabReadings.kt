package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaVocabEntity

/**
 * The readings 白い熊 handed in with their terms are the readings — in the word split too.
 *
 * A split (Claude's) is free to cut 所管先 into 所管 + 先, or to read a word another way than the
 * import said. Wherever a 語彙 term stands in a sentence as written, [enforce] makes it ONE word
 * again, with the imported reading and the term as its dictionary form (白い熊, 2026-11-16: "no
 * splits of imported phrases with alternate readings"). An inflected form — 食べました for 食べる —
 * cannot take the reading letter for letter; there the word's stem must read as the term's stem
 * does, and is corrected when it does not.
 *
 * A term standing inside a longer word of the split (会議 in 会議室, as one word) is left alone:
 * that is another word.
 */
object VocabReadings {

    data class Known(val term: String, val reading: String, val meaning: String = "")

    /** The terms to hold a sentence of [islandId] to: its own island's first, then every other. */
    fun known(vocab: List<GengoshimaVocabEntity>, islandId: Long): List<Known> =
        vocab.sortedBy { if (it.islandId == islandId) 0 else 1 }
            .distinctBy { it.term }
            .filter { it.term.isNotBlank() }
            .map { Known(it.term.trim(), it.reading.trim(), it.meaning) }

    private fun kana(s: String) = s.isNotEmpty() && s.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it == 'ー' }

    private fun reading(k: Known) = WordEdit.katakana(k.reading.ifBlank { if (kana(k.term)) k.term else "" })

    fun enforce(ja: String, tokens: List<Translator.Token>, known: List<Known>): List<Translator.Token> {
        if (tokens.isEmpty() || known.isEmpty() || tokens.joinToString("") { it.surface } != ja) return tokens
        var out = tokens
        // Longest first, so 所管先与信枠 is settled before 所管 can claim its first half.
        val settled = HashSet<IntRange>()
        for (k in known.sortedByDescending { it.term.length }) {
            val want = reading(k)
            if (want.isEmpty()) continue
            var from = 0
            while (true) {
                val at = ja.indexOf(k.term, from)
                if (at < 0) break
                val end = at + k.term.length
                from = end
                if (settled.any { it.first < end && at <= it.last }) continue
                // The words of the split that cover exactly [at, end), if its cuts fall there.
                var pos = 0
                var first = -1
                var last = -1
                for ((i, t) in out.withIndex()) {
                    if (pos == at) first = i
                    pos += t.surface.length
                    if (pos == end && first >= 0) { last = i; break }
                    if (pos > end) break
                }
                if (first < 0 || last < 0) continue
                val old = out.subList(first, last + 1)
                val one = Translator.Token(
                    surface = k.term,
                    base = k.term,
                    reading = want,
                    gloss = old.singleOrNull()?.gloss?.takeIf { it.isNotBlank() } ?: VocabSearch.senses(k.meaning).firstOrNull().orEmpty(),
                    // A reading 白い熊 set by hand on this very word stays; one on a fragment goes with the fragment.
                    reading_override = old.singleOrNull()?.reading_override,
                )
                out = out.subList(0, first) + one + out.subList(last + 1, out.size)
                settled += at until end
            }
        }
        // Inflected forms: the same dictionary form, another surface — the stem keeps the term's reading.
        val byTerm = known.associateBy { it.term }
        return out.map { t ->
            val k = byTerm[t.base]?.takeIf { t.surface != it.term && it.reading.isNotBlank() } ?: return@map t
            val stem = t.surface.commonPrefixWith(k.term)
            val tail = k.term.removePrefix(stem)
            val head = t.surface.removePrefix(stem)
            val whole = WordEdit.katakana(k.reading)
            // Only where it can be worked out exactly: both endings are kana, and the term's reading ends in its own.
            if (stem.isEmpty() || !(tail.isEmpty() || kana(tail)) || !(head.isEmpty() || kana(head))) return@map t
            val tailKana = WordEdit.katakana(tail)
            if (!whole.endsWith(tailKana)) return@map t
            val right = whole.removeSuffix(tailKana) + WordEdit.katakana(head)
            if (WordEdit.katakana(t.reading) == right) t else t.copy(reading = right)
        }
    }

    /** The lines that tell the split which words are 白い熊's, for the terms that occur in [sentences]. */
    fun promptLines(known: List<Known>, sentences: List<String>): String {
        // The term as written, or — for a word that inflects — all of it but its last letter.
        val here = known.filter { it.reading.isNotBlank() }.filter { k ->
            val stem = if (k.term.length > 1) k.term.dropLast(1) else k.term
            sentences.any { it.contains(stem) }
        }
        if (here.isEmpty()) return ""
        return buildString {
            append("These words are fixed vocabulary. Wherever one of them occurs, keep it as ONE word, never cut into parts, ")
            append("with exactly this reading and with itself as the dictionary form; an inflected form keeps the same stem reading:\n")
            here.forEach { append(it.term).append(" = ").append(WordEdit.katakana(it.reading)).append('\n') }
            append('\n')
        }
    }
}
