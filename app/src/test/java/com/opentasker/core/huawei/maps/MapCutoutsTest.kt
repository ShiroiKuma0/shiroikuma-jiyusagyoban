package com.opentasker.core.huawei.maps

import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The cutout grid, checked against arithmetic that can be verified by hand.
 *
 * These numbers are not from our own projection code round-tripped through itself — that would only
 * prove self-consistency. Web Mercator tile coordinates are published and every slippy map agrees on
 * them, so the fixtures are taken from that definition and the test is a real external check.
 */
class MapCutoutsTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun tileCoordinatesMatchTheWebMercatorDefinition() {
        // Prague, 50.0755 N 14.4378 E. At z16 the published tile is 35396/22204 — computed from
        // the Web Mercator definition independently of this code, which is the only reason it is
        // worth asserting. An expected value produced by the thing under test proves nothing.
        val (x, y) = Mercator.tileOf(50.0755, 14.4378, 16)
        assertEquals(35396, x)
        assertEquals(22204, y)
        // Null Island is the exact centre of the grid at every zoom.
        assertEquals(1 shl 15 to (1 shl 15), Mercator.tileOf(0.0, 0.0, 16))
    }

    @Test
    fun aTileCornerProjectsBackToItsOwnCoordinate() {
        // The inverse is the check that the forward transform is not merely plausible.
        for (zoom in listOf(10, 14, 16, 18)) {
            val lon = Mercator.longitudeOf(Mercator.tileX(14.4378, zoom), zoom)
            val lat = Mercator.latitudeOf(Mercator.tileY(50.0755, zoom), zoom)
            assertTrue("longitude at z$zoom", abs(lon - 14.4378) < 1e-9)
            assertTrue("latitude at z$zoom", abs(lat - 50.0755) < 1e-9)
        }
    }

    @Test
    fun theNameCarriesTheWholeTransform() {
        val c = MapCutouts.Cutout(16, 35396, 22204, 3, 2, File("x"))
        assertEquals("z16_x35396_y22204_3x2.png", c.id)
        val back = MapCutouts.parse(File(c.id))
        assertNotNull(back)
        assertEquals(c.copy(file = back!!.file), back)
    }

    @Test
    fun aWalkFindsTheCutoutItFitsInAndNotOneItDoesNot() {
        val root = temp.newFolder()
        val walk = MapCutouts.Box(50.070, 14.430, 50.080, 14.445)
        // Nothing on disk: this is somewhere new, and saying so is the whole trigger.
        assertNull(MapCutouts.cover(root, walk))

        val wanted = MapCutouts.needed(root, walk, preferredZoom = 16)
        MapCutouts.dir(root)
        wanted.file.writeBytes(byteArrayOf(1))
        val found = MapCutouts.cover(root, walk)
        assertNotNull("the cutout we just cut must cover the walk that asked for it", found)
        assertEquals(wanted.id, found!!.id)

        // Somewhere else entirely is not covered by it.
        assertNull(MapCutouts.cover(root, MapCutouts.Box(35.68, 139.76, 35.69, 139.77)))
    }

    @Test
    fun theMarginMakesOneCutoutServeTheNextWalkToo() {
        val root = temp.newFolder()
        MapCutouts.dir(root)
        val first = MapCutouts.Box(50.0740, 14.4360, 50.0760, 14.4400)
        MapCutouts.needed(root, first, 16).file.writeBytes(byteArrayOf(1))
        // A second walk from the same door, a few streets further — the point of the margin is that
        // this does NOT send us back to 地図.
        val second = MapCutouts.Box(50.0735, 14.4340, 50.0775, 14.4425)
        assertNotNull(MapCutouts.cover(root, second))
    }

    @Test
    fun aSprawlingBoxIsAnsweredAtACoarserZoomRatherThanRefused() {
        val root = temp.newFolder()
        // Prague to Brno: no cutout at z16 could hold this without thousands of tiles.
        val huge = MapCutouts.Box(49.19, 14.43, 50.08, 16.61)
        val c = MapCutouts.needed(root, huge, preferredZoom = 16)
        assertTrue("zoom must have been stepped down", c.zoom < 16)
        assertTrue("and the block kept small", c.tilesW <= MapCutouts.MAX_TILES)
        assertTrue(c.tilesH <= MapCutouts.MAX_TILES)
        assertTrue("it still has to cover the walk", c.covers(huge))
    }

    @Test
    fun pixelsLandWhereTheyShouldInsideTheImage() {
        val c = MapCutouts.Cutout(16, 35396, 22204, 2, 2, File("x"))
        val w = 2 * Mercator.TILE_PX
        val h = 2 * Mercator.TILE_PX
        // The cutout's own top-left corner is pixel 0,0 by definition.
        val nwLat = Mercator.latitudeOf(22204.0, 16)
        val nwLon = Mercator.longitudeOf(35396.0, 16)
        val (x0, y0) = c.pixelOf(nwLat, nwLon, w, h)
        assertTrue(abs(x0) < 1e-3 && abs(y0) < 1e-3)
        // ...and the far corner is the far corner.
        val seLat = Mercator.latitudeOf(22206.0, 16)
        val seLon = Mercator.longitudeOf(35398.0, 16)
        val (x1, y1) = c.pixelOf(seLat, seLon, w, h)
        assertTrue(abs(x1 - w) < 1e-3 && abs(y1 - h) < 1e-3)
    }

    @Test
    fun theBandsNoFixPointIsNotPartOfTheBox() {
        // 0,0 is what the band writes before it has a fix. Framed literally it drags every map to
        // the Gulf of Guinea, which is how a walk ends up as a world map with a dot on it.
        val box = MapCutouts.Box.of(
            listOf(50.075 to 14.437, 0.0 to 0.0, 50.076 to 14.438),
        )
        assertNotNull(box)
        assertTrue(box!!.south > 50.0)
        assertNull("no usable points at all is a real answer", MapCutouts.Box.of(emptyList()))
    }
}
