package com.opentasker.ui

import com.opentasker.ui.charts.huawei.BOARD_TILES
import com.opentasker.ui.charts.huawei.BoardOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stored board arrangement must never cost a tile.
 *
 * The stored list names tiles by key; `BOARD_TILES` defines them. The two drift apart every time a
 * tile is added, and how that is resolved decides whether an upgrade quietly removes a feature from
 * 白い熊's board. Merging rather than replacing is the whole answer, and this pins it down.
 */
class BoardOrderTest {

    @Test
    fun `no stored order leaves the board as built`() {
        assertEquals(BOARD_TILES, BoardOrder.apply(BOARD_TILES, emptyList()))
    }

    @Test
    fun `a stored order is honoured`() {
        val reversed = BOARD_TILES.map { it.key }.reversed()
        assertEquals(reversed, BoardOrder.apply(BOARD_TILES, reversed).map { it.key })
    }

    /** The case that matters on every upgrade: a tile the stored order has never heard of. */
    @Test
    fun `a tile added later appears at the end rather than vanishing`() {
        val partial = BOARD_TILES.map { it.key }.drop(2)
        val applied = BoardOrder.apply(BOARD_TILES, partial)
        assertEquals("every tile must survive", BOARD_TILES.size, applied.size)
        assertTrue(
            "the ones the store never saw belong at the end, in their own order",
            applied.takeLast(2).map { it.key } == BOARD_TILES.take(2).map { it.key },
        )
    }

    /** And a stored key for a tile that has since been removed is simply ignored. */
    @Test
    fun `a stale key does not break the board`() {
        val withGhost = listOf("a-tile-that-no-longer-exists") + BOARD_TILES.map { it.key }
        val applied = BoardOrder.apply(BOARD_TILES, withGhost)
        assertEquals(BOARD_TILES.size, applied.size)
        assertEquals(BOARD_TILES.map { it.key }, applied.map { it.key })
    }
}
