package com.opentasker.core.huawei.maps

import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
        val c = MapCutouts.Cutout(16, 35396, 22204, 3, 2)
        assertEquals("z16_x35396_y22204_3x2.png", c.id)
        // The key round-trips whole: it IS the transform, which is why it can be a database key
        // and why a picture can never be matched to the wrong row.
        assertEquals(c, MapCutouts.parse(c.id))
    }

    @Test
    fun aWalkFindsTheCutoutItFitsInAndNotOneItDoesNot() {
        val walk = MapCutouts.Box(50.070, 14.430, 50.080, 14.445)
        // Nothing held: this is somewhere new, and saying so is the whole trigger.
        assertNull(MapCutouts.cover(emptyList(), walk))

        val wanted = MapCutouts.needed(walk, preferredZoom = 16)
        val held = listOf(wanted.id)
        val found = MapCutouts.cover(held, walk)
        assertNotNull("the cutout we just cut must cover the walk that asked for it", found)
        assertEquals(wanted.id, found!!.id)

        // Somewhere else entirely is not covered by it.
        assertNull(MapCutouts.cover(held, MapCutouts.Box(35.68, 139.76, 35.69, 139.77)))
    }

    @Test
    fun theMarginMakesOneCutoutServeTheNextWalkToo() {
        val first = MapCutouts.Box(50.0740, 14.4360, 50.0760, 14.4400)
        val held = listOf(MapCutouts.needed(first, 16).id)
        // A second walk from the same door, a few streets further — the point of the margin is that
        // this does NOT send us back to 地図.
        val second = MapCutouts.Box(50.0735, 14.4340, 50.0775, 14.4425)
        assertNotNull(MapCutouts.cover(held, second))
    }

    @Test
    fun aSprawlingBoxIsAnsweredAtACoarserZoomRatherThanRefused() {
        // Prague to Brno: no cutout at z16 could hold this without thousands of tiles.
        val huge = MapCutouts.Box(49.19, 14.43, 50.08, 16.61)
        val c = MapCutouts.needed(huge, preferredZoom = 16)
        assertTrue("zoom must have been stepped down", c.zoom < 16)
        assertTrue("and the block kept small", c.tilesW <= MapCutouts.MAX_TILES)
        assertTrue(c.tilesH <= MapCutouts.MAX_TILES)
        assertTrue("it still has to cover the walk", c.covers(huge))
    }

    @Test
    fun theFrameCarriesTheCellsShapeWhateverTheWalksIs() {
        // A walk four times as tall as it is wide — the shape that broke this: a river path, drawn
        // in a 4:3 cell, on a map cut to the track and therefore a third too narrow to fill it.
        val tall = MapCutouts.Box(50.0700, 14.4370, 50.0800, 14.4400)
        val f = MapCutouts.frame(tall)
        // Measured in projected coordinates, because that is what the renderer scales in — degrees
        // of longitude and latitude are different distances and would not answer the question.
        val z = 21
        val w = Mercator.tileX(f.east, z) - Mercator.tileX(f.west, z)
        val h = Mercator.tileY(f.south, z) - Mercator.tileY(f.north, z)
        assertEquals("the frame is the cell's shape", MapCutouts.CELL_ASPECT, w / h, 1e-6)
        assertTrue("and it contains the walk it was framed for", f.south < tall.south)
        assertTrue(f.north > tall.north)
        assertTrue(f.west < tall.west)
        assertTrue(f.east > tall.east)

        // The wide case takes the same route through the other branch of the aspect step.
        val wide = MapCutouts.Box(50.0755, 14.4300, 50.0770, 14.4500)
        val g = MapCutouts.frame(wide)
        val gw = Mercator.tileX(g.east, z) - Mercator.tileX(g.west, z)
        val gh = Mercator.tileY(g.south, z) - Mercator.tileY(g.north, z)
        assertEquals(MapCutouts.CELL_ASPECT, gw / gh, 1e-6)
        assertTrue(g.north > wide.north && g.south < wide.south)
    }

    @Test
    fun aWalkThatStandsStillStillGetsAPlace() {
        // One fix, repeated: the box has no span at all, and the aspect step would divide by zero.
        val still = MapCutouts.Box(50.0755, 14.4378, 50.0755, 14.4378)
        val f = MapCutouts.frame(still)
        assertTrue("a frame with real extent", f.north > f.south && f.east > f.west)
        val c = MapCutouts.needed(still, preferredZoom = 17)
        assertTrue("and a cutout that contains it", c.covers(f))
    }

    @Test
    fun whatIsCutIsWhatIsThenAcceptedBackAgain() {
        // The loop that must never exist: needed() answering with a cutout that cover() rejects
        // would send the window back to 地図 for the same area forever. Checked across shapes,
        // because the aspect step is what makes the two disagree if only one of them frames.
        val shapes = listOf(
            MapCutouts.Box(50.0700, 14.4370, 50.0800, 14.4400),   // tall
            MapCutouts.Box(50.0755, 14.4300, 50.0770, 14.4500),   // wide
            MapCutouts.Box(50.0740, 14.4360, 50.0760, 14.4400),   // square-ish
            MapCutouts.Box(49.1900, 14.4300, 50.0800, 16.6100),   // sprawling, zoom stepped down
        )
        for (box in shapes) {
            val wanted = MapCutouts.needed(box, WalkTrack.zoomFor(box))
            assertNotNull(
                "a cutout just cut for $box must satisfy the cover test that asked for it",
                MapCutouts.cover(listOf(wanted.id), box),
            )
        }
    }

    @Test
    fun aMapThatOnlyTouchesTheWalkIsNoLongerGoodEnough() {
        // Exactly the fault 白い熊 saw: a cutout that contains the track and nothing more. It used
        // to be accepted — edges counted as covered — and the walk was then drawn on a map with no
        // street beyond its own ends.
        val walk = MapCutouts.Box(50.0700, 14.4370, 50.0800, 14.4400)
        // The tiles the track itself spans, snapped outward and not one tile further — which is
        // what an area's cutout decays into once a later walk grows out to its rim.
        val z = 16
        val x0 = floor(Mercator.tileX(walk.west, z)).toInt()
        val x1 = ceil(Mercator.tileX(walk.east, z)).toInt()
        val y0 = floor(Mercator.tileY(walk.north, z)).toInt()
        val y1 = ceil(Mercator.tileY(walk.south, z)).toInt()
        val tight = MapCutouts.Cutout(z, x0, y0, x1 - x0, y1 - y0)
        assertTrue("the fixture has to be a map that really does contain the track", tight.covers(walk))
        assertNull("but containing the track is no longer the question", MapCutouts.cover(listOf(tight.id), walk))
        // And the roomy one cut for it is accepted, so this is a raised bar and not a closed door.
        val proper = MapCutouts.needed(walk, 16)
        assertNotNull(MapCutouts.cover(listOf(proper.id, tight.id), walk))
    }

    @Test
    fun theFinestMapWins() {
        // Two cutouts, both framing the walk. The old rule took the smallest by area, which is the
        // coarser one here; what is wanted is the one with the most detail.
        val walk = MapCutouts.Box(50.0740, 14.4360, 50.0760, 14.4400)
        val fine = MapCutouts.needed(walk, 17)
        val coarse = MapCutouts.needed(walk, 14)
        assertTrue("the fixture needs two different zooms", fine.zoom > coarse.zoom)
        val chosen = MapCutouts.cover(listOf(coarse.id, fine.id), walk)
        assertEquals(fine.id, chosen!!.id)
    }

    @Test
    fun pixelsLandWhereTheyShouldInsideTheImage() {
        val c = MapCutouts.Cutout(16, 35396, 22204, 2, 2)
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
