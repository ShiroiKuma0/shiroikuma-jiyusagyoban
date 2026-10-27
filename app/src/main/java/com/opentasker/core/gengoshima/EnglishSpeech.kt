package com.opentasker.core.gengoshima

/**
 * What 白い熊 音声's English voice (Kokoro) is given to read — the English with the words it says
 * wrong respelled, the English counterpart of the katakana overrides in [Translator.speechText].
 * The sentence keeps its own spelling on every screen and in 暗記; only the voice reads this.
 *
 * Kokoro looks a word up in its US lexicon and falls back to espeak-ng's rules for one it lacks.
 * The lexicon has sumo as /sˈumO/, the English "Sūmō", and espeak reads "Sumodo" /suːmˈoʊdoʊ/.
 * 白い熊 wants the Japanese sound, S'maw and S'mawdaw. Of four spellings rendered with the cards' own
 * voice (am_michael, speed 1.0) and compared by ear, 白い熊 chose "Smaw" and "Smaw-daw" (2026-10-27):
 * the hyphen makes the voice say the second syllable as its own clear "daw". The earlier "Smo" and
 * "Smodo" (2026-10-26) came out as English "smoh" and were wrong.
 *
 * Every way the words are written is caught: Sumo, Sumō, Sumodo, Sumōdō (and the half-macron
 * forms), and 白い熊's own dictation spellings Smaw and Smawdaw.
 */
object EnglishSpeech {

    /** Whole words, any case, longest first so Sumodo is never read as Sumo + do. */
    private val RESPELL = listOf(
        "sumōdō" to "Smaw-daw",
        "sumōdo" to "Smaw-daw",
        "sumodō" to "Smaw-daw",
        "sumodo" to "Smaw-daw",
        "smawdaw" to "Smaw-daw",
        "sumō" to "Smaw",
        "sumo" to "Smaw",
        "smaw" to "Smaw",
    )

    // Lookarounds, not \b: Java's \b counts only ASCII letters as word characters, so it would
    // find no word end after the ō of Sumō.
    private val WORDS = Regex(
        "(?<![\\w\\u00C0-\\u024F])(" + RESPELL.joinToString("|") { it.first } + ")(?![\\w\\u00C0-\\u024F])",
        RegexOption.IGNORE_CASE,
    )

    /** [en] as the voice should read it; Czech is read as written. */
    fun text(en: String, language: String = GengoshimaLanguage.EN): String {
        if (GengoshimaLanguage.normalize(language) != GengoshimaLanguage.EN) return en
        return WORDS.replace(en) { m -> RESPELL.first { it.first.equals(m.value, ignoreCase = true) }.second }
    }
}
