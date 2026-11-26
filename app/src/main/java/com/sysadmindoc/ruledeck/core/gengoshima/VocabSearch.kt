package com.sysadmindoc.ruledeck.core.gengoshima

import com.sysadmindoc.ruledeck.core.storage.GengoshimaSentenceEntity
import com.sysadmindoc.ruledeck.core.storage.GengoshimaVocabEntity

/**
 * Finding and ordering 語彙 terms: one query matched against the term, its reading (typed in kana
 * or in romaji), its translation, its note and its example sentences.
 */
object VocabSearch {

    fun hiragana(text: String): String = buildString {
        for (ch in text) append(if (ch in 'ァ'..'ヶ') (ch - 0x60) else ch)
    }

    /** The senses of a translation: `meeting; conference` is two. */
    fun senses(meaning: String): List<String> =
        meaning.split(';', '；').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(meaning.trim()) }

    /** What 五十音 order sorts by: the reading, or the term itself when it is all kana. */
    fun kanaKey(v: GengoshimaVocabEntity): String = hiragana(v.reading.ifBlank { v.term })

    /** The 五十音 rows, each from its first kana up to the first kana of the next. */
    private val ROWS = listOf('ぁ' to "あ", 'か' to "か", 'さ' to "さ", 'た' to "た", 'な' to "な", 'は' to "は", 'ま' to "ま", 'ゃ' to "や", 'ら' to "ら", 'ゎ' to "わ")

    /** What does not start with a kana or a Latin letter — a digit, a bracket, a kanji with no reading. */
    const val OTHER = "#"

    /**
     * The heading a reading is listed under in 五十音 order: its row (あ か さ た な は ま や ら わ, the
     * voiced kana with their plain ones), a capital for a word in Latin letters, [OTHER] for the rest.
     */
    fun kanaGroup(key: String): String {
        val ch = hiragana(key).firstOrNull() ?: return OTHER
        if (ch == 'ゔ') return "あ"
        if (ch in 'ぁ'..'ん') return ROWS.last { ch >= it.first }.second
        return latin(ch)
    }

    /** The heading an English sense is listed under: its first letter, brackets and digits passed over. */
    fun letterGroup(sense: String): String = sense.firstOrNull { it.isLetter() }?.let(::latin) ?: OTHER

    private fun latin(ch: Char): String {
        val base = java.text.Normalizer.normalize(ch.toString(), java.text.Normalizer.Form.NFD).first().uppercaseChar()
        return if (base in 'A'..'Z') base.toString() else OTHER
    }

    /** Where a heading stands: the kana rows in 五十音 order, then A–Z, then [OTHER]. */
    fun groupOrder(group: String): Int {
        val row = ROWS.indexOfFirst { it.second == group }
        return when {
            row >= 0 -> row
            group == OTHER -> 1000
            else -> 100 + (group.first() - 'A')
        }
    }

    /** What an English sense sorts by: its letters and digits only, case ignored, so `(NCV) value` stands under N. */
    fun senseKey(sense: String): String = sense.lowercase().dropWhile { !it.isLetterOrDigit() }

    private val ROMAJI: Map<String, String> = buildMap {
        val rows = listOf(
            "" to "あいうえお", "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの",
            "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご", "z" to "ざじずぜぞ",
            "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ",
        )
        for ((c, kana) in rows) "aiueo".forEachIndexed { i, v -> put(c + v, kana[i].toString()) }
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ"); put("wa", "わ"); put("wo", "を")
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ"); put("ji", "じ")
        val small = listOf("a" to "ゃ", "u" to "ゅ", "o" to "ょ")
        val glides = listOf(
            "ky" to "き", "gy" to "ぎ", "ny" to "に", "hy" to "ひ", "by" to "び", "py" to "ぴ", "my" to "み", "ry" to "り",
            "sh" to "し", "ch" to "ち", "j" to "じ", "sy" to "し", "ty" to "ち", "zy" to "じ", "jy" to "じ",
        )
        for ((c, k) in glides) for ((v, s) in small) put(c + v, k + s)
    }

    /** [text] as hiragana when it is romaji from end to end (Hepburn or 訓令), otherwise null. */
    fun romajiToHiragana(text: String): String? {
        val s = text.lowercase().replace("ā", "aa").replace("ī", "ii").replace("ū", "uu").replace("ē", "ee").replace("ō", "ou")
        if (s.isEmpty() || s.any { it !in 'a'..'z' && it != '-' && it != '\'' }) return null
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '-') { out.append('ー'); i++; continue }
            if (ch == '\'') { i++; continue }
            // A doubled consonant is the small っ; n before a consonant (or at the end) is ん.
            if (i + 1 < s.length && ch == s[i + 1] && ch !in "aiueon") { out.append('っ'); i++; continue }
            val hit = (3 downTo 1).firstNotNullOfOrNull { n ->
                if (i + n <= s.length) ROMAJI[s.substring(i, i + n)]?.let { it to n } else null
            }
            when {
                hit != null -> { out.append(hit.first); i += hit.second }
                ch == 'n' -> { out.append('ん'); i++ }
                // A syllable still being typed ("kaig") matches what is complete before it.
                i == s.length - 1 || (i == s.length - 2 && s[i + 1] in "yhs") -> i = s.length
                else -> return null
            }
        }
        return out.toString().ifEmpty { null }
    }

    fun matches(v: GengoshimaVocabEntity, examples: List<GengoshimaSentenceEntity>, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val kana = hiragana(q)
        val readings = hiragana(v.reading) + "\n" + hiragana(v.term)
        if (v.term.lowercase().contains(q) || readings.contains(kana)) return true
        if (v.meaning.lowercase().contains(q) || v.note.lowercase().contains(q)) return true
        romajiToHiragana(q)?.let { if (readings.contains(it)) return true }
        return examples.any { it.ja.contains(q) || it.en.lowercase().contains(q) }
    }
}
