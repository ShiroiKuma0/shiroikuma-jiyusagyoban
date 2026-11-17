package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.core.storage.GengoshimaVocabEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 言語島 取込: docs/gengoshima-import-format.md. */
class VocabImportTest {

    private val sample = """
        言語島 取込 1
        島: 仕事 | Work | 丁寧

        語: 会議【かいぎ】 = meeting; conference
          例: 会議は三時からです。 = The meeting starts at three.
          例: 会議に遅れてすみません。 = Sorry I am late for the meeting.
          注: formal; 打ち合わせ is the casual word

        語: メール = e-mail
        文: 明日は休みを取ります。 = I am taking tomorrow off.
    """.trimIndent()

    @Test
    fun `the sample parses into one island with its terms, examples and a sentence in file order`() {
        val p = VocabImport.parse(sample)
        assertEquals(emptyList<VocabImport.Problem>(), p.problems)
        val b = p.blocks.single()
        assertEquals(Triple("仕事", "Work", "丁寧"), Triple(b.nameJa, b.nameEn, b.register))
        assertEquals(3, b.items.size)
        val kaigi = b.items[0] as VocabImport.Term
        assertEquals("会議", kaigi.term)
        assertEquals("かいぎ", kaigi.reading)
        assertEquals("meeting; conference", kaigi.meaning)
        assertEquals("formal; 打ち合わせ is the casual word", kaigi.note)
        assertEquals(listOf("会議は三時からです。", "会議に遅れてすみません。"), kaigi.examples.map { it.ja })
        assertEquals("The meeting starts at three.", kaigi.examples[0].en)
        val mail = b.items[1] as VocabImport.Term
        assertEquals("メール" to "", mail.term to mail.reading)
        assertEquals("明日は休みを取ります。", (b.items[2] as VocabImport.Sentence).text.ja)
        assertEquals(3, b.sentenceCount)
    }

    @Test
    fun `ASCII keywords, bullets, other brackets and full-width signs read the same`() {
        val p = VocabImport.parse(
            """
            island: Work
            - **term:** 資料（しりょう） ＝ materials；documents
              * ex： 資料を送ります。 = I will send the materials.
            sentence: お疲れ様です。	Thanks for your work.
            """.trimIndent(),
        )
        assertEquals(emptyList<VocabImport.Problem>(), p.problems)
        val b = p.blocks.single()
        assertEquals("" to "Work", b.nameJa to b.nameEn)
        val t = b.terms.single()
        assertEquals(Triple("資料", "しりょう", "materials; documents"), Triple(t.term, t.reading, t.meaning))
        assertEquals("資料を送ります。", t.examples.single().ja)
        assertEquals("Thanks for your work.", (b.items[1] as VocabImport.Sentence).text.en)
    }

    @Test
    fun `an equals sign inside the English stays there`() {
        val t = VocabImport.parse("島: 数\n語: 合計【ごうけい】 = total (a + b = c)").blocks.single().terms.single()
        assertEquals("total (a + b = c)", t.meaning)
    }

    @Test
    fun `a missing translation and a stray line are reported by line, and nothing is dropped silently`() {
        val p = VocabImport.parse("島: 仕事\n語: 会議【かいぎ】\n例: 会議です。 = It is a meeting.\nsomething else\n文: 行きます。")
        assertEquals(listOf(2, 3, 4, 5), p.problems.map { it.line })
        assertTrue(p.blocks.single().items.isEmpty())
    }

    @Test
    fun `several islands in one paste make several blocks`() {
        val p = VocabImport.parse("島: 仕事\n文: 一。 = One.\n島: 相撲 | Sumo\n語: 力士【りきし】 = wrestler")
        assertEquals(listOf("仕事", "相撲"), p.blocks.map { it.nameJa })
        assertEquals(listOf(1, 0), p.blocks.map { it.items.count { i -> i is VocabImport.Sentence } })
    }

    @Test
    fun `the header check reads the first written line only`() {
        assertTrue(VocabImport.looksLikeImport("\n  言語島 取込 1\n島: 仕事"))
        assertFalse(VocabImport.looksLikeImport("島: 仕事\n言語島 取込 1"))
        assertFalse(VocabImport.looksLikeImport(""))
    }

    private fun island(id: Long, ja: String, en: String) =
        GengoshimaIslandEntity(id = id, position = id.toInt(), nameEn = en, nameJa = ja, createdAt = 0)

    @Test
    fun `the plan finds the island by either name and counts what is already there`() {
        val islands = listOf(island(4, "仕事", "Work - friends"), island(5, "相撲", "Sumo"))
        val vocab = listOf(
            GengoshimaVocabEntity(id = 1, islandId = 4, position = 1, term = "会議", reading = "かいぎ", meaning = "meeting", createdAt = 0, updatedAt = 0),
        )
        val sentences = listOf(
            GengoshimaSentenceEntity(id = 9, islandId = 4, position = 1, en = "x", ja = "会議は 三時からです", createdAt = 0, updatedAt = 0),
        )
        val plan = VocabImport.plan(VocabImport.parse(sample), islands, vocab, sentences)
        val b = plan.blocks.single()
        assertEquals(4L, b.islandId)
        assertEquals(1 to 1, b.newTerms to b.knownTerms)
        assertEquals(2 to 1, b.newSentences to b.knownSentences)
        assertEquals(0, plan.newIslands)
        assertTrue(plan.ready)
    }

    @Test
    fun `what another island already holds is not entered again, nor what the text says twice`() {
        val islands = listOf(island(4, "仕事", "Work"), island(5, "相撲", "Sumo"))
        val vocab = listOf(
            GengoshimaVocabEntity(id = 1, islandId = 5, position = 1, term = "会議", reading = "かいぎ", meaning = "meeting", createdAt = 0, updatedAt = 0),
        )
        val sentences = listOf(
            GengoshimaSentenceEntity(id = 9, islandId = 5, position = 1, en = "x", ja = "明日は休みを取ります。", createdAt = 0, updatedAt = 0),
        )
        val text = sample + "\n語: メール = e-mail\n文: 会議は三時からです。 = Again."
        val b = VocabImport.plan(VocabImport.parse(text), islands, vocab, sentences).blocks.single()
        assertEquals(4L, b.islandId)
        // 会議 is in 相撲; メール stands twice in the text.
        assertEquals(1 to 2, b.newTerms to b.knownTerms)
        // The stand-alone sentence is in 相撲; the last line repeats the first example.
        assertEquals(2 to 2, b.newSentences to b.knownSentences)
        assertEquals(mapOf(4 to "相撲", 10 to "相撲", 11 to "仕事", 12 to "仕事"), b.known)
    }

    @Test
    fun `an unknown island is new, and a block without one waits for a target`() {
        val fresh = VocabImport.plan(VocabImport.parse(sample), emptyList(), emptyList(), emptyList())
        assertNull(fresh.blocks.single().islandId)
        assertEquals(1, fresh.newIslands)
        assertTrue(fresh.ready)

        val bare = VocabImport.parse("語: 会議【かいぎ】 = meeting")
        assertFalse(VocabImport.plan(bare, emptyList(), emptyList(), emptyList()).ready)
        val aimed = VocabImport.plan(bare, listOf(island(5, "相撲", "Sumo")), emptyList(), emptyList(), targets = mapOf(0 to 5L))
        assertEquals(5L, aimed.blocks.single().islandId)
        assertTrue(aimed.ready)
    }

    @Test
    fun `what is exported imports again as the same terms and sentences`() {
        val islands = listOf(island(4, "仕事", "Work"))
        val vocab = listOf(
            GengoshimaVocabEntity(id = 1, islandId = 4, position = 1, term = "会議", reading = "かいぎ", meaning = "meeting; conference", note = "formal", createdAt = 0, updatedAt = 0),
        )
        val sentences = listOf(
            GengoshimaSentenceEntity(id = 1, islandId = 4, position = 1, en = "I am off tomorrow.", ja = "明日は休みです。", createdAt = 0, updatedAt = 0),
            GengoshimaSentenceEntity(id = 2, islandId = 4, position = 2, en = "The meeting is at three.", ja = "会議は三時です。", vocabId = 1, createdAt = 0, updatedAt = 0),
        )
        val text = VocabImport.export(islands, vocab, sentences)
        assertTrue(VocabImport.looksLikeImport(text))
        val back = VocabImport.parse(text)
        assertEquals(emptyList<VocabImport.Problem>(), back.problems)
        val b = back.blocks.single()
        assertEquals("仕事" to "Work", b.nameJa to b.nameEn)
        val t = b.terms.single()
        assertEquals(listOf("会議", "かいぎ", "meeting; conference", "formal"), listOf(t.term, t.reading, t.meaning, t.note))
        assertEquals("会議は三時です。", t.examples.single().ja)
        assertEquals("明日は休みです。", b.items.filterIsInstance<VocabImport.Sentence>().single().text.ja)
        val again = VocabImport.plan(back, islands, vocab, sentences)
        assertEquals(0 to 0, again.newTerms to again.newSentences)
    }
}

class VocabSearchTest {
    private fun term(term: String, reading: String, meaning: String) =
        GengoshimaVocabEntity(islandId = 1, position = 1, term = term, reading = reading, meaning = meaning, createdAt = 0, updatedAt = 0)

    @Test
    fun `romaji reads as kana, doubled consonants and n included`() {
        assertEquals("かいぎ", VocabSearch.romajiToHiragana("kaigi"))
        assertEquals("しゅっちょう", VocabSearch.romajiToHiragana("shucchou"))
        assertEquals("ざんぎょう", VocabSearch.romajiToHiragana("zangyou"))
        assertEquals("けんい", VocabSearch.romajiToHiragana("ken'i"))
        assertEquals("かい", VocabSearch.romajiToHiragana("kaig"))
        assertNull(VocabSearch.romajiToHiragana("meeting x"))
    }

    @Test
    fun `a query finds a term by spelling, kana, romaji, translation or example`() {
        val kaigi = term("会議", "かいぎ", "meeting; conference")
        val ex = listOf(GengoshimaSentenceEntity(islandId = 1, position = 1, en = "Sorry I am late.", ja = "遅れてすみません。", createdAt = 0, updatedAt = 0))
        for (q in listOf("会", "かいぎ", "カイギ", "kaigi", "Confer", "late", "遅れて")) assertTrue(q, VocabSearch.matches(kaigi, ex, q))
        assertFalse(VocabSearch.matches(kaigi, ex, "sumo"))
        assertEquals(listOf("meeting", "conference"), VocabSearch.senses(kaigi.meaning))
        assertEquals("めーる", VocabSearch.kanaKey(term("メール", "", "e-mail")))
    }
}

class VocabGroupTest {
    @Test
    fun `a reading is listed under its 五十音 row, a Latin word under its letter`() {
        for ((key, row) in listOf("かしだしわく" to "か", "がくせい" to "か", "ほしょうわく" to "は", "ぽすと" to "は", "みしようわく" to "ま", "よしん" to "や", "わく" to "わ", "ん" to "わ", "メール" to "ま", "NPE" to "N", "étude" to "E", "担保" to "#", "" to "#")) {
            assertEquals(key, row, VocabSearch.kanaGroup(key))
        }
        assertEquals(listOf("あ", "か", "わ", "A", "Z", "#"), listOf("#", "Z", "わ", "A", "か", "あ").sortedBy { VocabSearch.groupOrder(it) })
    }

    @Test
    fun `an English sense is listed by its first letter, brackets passed over`() {
        assertEquals("N", VocabSearch.letterGroup("Net Risk"))
        assertEquals("N", VocabSearch.letterGroup("(NCV) nominal value"))
        assertEquals("#", VocabSearch.letterGroup("24/7"))
        assertEquals(listOf("collateral", "(NCV) nominal", "Net Risk", "off-balance limit"), listOf("off-balance limit", "(NCV) nominal", "Net Risk", "collateral").sortedBy { VocabSearch.senseKey(it) })
    }
}

class VocabReadingsTest {
    private fun t(surface: String, reading: String = "", base: String = surface) = Translator.Token(surface, base, reading, "")
    private fun known(term: String, reading: String) = VocabReadings.Known(term, reading, "x; y")

    @Test
    fun `a term cut into parts is one word again, with the imported reading`() {
        val ja = "所管先の与信枠は増えた。"
        val split = listOf(t("所管", "ショカン"), t("先", "サキ"), t("の"), t("与信", "ヨシン"), t("枠", "ワク"), t("は"), t("増え", "フエ", "増える"), t("た"), t("。"))
        val out = VocabReadings.enforce(ja, split, listOf(known("所管先", "しょかんさき"), known("与信枠", "よしんわく")))
        assertEquals(listOf("所管先", "の", "与信枠", "は", "増え", "た", "。"), out.map { it.surface })
        assertEquals("ショカンサキ" to "所管先", out[0].reading to out[0].base)
        assertEquals("ヨシンワク", out[2].reading)
        assertEquals("x", out[0].gloss)
        assertEquals(ja, out.joinToString("") { it.surface })
    }

    @Test
    fun `another reading of a whole term is replaced by the imported one`() {
        val out = VocabReadings.enforce("今日は休み。", listOf(t("今日", "コンニチ"), t("は"), t("休み", "ヤスミ"), t("。")), listOf(known("今日", "きょう")))
        assertEquals("キョウ", out[0].reading)
    }

    @Test
    fun `the longer term wins, and a term inside a longer word of the split is another word`() {
        val both = listOf(known("会議", "かいぎ"), known("会議室", "かいぎしつ"))
        val out = VocabReadings.enforce("会議室で会議。", listOf(t("会議", "カイギ"), t("室", "シツ"), t("で"), t("会", "カイ"), t("議", "ギ"), t("。")), both)
        assertEquals(listOf("会議室", "で", "会議", "。"), out.map { it.surface })
        val alone = VocabReadings.enforce("会議室で。", listOf(t("会議室", "カイギシツ"), t("で"), t("。")), listOf(known("会議", "かいぎ")))
        assertEquals(listOf("会議室", "で", "。"), alone.map { it.surface })
    }

    @Test
    fun `an inflected form keeps the term's stem reading`() {
        val out = VocabReadings.enforce("資料を承ります。", listOf(t("資料", "シリョウ"), t("を"), t("承り", "ウケタマワリ", "承る"), t("ます"), t("。")), listOf(known("承る", "うけたまわる")))
        assertEquals("ウケタマワリ", out[2].reading)
        val wrong = VocabReadings.enforce("行った。", listOf(t("行っ", "オコナッ", "行く"), t("た"), t("。")), listOf(known("行く", "いく")))
        assertEquals("イッ", wrong[0].reading)
    }

    @Test
    fun `a split that does not spell the sentence is left as it came`() {
        val split = listOf(t("会", "カイ"), t("議", "ギ"))
        assertEquals(split, VocabReadings.enforce("会議です。", split, listOf(known("会議", "かいぎ"))))
    }
}
