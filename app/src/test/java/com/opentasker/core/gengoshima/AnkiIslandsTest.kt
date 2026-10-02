package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity
import com.opentasker.core.storage.GengoshimaTombstoneEntity
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiIslandsTest {

    private val s = GengoshimaSettings()
    private val island = GengoshimaIslandEntity(id = 1, position = 1, nameEn = "Russia", nameJa = "ロシア", createdAt = 0, uuid = "I1")

    private fun sentence(id: Long, en: String, ja: String, audio: File?, enAudio: File? = null) = GengoshimaSentenceEntity(
        id = id, islandId = 1, position = id.toInt(), en = en, ja = ja,
        state = GengoshimaSentenceEntity.STATE_READY,
        audioPath = audio?.path.orEmpty(), audioHash = "h$id",
        enAudioPath = enAudio?.path.orEmpty(), enAudioHash = if (enAudio != null) "e$id" else "",
        createdAt = 0, updatedAt = 0, uuid = "S$id",
    )

    @Test
    fun aDeltaSendsOnlyWhatChangedAndAFullSendsEverything() {
        val dir = kotlin.io.path.createTempDirectory("anki").toFile()
        try {
            val ja = File(dir, "a.ogg").apply { writeText("ja") }
            val en = File(dir, "a-en.ogg").apply { writeText("en") }
            val synced = sentence(1, "I", "私", ja, en).let { it.copy(ankiHash = AnkiIslands.ankiHash(it, island)) }
            val changed = sentence(2, "You", "あなた", ja)
            val unvoiced = sentence(3, "He", "彼", null).copy(state = GengoshimaSentenceEntity.STATE_TRANSLATED)
            val all = listOf(synced, changed, unvoiced)

            val delta = AnkiIslands.plan(false, s, listOf(island), all, emptyList(), mapOf("I1" to "ロシア"))
            assertEquals(setOf(2L), delta.sent.keys)
            val one = delta.manifest["sentences"]!!.jsonArray.single().jsonObject
            assertEquals("S2", one["uuid"]!!.jsonPrimitive.content)
            assertEquals("I1", one["island"]!!.jsonPrimitive.content)
            assertEquals("audio/S2-ja.ogg", one["ja_audio"]!!.jsonPrimitive.content)
            // No English recording: the field is left as it is, not cleared.
            assertFalse(one.containsKey("en_audio"))

            val full = AnkiIslands.plan(true, s, listOf(island), all, emptyList(), emptyMap())
            assertEquals(setOf(1L, 2L), full.sent.keys)
            assertEquals("full", full.manifest["mode"]!!.jsonPrimitive.content)

            val zip = File(dir, "sync.zip")
            AnkiIslands.writeZip(full, zip)
            ZipFile(zip).use { z ->
                assertEquals(setOf("manifest.json", "audio/S1-ja.ogg", "audio/S1-en.ogg", "audio/S2-ja.ogg"), z.entries().toList().map { it.name }.toSet())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nothingChangedIsNoSyncButARenameOrADeletionIs() {
        val row = sentence(1, "I", "私", null).let { it.copy(audioPath = "/x", ankiHash = AnkiIslands.ankiHash(it.copy(audioPath = "/x"), island)) }
        assertTrue(AnkiIslands.plan(false, s, listOf(island), listOf(row), emptyList(), mapOf("I1" to "ロシア")).empty)

        val renamed = AnkiIslands.plan(false, s, listOf(island), listOf(row), emptyList(), mapOf("I1" to "露西亜"))
        assertFalse(renamed.empty)

        val gone = AnkiIslands.plan(
            false, s, listOf(island), listOf(row),
            listOf(GengoshimaTombstoneEntity("S9", "sentence", 0), GengoshimaTombstoneEntity("I9", "island", 0)),
            mapOf("I1" to "ロシア"),
        )
        assertEquals("S9", gone.manifest["deleted"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("I9", gone.manifest["deleted_islands"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun anAdoptedSentenceIsHandedBackByItsNoteIdOnlyUntilItIsSynced() {
        val adopted = sentence(1, "I", "私", null).copy(audioPath = "/x", ankiNid = 1759674101234)
        val plan = AnkiIslands.plan(false, s, listOf(island), listOf(adopted), emptyList(), emptyMap())
        assertEquals(1759674101234, plan.manifest["adopt"]!!.jsonObject["S1"]!!.jsonPrimitive.long)

        val later = adopted.copy(en = "Me", ankiHash = "old")
        val again = AnkiIslands.plan(false, s, listOf(island), listOf(later), emptyList(), emptyMap())
        assertTrue(again.manifest["adopt"]!!.jsonObject.isEmpty())
    }

    @Test
    fun aDeckSeparatorInAnIslandNameDoesNotMakeASubdeck() {
        assertEquals("仕事：：遊び", AnkiIslands.deckName(island.copy(nameJa = "仕事::遊び")))
        assertEquals("Work", AnkiIslands.deckName(island.copy(nameJa = "", nameEn = "Work")))
    }

    @Test
    fun theListIsReadAsPlainTextInNoteOrderUnderItsIsland() {
        val text = """
            {"format":"shiroikuma-anki-islands","version":1,"notetype":"Language Islands","notes":[
             {"nid":20,"fields":{"english":"Tom &amp; Jerry<br>run","japanese":"トム&nbsp;と","japanese-audio":"","english-audio":""},
              "tags":[],"cards":[{"ord":1,"deck":"言語島々::製作::猫"},{"ord":0,"deck":"言語島々::認識::猫"}]},
             {"nid":10,"fields":{"english":"I &lt;3 it","japanese":"好き","japanese-audio":"","english-audio":""},
              "tags":[],"cards":[{"ord":0,"deck":"言語島々::製作::ロシア"},{"ord":1,"deck":"言語島々::製作::ロシア"}]}
            ]}
        """.trimIndent()
        val notes = AnkiIslands.parseList(text)
        assertEquals(listOf(10L, 20L), notes.map { it.nid })
        assertEquals("I <3 it", notes[0].english)
        assertEquals("Tom & Jerry run", notes[1].english)
        assertEquals("トム と", notes[1].japanese)
        assertEquals("猫", AnkiIslands.islandOf(notes[1], s))
        // A misfiled recognition card: the production deck still names the island.
        assertEquals("ロシア", AnkiIslands.islandOf(notes[0], s))
    }
}
