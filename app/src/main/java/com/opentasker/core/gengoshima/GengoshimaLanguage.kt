package com.opentasker.core.gengoshima

/**
 * The languages a 言語島 source sentence can be in — what 白い熊 wrote or said before it became
 * Japanese. English is the default and the majority; Czech arrives from kxkb's walk capture, where
 * the review decides each sentence's language (kxkb contract §7), and can be set by hand on the
 * 未分類 page, in the islands editor and on 文入力.
 *
 * Stored per sentence as the code ([EN] / [CS]); anything else is normalised to English, as kxkb
 * does before sending.
 */
object GengoshimaLanguage {
    const val EN = "en"
    const val CS = "cs"

    val ALL = listOf(EN, CS)

    fun normalize(code: String?): String = code?.trim()?.lowercase()?.takeIf { it in ALL } ?: EN

    /** The other one — what a tap on the language chip switches to. */
    fun toggle(code: String): String = if (normalize(code) == CS) EN else CS

    /** The chip's text: `EN` / `CS`. */
    fun chip(code: String): String = normalize(code).uppercase()

    /** The language's name for a label: 英語 / チェコ語. */
    fun japaneseName(code: String): String = if (normalize(code) == CS) "チェコ語" else "英語"

    /**
     * The Japanese name an island gets from its [topic] at once: the topic itself when it is already
     * written in Japanese (any kana or kanji — 「健康」), otherwise blank, for Claude to name on the
     * first translation. Claude renamed a typed 健康 to 体の調子; a Japanese topic stands as it is
     * (白い熊, 2026-10-12).
     */
    fun islandNameFromTopic(topic: String): String = topic.trim().takeIf { JAPANESE.containsMatchIn(it) }.orEmpty()

    private val JAPANESE = Regex("[\\u3040-\\u30FF\\u3400-\\u9FFF\\uF900-\\uFAFF]")
}
