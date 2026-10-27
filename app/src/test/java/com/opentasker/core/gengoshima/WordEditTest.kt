package com.opentasker.core.gengoshima

import com.opentasker.core.gengoshima.Translator.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 白い熊's hand corrections to a word split: the words always still spell the sentence. */
class WordEditTest {

    private val words = listOf(
        Token("私", "私", "ワタシ"), Token("は", "は", "ハ"), Token("食べます", "食べる", "タベマス", "eat"), Token("。"),
    )
    private fun spelt(t: List<Token>) = t.joinToString("") { it.surface }

    @Test
    fun `a cut before kana shares the reading out by itself`() {
        assertEquals("タベ" to "マス", WordEdit.guessSplit(words[2], 2))
        val cut = WordEdit.split(words, 2, 2, "たべ", "ます")
        assertEquals(spelt(words), spelt(cut))
        assertEquals(listOf("食べ", "ます"), cut.subList(2, 4).map { it.surface })
        assertEquals(listOf("タベ", "マス"), cut.subList(2, 4).map { it.reading })
        assertEquals("eat", cut[2].gloss)
    }

    @Test
    fun `a cut between kanji leaves the second reading to 白い熊`() {
        val t = Token("相撲道", "相撲道", "スモウドウ")
        assertEquals("スモウドウ" to "", WordEdit.guessSplit(t, 2))
    }

    @Test
    fun `joining two words keeps the spelling and joins the readings`() {
        val joined = WordEdit.joinNext(words, 0)
        assertEquals(spelt(words), spelt(joined))
        assertEquals("私は", joined[0].surface)
        assertEquals("ワタシハ", joined[0].reading)
        assertNull(joined[0].reading_override)
        val withOverride = WordEdit.joinNext(WordEdit.setReading(words, 0, "わたくし"), 0)
        assertEquals("ワタクシハ", withOverride[0].reading_override)
    }

    @Test
    fun `a reading equal to the translator's clears the override`() {
        val set = WordEdit.setReading(words, 0, "わたくし")
        assertEquals("ワタクシ", set[0].reading_override)
        assertNull(WordEdit.setReading(set, 0, "ワタシ")[0].reading_override)
        assertNull(WordEdit.setReading(set, 0, "")[0].reading_override)
    }

    @Test
    fun `a cut at either end does nothing`() {
        assertEquals(words, WordEdit.split(words, 2, 0, "", ""))
        assertEquals(words, WordEdit.split(words, 2, 4, "", ""))
    }
}
