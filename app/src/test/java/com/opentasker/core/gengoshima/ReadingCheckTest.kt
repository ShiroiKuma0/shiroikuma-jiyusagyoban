package com.opentasker.core.gengoshima

import com.opentasker.core.gengoshima.Translator.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The furigana against what VOICEVOX said, with the voice's readings as 音声 reports them
 * (AquesTalk-style: accent ', phrase /, pause 、). The sentences are 白い熊's own from the
 * 2026-10-24 sweep, where the voice was checked against OpenJTalk on the PC.
 */
class ReadingCheckTest {

    private fun t(surface: String, reading: String = "") = Token(surface, surface, reading)

    private val fluff = listOf(
        t("フラッフ", "フラッフ"), t("は", "ハ"), t("他", "ホカ"), t("の", "ノ"), t("犬", "イヌ"), t("を", "ヲ"),
        t("見る", "ミル"), t("と", "ト"), t("いつも", "イツモ"), t("怖がっ", "コワガッ"), t("て", "テ"), t("、"),
        t("何", "ナニ"), t("が", "ガ"), t("起こる", "オコル"), t("の", "ノ"), t("か", "カ"), t("不安", "フアン"),
        t("で", "デ"), t("固まっ", "カタマッ"), t("て", "テ"), t("しまい", "シマイ"), t("ます", "マス"), t("。"),
    )
    private val fluffVoice = "フラッフワ/タノ'イヌヲ/ミ'ルト/イ'ツモ/コワガッテ、ナ'ニガ/オコ'ルノカ/フアンデ/カタマ'ッテ/シマイマ'ス"

    @Test
    fun `他 read as タ is caught, and only 他`() {
        val found = ReadingCheck.mismatches(fluff, fluffVoice)
        assertEquals(listOf("他"), found.map { it.surface })
        assertEquals(2, found.single().index)
        assertEquals("ホカ", found.single().furigana)
        assertEquals("タ", found.single().voice)
    }

    @Test
    fun `the furigana wins for a kanji word - it goes to VOICEVOX as katakana`() {
        val fixed = ReadingCheck.fix(fluff, fluffVoice)
        assertTrue(fixed.revoice)
        assertEquals("ホカ", fixed.tokens[2].reading_override)
        val ja = fluff.joinToString("") { it.surface }
        assertEquals(
            "フラッフはホカの犬を見るといつも怖がって、何が起こるのか不安で固まってしまいます。",
            Translator.speechText(ja, Translator.tokensJson(fixed.tokens)),
        )
    }

    @Test
    fun `notation alone is never a mismatch - long vowels, は as ワ, へ as エ, を as オ`() {
        val tokyo = listOf(t("東京", "トウキョウ"), t("へ", "ヘ"), t("行こ", "イコ"), t("う", "ウ"), t("。"))
        assertTrue(ReadingCheck.mismatches(tokyo, "トーキョーエ/イコ'ー").isEmpty())
        val big = listOf(t("大きい", "オオキイ"), t("先生", "センセイ"), t("を", "ヲ"), t("見", "ミ"), t("た", "タ"))
        assertTrue(ReadingCheck.mismatches(big, "オーキ'イ/センセーオ/ミ'タ").isEmpty())
        val work = listOf(t("仕事", "シゴト"), t("も", "モ"), t("楽しく", "タノシク"), t("なきゃ", "ナキャ"), t("ね", "ネ"), t("。"))
        assertTrue(ReadingCheck.mismatches(work, "シゴトモ/タノシ'クナキャネ").isEmpty())
    }

    @Test
    fun `a number takes the spoken form instead - 2004年 is ニセンヨネン`() {
        val year = listOf(t("2004", "ニセンヨン"), t("年", "ネン"), t("に", "ニ"), t("日本", "ニホン"), t("で", "デ"))
        val fixed = ReadingCheck.fix(year, "ニセンヨネンニ/ニホンデ")
        assertEquals(listOf("2004"), fixed.fixed.map { it.surface })
        assertEquals("ニセンヨ", fixed.tokens[0].reading)
        assertFalse(fixed.revoice)
    }

    @Test
    fun `the same word twice is reported at both places`() {
        val s = listOf(t("鳳翔龍", "ホウショウリュウ"), t("と", "ト"), t("鳳翔龍", "ホウショウリュウ"))
        val found = ReadingCheck.mismatches(s, "オートリショーリュート/オートリショーリュー")
        assertEquals(listOf(0, 2), found.map { it.index })
    }

    @Test
    fun `an override VOICEVOX still read otherwise is stuck, never looped on`() {
        val s = listOf(Token("他", "他", "ホカ", reading_override = "ホカ"), t("の", "ノ"))
        val fixed = ReadingCheck.fix(s, "タノ")
        assertTrue(fixed.fixed.isEmpty())
        assertEquals(listOf("他"), fixed.stuck.map { it.surface })
        assertFalse(fixed.revoice)
    }

    @Test
    fun `a space in the sentence no longer blocks the override`() {
        val tokens = listOf(t("20", "ニジュウ"), Token(" "), t("年代", "ネンダイ"), Token("外", "外", "ソト", reading_override = "ソト"))
        assertEquals("20年代ソト", Translator.speechText("20 年代外", Translator.tokensJson(tokens)))
    }
}
