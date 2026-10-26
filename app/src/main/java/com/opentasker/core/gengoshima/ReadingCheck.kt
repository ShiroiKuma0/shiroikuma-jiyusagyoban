package com.opentasker.core.gengoshima

/**
 * Do the furigana and the audio say the same thing? (白い熊, 2026-10-24: 他 showed ほか and was
 * heard as た — and so were 外, 家, 後, 日本 and two dozen more.)
 *
 * The furigana is Claude's reading of each word, chosen with the sentence's meaning in view. The
 * audio is VOICEVOX, which reads through OpenJTalk: a dictionary lookup that knows nothing of the
 * meaning, so a word with two readings comes out in whichever its dictionary ranks first. 白い熊 音声
 * answers each rendered sentence with the reading it used ([OnseRender.ItemReply.kana]); this
 * compares that with the furigana word by word and says which words disagree.
 *
 * [fix] then decides, per word, which side is right:
 *
 *  * A word with kanji: the furigana. VOICEVOX is told to read it so — the word goes to it as its
 *    katakana ([Translator.Token.reading_override], which [Translator.speechText] substitutes) — and
 *    the sentence is voiced again. Katakana is read verbatim, so the second rendering matches.
 *  * A number written in digits: the voice. Claude spells 2004 ニセンヨン and then 年 ネン, but
 *    2004年 is にせんよねん, which is what OpenJTalk's own number rules say; the furigana takes the
 *    spoken form.
 *
 * Both readings are compared in a canonical form, so that what is only a difference of NOTATION
 * never counts: the voice writes pronunciation (トーキョー, particle は as ワ, を as オ), the
 * furigana writes reading (トウキョウ, ハ, ヲ). Long vowels are folded into the vowel before them,
 * ヂ/ヅ into ジ/ズ, and the voice's accent, phrase and devoicing marks are dropped.
 */
object ReadingCheck {

    /** One word whose audio says something else than its furigana. */
    data class Mismatch(val index: Int, val surface: String, val furigana: String, val voice: String)

    private val KANJI = Regex("[\\u3400-\\u9fff々〆ヶ]")
    private val DIGIT = Regex("[0-9０-９]")
    private val MARKS = Regex("[’'/_、。，．,.！？!?\\s・「」『』（）()～〜…—\\-]")
    private const val E_ROW = "エケゲセゼテデネヘベペメレヱェ"
    private const val I_ROW = "イキギシジチヂニヒビピミリィ"
    private const val O_ROW = "オコゴソゾトドノホボポモヨョロヲォ"
    private const val U_ROW = "ウクグスズツヅヌフブプムユュルゥ"

    fun katakana(s: String): String = buildString {
        for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }

    /** [s] in canonical form, with the index in [s] each kept character came from. */
    internal fun canon(s: String): Pair<String, IntArray> {
        val out = StringBuilder()
        val from = ArrayList<Int>()
        katakana(s).forEachIndexed { i, raw ->
            if (MARKS.matches(raw.toString())) return@forEachIndexed
            val c = when (raw) {
                'ヲ' -> 'オ'; 'ハ' -> 'ワ'; 'ヘ' -> 'エ'; 'ヂ' -> 'ジ'; 'ヅ' -> 'ズ'
                else -> raw
            }
            if (c == 'ー') return@forEachIndexed
            val prev = out.lastOrNull()
            if (prev != null && (
                    (c == 'ウ' && (prev in O_ROW || prev in U_ROW)) ||
                        (c == 'オ' && prev in O_ROW) ||
                        (c == 'イ' && (prev in E_ROW || prev in I_ROW))
                    )
            ) return@forEachIndexed
            out.append(c)
            from += i
        }
        return out.toString() to from.toIntArray()
    }

    /** What a word reads as, by the furigana: its override, its reading, or the kana it is written in. */
    private fun furigana(t: Translator.Token): String =
        t.reading_override?.takeIf { it.isNotBlank() } ?: t.reading.ifBlank { t.surface }

    /**
     * The words of [tokens] that [voice] (the reading 音声 used, AquesTalk-style kana) reads
     * otherwise than their furigana. Words written only in kana are never reported: what differs
     * there is notation, never a reading.
     */
    fun mismatches(tokens: List<Translator.Token>, voice: String): List<Mismatch> {
        // With their places in [tokens]: a sentence can hold the same word twice.
        val places = tokens.indices.filter { tokens[it].surface.isNotBlank() }
        val words = places.map { tokens[it] }
        if (words.isEmpty() || voice.isBlank()) return emptyList()
        // The furigana as one string, each character tagged with the word it belongs to.
        val raw = StringBuilder()
        val owner = ArrayList<Int>()
        words.forEachIndexed { w, t -> furigana(t).forEach { raw.append(it); owner += w } }
        val (exp, expFrom) = canon(raw.toString())
        val (got, gotFrom) = canon(voice)
        val expOwner = IntArray(exp.length) { owner[expFrom[it]] }

        // Edit-distance alignment; a few dozen morae each side, so the full table is cheap.
        val n = exp.length
        val m = got.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(
                d[i - 1][j - 1] + if (exp[i - 1] == got[j - 1]) 0 else 1,
                d[i - 1][j] + 1,
                d[i][j - 1] + 1,
            )
        }
        // Walk back: every word touched by a difference, and the stretch of the voice aligned to it.
        val bad = sortedSetOf<Int>()
        val voiceSpan = HashMap<Int, IntRange>()
        fun note(w: Int, j: Int?) {
            if (j == null) return
            val r = voiceSpan[w]
            voiceSpan[w] = if (r == null) j..j else minOf(r.first, j)..maxOf(r.last, j)
        }
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (if (exp[i - 1] == got[j - 1]) 0 else 1) -> {
                    val w = expOwner[i - 1]
                    note(w, j - 1)
                    if (exp[i - 1] != got[j - 1]) bad += w
                    i--; j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> {
                    bad += expOwner[i - 1]
                    i--
                }
                else -> {
                    // The voice says more here: blame the word before the gap (or the first word).
                    val w = if (i > 0) expOwner[i - 1] else expOwner.firstOrNull() ?: 0
                    bad += w
                    note(w, j - 1)
                    j--
                }
            }
        }
        return bad.mapNotNull { w ->
            val t = words[w]
            if (!KANJI.containsMatchIn(t.surface) && !DIGIT.containsMatchIn(t.surface)) return@mapNotNull null
            val said = voiceSpan[w]?.let { r -> voice.substring(gotFrom[r.first], gotFrom[r.last] + 1) }.orEmpty()
            Mismatch(places[w], t.surface, furigana(t), katakana(said).replace(MARKS, ""))
        }
    }

    /**
     * [tokens] corrected so the next rendering agrees with the furigana, and the words that could
     * not be: a kanji word whose override VOICEVOX still read otherwise is left for 白い熊 to look at.
     * Unchanged tokens come back as the same list.
     */
    data class Fixed(val tokens: List<Translator.Token>, val revoice: Boolean, val fixed: List<Mismatch>, val stuck: List<Mismatch>)

    fun fix(tokens: List<Translator.Token>, voice: String): Fixed {
        val found = mismatches(tokens, voice)
        if (found.isEmpty()) return Fixed(tokens, false, emptyList(), emptyList())
        val out = tokens.toMutableList()
        val fixed = ArrayList<Mismatch>()
        val stuck = ArrayList<Mismatch>()
        var revoice = false
        for (mm in found) {
            val t = out[mm.index]
            when {
                !KANJI.containsMatchIn(t.surface) && mm.voice.isNotBlank() -> {
                    out[mm.index] = t.copy(reading = mm.voice)
                    fixed += mm
                }
                t.reading_override.isNullOrBlank() && t.reading.isNotBlank() -> {
                    out[mm.index] = t.copy(reading_override = katakana(t.reading))
                    revoice = true
                    fixed += mm
                }
                else -> stuck += mm
            }
        }
        return Fixed(out, revoice, fixed, stuck)
    }
}
