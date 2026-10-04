package com.opentasker.ui

import com.opentasker.ui.gengoshima.GS_TILES
import com.opentasker.ui.gengoshima.GsBoardOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class GsBoardOrderTest {
    @Test
    fun `stored order wins, new tiles go last, unknown keys are dropped`() {
        val got = GsBoardOrder.apply(GS_TILES, listOf("listen", "gone", "entry")).map { it.key }
        assertEquals(listOf("listen", "entry") + GS_TILES.map { it.key }.filterNot { it in setOf("listen", "entry") }, got)
    }

    @Test
    fun `no stored order is the default order, review and inbox first, one wide play tile`() {
        val tiles = GsBoardOrder.apply(GS_TILES, emptyList())
        assertEquals(listOf("review", "inbox", "entry"), tiles.take(3).map { it.key })
        assertEquals(listOf("listen"), tiles.filter { it.wide }.map { it.key })
        assertEquals(tiles.size, tiles.map { it.key }.toSet().size)
    }
}
