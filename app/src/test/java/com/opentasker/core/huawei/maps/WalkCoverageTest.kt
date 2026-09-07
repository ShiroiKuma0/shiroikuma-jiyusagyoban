package com.opentasker.core.huawei.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much of a walk the band recorded — the measurement that would have caught 2026-09-06.
 *
 * The fixtures are the real numbers off 白い熊's phone that day, read out of the band's own files:
 * eleven walks whose route matched the summary and four that did not. The point of using them rather
 * than invented ones is that the threshold has to separate the two groups as they actually fell, and
 * they fall closer together than one would guess — a healthy walk's polyline runs LONGER than the
 * band's smoothed distance, by up to 15 %.
 */
class WalkCoverageTest {

    @Test
    fun `the haversine agrees with a distance that can be checked by hand`() {
        // One degree of latitude is 111.2 km, everywhere. A projection error shows up here as a
        // percentage, and an Earth-radius mistake as a constant factor.
        val oneDegreeNorth = WalkTrack.metres(listOf(50.0 to 14.0, 51.0 to 14.0))
        assertEquals(111_195.0, oneDegreeNorth, 200.0)
        // A degree of longitude is that times cos(latitude) — the check that the cosine is applied
        // at all, which a flat-Earth sum would pass at the equator and fail here.
        val oneDegreeEast = WalkTrack.metres(listOf(50.0 to 14.0, 50.0 to 15.0))
        assertEquals(111_195.0 * Math.cos(Math.toRadians(50.0)), oneDegreeEast, 200.0)
        assertEquals("a single point has no length", 0.0, WalkTrack.metres(listOf(50.0 to 14.0)), 0.0)
        assertEquals(0.0, WalkTrack.metres(emptyList()), 0.0)
    }

    @Test
    fun `the walks that recorded properly are not called partial`() {
        // route metres, band metres — measured, 2026-08-23 through 2026-09-06.
        val healthy = listOf(
            2340 to 2270, 1555 to 1430, 2056 to 1830, 3198 to 2970, 1890 to 1730,
            2806 to 2660, 2795 to 2440, 2343 to 2190, 2834 to 2680, 2311 to 2240, 2903 to 2750,
        )
        for ((route, band) in healthy) {
            val c = WalkTrack.Coverage(firstFixDelaySeconds = 5, routeMetres = route, bandMetres = band)
            assertFalse(
                "$route m of $band m (${"%.2f".format(c.fraction)}) is a whole walk, not a partial one",
                c.partial,
            )
        }
    }

    @Test
    fun `the four that did not are`() {
        // The same day's failures, worst last: a GPS that fixed late, or never really fixed.
        val broken = listOf(1939 to 2330, 692 to 1690, 399 to 2860, 1249 to 2550)
        for ((route, band) in broken) {
            val c = WalkTrack.Coverage(firstFixDelaySeconds = 1135, routeMetres = route, bandMetres = band)
            assertTrue("$route m of $band m is not the whole walk", c.partial)
            assertEquals(band - route, c.missingMetres)
        }
    }

    @Test
    fun `the first fix delay is what the band's own file says, not what the walk claims`() {
        // 2026-09-06 14:32: the workout began at 14:32:13 and the first fix landed at 14:51:09.
        val c = WalkTrack.coverage(
            points = listOf(50.0 to 14.0, 50.0 to 14.0),
            trackStartSeconds = 1788699068,
            workoutStartSeconds = 1788697933,
            bandMetres = 2550,
        )
        assertEquals(1135L, c.firstFixDelaySeconds)
        // A track whose header predates the workout is not a negative wait; it is a band that
        // started recording first, and the honest reading of that is zero.
        val early = WalkTrack.coverage(
            points = emptyList(), trackStartSeconds = 100, workoutStartSeconds = 200, bandMetres = 0,
        )
        assertEquals(0L, early.firstFixDelaySeconds)
        assertFalse("a walk the band gave no distance for cannot be judged", early.partial)
    }
}
