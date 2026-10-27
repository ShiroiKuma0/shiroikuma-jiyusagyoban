package com.opentasker.core.gengoshima

import com.opentasker.core.gengoshima.Translator.Token
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-10-26: the voice reads Sumo and Sumodo the Japanese way, and 「1968 年」 loses its space. */
class EnglishSpeechTest {

    @Test
    fun `Sumo and Sumodo are respelled for the voice, whole words only`() {
        assertEquals("I like Smaw very much.", EnglishSpeech.text("I like Sumo very much."))
        assertEquals("Japanese Smaw-daw thought.", EnglishSpeech.text("Japanese Sumodo thought."))
        assertEquals("I discovered Smaw-daw - the philosophy behind Smaw.", EnglishSpeech.text("I discovered Smawdaw - the philosophy behind Smaw."))
        assertEquals("Smaw's rules, Smaw.", EnglishSpeech.text("Sumo's rules, SUMO."))
        assertEquals("Smaw and Smaw-daw, Smaw-daw.", EnglishSpeech.text("Sumō and Sumōdō, Sumodō."))
        assertEquals("Sumotori and consumo stay.", EnglishSpeech.text("Sumotori and consumo stay."))
        assertEquals("Sumo je sport.", EnglishSpeech.text("Sumo je sport.", GengoshimaLanguage.CS))
    }

    @Test
    fun `a space beside Japanese goes from the sentence and the words`() {
        val (ja, tokens) = Translator.closeSpaces(
            "20 年代以前は",
            listOf(Token("20", "20", "ニジュウ"), Token(" ", " "), Token("年代", "年代", "ネンダイ"), Token("以前"), Token("は")),
        )
        assertEquals("20年代以前は", ja)
        assertEquals(ja, tokens.joinToString("") { it.surface })
        val (ja2, tokens2) = Translator.closeSpaces("1968 年の", listOf(Token("1968"), Token("年", "年", "ネン"), Token("の")))
        assertEquals("1968年の", ja2)
        assertEquals(3, tokens2.size)
        val (ja3, _) = Translator.closeSpaces("New York に", listOf(Token("New York"), Token("に")))
        assertEquals("New Yorkに", ja3)
    }
}
