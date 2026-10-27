package com.opentasker.core.gengoshima

import com.opentasker.core.gengoshima.Translator.Token

/**
 * 白い熊's own corrections to a sentence's word split, made in the island editor (2026-10-26): cut a
 * word in two, join it with the next, or set its reading. The Japanese is never touched — the words
 * still spell it after every edit — and a sentence so edited is marked `wordsEdited`, which no run
 * splits again.
 *
 * Readings are katakana, like everything the translator writes. A reading 白い熊 sets that differs
 * from the translator's becomes the word's `reading_override`, so the voice says what the furigana
 * says; one that equals it clears the override.
 */
object WordEdit {

    /** What the furigana shows for [t]: 白い熊's reading where there is one. */
    fun shown(t: Token): String = t.reading_override?.takeIf { it.isNotBlank() } ?: t.reading

    /** Set word [k]'s reading; blank goes back to the translator's own. */
    fun setReading(tokens: List<Token>, k: Int, reading: String): List<Token> {
        val t = tokens.getOrNull(k) ?: return tokens
        val r = katakana(reading)
        val override = r.takeIf { it.isNotBlank() && it != t.reading }
        return tokens.toMutableList().also { it[k] = t.copy(reading_override = override) }
    }

    /**
     * The readings a cut of word [t] after [at] characters most likely has — exact when the part
     * after or before the cut is kana that ends or starts the reading (食べ|ます → タベ + マス),
     * otherwise the whole reading to the first part and nothing to the second, for 白い熊 to share out.
     */
    fun guessSplit(t: Token, at: Int): Pair<String, String> {
        val r = shown(t)
        val a = t.surface.take(at)
        val b = t.surface.drop(at)
        val kb = katakana(b)
        val ka = katakana(a)
        return when {
            isKana(b) && r.endsWith(kb) && r.length > kb.length -> r.dropLast(kb.length) to kb
            isKana(a) && r.startsWith(ka) && r.length > ka.length -> ka to r.drop(ka.length)
            else -> r to ""
        }
    }

    /** Cut word [k] after [at] characters, the two parts reading [readingA] and [readingB]. */
    fun split(tokens: List<Token>, k: Int, at: Int, readingA: String, readingB: String): List<Token> {
        val t = tokens.getOrNull(k) ?: return tokens
        if (at <= 0 || at >= t.surface.length) return tokens
        val a = t.surface.take(at)
        val b = t.surface.drop(at)
        val parts = listOf(
            Token(surface = a, base = a, reading = katakana(readingA), gloss = t.gloss),
            Token(surface = b, base = b, reading = katakana(readingB), gloss = ""),
        )
        return tokens.subList(0, k) + parts + tokens.subList(k + 1, tokens.size)
    }

    /** Join word [k] with the one after it into one word, its reading the two readings together. */
    fun joinNext(tokens: List<Token>, k: Int): List<Token> {
        val a = tokens.getOrNull(k) ?: return tokens
        val b = tokens.getOrNull(k + 1) ?: return tokens
        val overridden = !a.reading_override.isNullOrBlank() || !b.reading_override.isNullOrBlank()
        val joined = Token(
            surface = a.surface + b.surface,
            base = a.surface + b.surface,
            reading = a.reading + b.reading,
            gloss = listOf(a.gloss, b.gloss).filter { it.isNotBlank() }.joinToString(" · "),
            reading_override = if (overridden) shown(a) + shown(b) else null,
        )
        return tokens.subList(0, k) + joined + tokens.subList(k + 2, tokens.size)
    }

    /** ひらがな → カタカナ, so a reading typed on a kana keyboard is stored the translator's way. */
    fun katakana(text: String): String = buildString {
        for (ch in text.trim()) append(if (ch in 'ぁ'..'ゖ') (ch + 0x60) else ch)
    }

    private fun isKana(s: String) = s.isNotEmpty() && s.all { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it == 'ー' }
}
