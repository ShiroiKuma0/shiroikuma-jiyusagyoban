package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaInboxEntity
import com.opentasker.core.storage.GengoshimaIslandEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 言語島 walk capture: docs/sister-app-contract-kxkb-gengoshima.md. */
class WalkCaptureTest {

    @Test
    fun `the WAV header is kxkb's canonical 44-byte PCM16 16 kHz mono`() {
        val h = SentenceCapture.header(32_000)
        assertEquals(44, h.size)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals(36 + 32_000, b.getInt(4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4))
        assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()) // PCM
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(16_000, b.getInt(24))
        assertEquals(32_000, b.getInt(28)) // byte rate
        assertEquals(2, b.getShort(32).toInt()) // block align
        assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", String(h, 36, 4))
        assertEquals(32_000, b.getInt(40))
    }

    @Test
    fun `rejected list parses uuid and reason`() {
        val m = ClipOffer.parseRejected("a-1:sha256, b-2:format")
        assertEquals(mapOf("a-1" to "sha256", "b-2" to "format"), m)
        assertTrue(ClipOffer.parseRejected(null).isEmpty())
        assertTrue(ClipOffer.parseRejected("").isEmpty())
    }

    @Test
    fun `intake parses items and refuses a bad payload`() {
        val rows = GengoshimaIntake.parse(
            """[{"uuid":"u1","text":"I walked.","recognized":"I walk","language":"en","capturedAt":5},
               {"uuid":"u2","text":"Rain."}]""",
            now = 99,
        )!!
        assertEquals(listOf("u1", "u2"), rows.map { it.uuid })
        assertEquals("I walk", rows[0].recognized)
        assertEquals(5L, rows[0].capturedAt)
        assertEquals("Rain.", rows[1].recognized)
        assertEquals(99L, rows[1].capturedAt)
        assertEquals("en", rows[1].language)
        assertNull(GengoshimaIntake.parse("not json", 0))
        assertNull(GengoshimaIntake.parse("""[{"uuid":"","text":"x"}]""", 0))
        assertNull(GengoshimaIntake.parse("[" + (1..51).joinToString(",") { """{"uuid":"$it","text":"t"}""" } + "]", 0))
        assertEquals(emptyList<GengoshimaInboxEntity>(), GengoshimaIntake.parse("[]", 0))
    }

    @Test
    fun `proposer answers map unknown islands to new`() {
        val picks = IslandProposer.parse(
            """{"picks":[{"uuid":"a","island_id":3,"new_name":"","new_register":""},
                         {"uuid":"b","island_id":42,"new_name":"","new_register":""},
                         {"uuid":"c","island_id":0,"new_name":"Rain","new_register":"casual"}]}""",
            islandIds = setOf(3L),
        )
        assertEquals(3L, picks.getValue("a").island_id)
        assertEquals(0L, picks.getValue("b").island_id)
        assertEquals("New island", picks.getValue("b").new_name)
        assertEquals("Rain", picks.getValue("c").new_name)
    }

    private fun row(uuid: String, at: Long, island: Long? = null, name: String = "", reg: String = "") =
        GengoshimaInboxEntity(uuid, "s $uuid", "s $uuid", "en", at, 0, island, name, reg, 1)

    @Test
    fun `filing plan keeps spoken order and makes one island per new topic`() {
        val islands = listOf(GengoshimaIslandEntity(id = 7, position = 1, nameEn = "Morning", createdAt = 0))
        val plan = InboxFiling.plan(
            listOf(
                row("c", 30, name = "Rain", reg = "casual"),
                row("a", 10, island = 7),
                row("b", 20, name = "Rain", reg = "casual"),
                row("d", 40, name = "morning"), // a "new" topic that is an existing island
            ),
            islands,
        )
        assertEquals(listOf("Rain" to "casual"), plan.newIslands)
        assertEquals(listOf("a", "b", "c", "d"), plan.placements.map { it.first.uuid })
        assertEquals(7L, plan.placements[0].second)
        assertEquals("Rain" to "casual", plan.placements[1].second)
        assertEquals(7L, plan.placements[3].second)
    }

    @Test
    fun `rows without an island block filing`() {
        assertEquals(listOf("x"), InboxFiling.unfiled(listOf(row("x", 1), row("y", 2, island = 1))).map { it.uuid })
    }
}
