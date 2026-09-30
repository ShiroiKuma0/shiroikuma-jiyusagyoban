package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GengoshimaTest {

    private fun island(id: Long, pos: Int, en: String, ja: String = "", dir: String? = null) =
        GengoshimaIslandEntity(id = id, position = pos, nameEn = en, nameJa = ja, dirName = dir, createdAt = 0)

    private fun sentence(id: Long, island: Long, pos: Int, en: String, ja: String = "", path: String = "") =
        GengoshimaSentenceEntity(
            id = id, islandId = island, position = pos, en = en, ja = ja, audioPath = path,
            state = if (path.isEmpty()) GengoshimaSentenceEntity.STATE_TRANSLATED else GengoshimaSentenceEntity.STATE_READY,
            createdAt = 0, updatedAt = 0,
        )

    private fun settings(dir: File) = GengoshimaSettings(dir = dir.absolutePath)

    @Test
    fun theDefaultLayoutReadsAsThePlanDrewIt() {
        val s = GengoshimaSettings()
        assertEquals("001 自己紹介 — Self-introduction", AudioTree.islandDirName(island(1, 1, "Self-introduction", "自己紹介"), s))
        assertEquals("001 私は白い熊です.ogg", AudioTree.sentenceFileName(sentence(1, 1, 1, "I am", "私は白い熊です。"), s))
    }

    @Test
    fun anIslandWithoutAJapaneseNameLeavesNoDanglingDash() {
        assertEquals("002 In the car", AudioTree.islandDirName(island(2, 2, "In the car"), GengoshimaSettings()))
    }

    @Test
    fun forbiddenCharactersBecomeTheirFullWidthTwinsAndLongNamesAreCut() {
        assertEquals("A／B：C？D", AudioTree.field("A/B:C?D", 40))
        // Sentence-final marks are dropped from a name, whatever width they arrived in.
        assertEquals("元気ですか", AudioTree.field("元気ですか？", 40))
        assertEquals("あいうえお", AudioTree.field("あいうえおかきくけこ", 5))
        assertTrue(AudioTree.capBytes("あ".repeat(200)).toByteArray().size <= AudioTree.MAX_NAME_BYTES)
    }

    @Test
    fun aReadingOverrideIsSpokenInKatakanaAndOnlyWhenTheWordsStillSpellTheSentence() {
        val tokens = Translator.tokensJson(
            listOf(
                Translator.Token("生", "生", "セイ", "raw", reading_override = "ナマ"),
                Translator.Token("ビール", "ビール", "ビール", "beer"),
            ),
        )
        assertEquals("ナマビール", Translator.speechText("生ビール", tokens))
        // Hand-edited Japanese no longer matches the split: read it as written.
        assertEquals("生ビールです", Translator.speechText("生ビールです", tokens))
        assertEquals("そのまま", Translator.speechText("そのまま", ""))
    }

    @Test
    fun theHashChangesWithTheVoiceNotOnlyWithTheText() {
        val a = GenerationRunner.audioHash("文", GengoshimaSettings())
        assertEquals(a, GenerationRunner.audioHash("文", GengoshimaSettings()))
        assertFalse(a == GenerationRunner.audioHash("文", GengoshimaSettings(speed = "1.0")))
    }

    @Test
    fun tidyingMovesSwapsAndDeletesButNeverTouchesAFolderItDidNotMake() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("gengoshima").toFile()
        try {
            val s = settings(root)
            // On disk: island dir under an OLD name, two sentences swapped relative to their files.
            val old = File(root, "001 Old").apply { mkdirs() }
            File(old, "001 あ.ogg").writeText("A")
            File(old, "002 い.ogg").writeText("I")
            File(old, "009 stale.ogg").writeText("S")
            val mine = File(root, "私のメモ").apply { mkdirs() }
            File(mine, "note.txt").writeText("keep")
            val isl = island(1, 1, "New", dir = "001 Old")
            // 「あ」 is now second and 「い」 first.
            val sentences = listOf(
                sentence(10, 1, 2, "a", "あ", File(old, "001 あ.ogg").path),
                sentence(11, 1, 1, "i", "い", File(old, "002 い.ogg").path),
            )
            val saved = HashMap<Long, GengoshimaSentenceEntity>()
            val tidy = AudioTree.reconcile(listOf(isl), sentences, s, {}, { saved[it.id] = it })
            val dir = File(root, "001 New")
            assertTrue(dir.isDirectory)
            assertEquals("A", File(dir, "002 あ.ogg").readText())
            assertEquals("I", File(dir, "001 い.ogg").readText())
            assertFalse(File(dir, "009 stale.ogg").exists())
            assertEquals(File(dir, "002 あ.ogg").absolutePath, saved.getValue(10).audioPath)
            assertTrue("a folder 白い熊 made is left alone", File(mine, "note.txt").exists())
            assertEquals(1, tidy.deleted)
        } finally {
            root.deleteRecursively()
        }
    }

    /** The live count: only a sentence whose Japanese has fully arrived counts, and it is the one shown. */
    @Test
    fun sentencesAreCountedAsTheirJapaneseArrives() {
        val partial = """{"island_name_ja":"仕事","sentences":[{"id":1,"ja":"彼は「\"はい\"」と言った。","tokens":[]},{"id":2,"ja":"まだ書いて"""
        val (n, last) = Translator.completed(partial)
        assertEquals(1, n)
        assertEquals("彼は「\"はい\"」と言った。", last)
        assertEquals(0, Translator.completed("""{"island_name_ja":"仕""").first)
    }
}
