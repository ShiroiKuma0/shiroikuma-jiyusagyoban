package com.opentasker.ui.gengoshima

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Furigana are shown in hiragana; readings go to VOICEVOX in katakana. A reading typed on a kana
 * keyboard arrives in hiragana and has to reach VOICEVOX as katakana, which it reads verbatim.
 */
class KanaTest {
    @Test
    fun katakanaToHiraganaForFurigana() {
        assertEquals("なまびーる", hiragana("ナマビール"))
        assertEquals("ゔぁ", hiragana("ヴァ"))
    }

    @Test
    fun hiraganaToKatakanaForVoicevoxAndTheRestUntouched() {
        assertEquals("ナマ", katakana("なま"))
        assertEquals("ナマ", katakana("ナマ"))
        assertEquals("ナマ生", katakana(" なま生 "))
    }
}
