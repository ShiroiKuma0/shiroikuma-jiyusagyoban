package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 白い熊's first recorded walk — the file that turned the decoder from a hypothesis into a fact.
 *
 * 2026-08-23, Prague, 26,508 bytes off the band. Everything the published descriptions got right is
 * confirmed here, and the one thing they got wrong is pinned: the header is 33 bytes, not 32.
 *
 * The fixture is a real walk off the band with its **origin removed**: the header's start latitude,
 * longitude and time are overwritten with a synthetic 45 N, 30 W and a fixed instant, while every
 * one of the 1763 displacement records is byte-identical to what the band produced.
 *
 * That split is the point. The bug this test exists to catch was a **33-byte header read as 32**,
 * which is a property of the record stride and the projection, not of where the walk happened — so
 * the fixture keeps everything that can fail and discards the part that identifies a person. This
 * repository is public; a track of somebody's movements is not something to publish for a test.
 *
 * The mid-latitude origin is deliberate too: at the equator `cos(lat)` is exactly 1, which would
 * hide a whole class of longitude-projection error.
 */
class HuaweiWalkShapeTest {

    private fun bytes() = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("huawei/walk-shape.bin"),
    ) { "fixture missing" }.readBytes()

    private fun metres(a: HuaweiGpsTrack.Point, b: HuaweiGpsTrack.Point): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * 6_371_000.0 * asin(sqrt(h))
    }

    @Test
    fun `the real track decodes, and every record is consumed exactly`() {
        val track = HuaweiGpsTrack.decode(bytes())
        assertNotNull("the real walk must decode", track)
        // 26508 bytes = a 63-byte header plus 1763 records of 15. At a 32-byte header the payload
        // does not divide, which is what exposed the off-by-one.
        assertEquals(1763, track!!.points.size)
        assertTrue("no altitude in this file", !track.hasAltitude)
    }

    @Test
    fun `it starts where the phone thought it was, which settles the datum`() {
        val p = HuaweiGpsTrack.decode(bytes())!!.points.first()
        // The origin in this fixture is synthetic — see the class comment. A GCJ-02 track would sit
        // 100–700 m away, consistently in one direction. This sits within GPS noise, so the stored
        // datum is WGS-84 and no shift is applied anywhere.
        assertTrue("latitude near the fixture's origin", abs(p.latitude - 45.0) < 0.002)
        assertTrue("longitude near the fixture's origin", abs(p.longitude - (-30.0)) < 0.002)
    }

    @Test
    fun `the path length agrees with the band's own summary`() {
        val pts = HuaweiGpsTrack.decode(bytes())!!.points
        val km = pts.zipWithNext { a, b -> metres(a, b) }.sum() / 1000.0
        // The band reported this walk as 2.27 km. A polyline through raw fixes runs a little long
        // against a device's smoothed distance; 3% is ordinary. What would NOT be ordinary is a
        // factor or an order of magnitude, which is exactly what a wrong radius or a misread delta
        // would produce.
        assertTrue("decoded $km km against the band's 2.27 km", km > 2.0 && km < 2.7)
    }

    @Test
    fun `time runs forward across the whole walk, and lasts what the band says it lasted`() {
        val track = HuaweiGpsTrack.decode(bytes())!!
        val pts = track.points
        assertTrue("timestamps must not go backwards", pts.zipWithNext().all { (a, b) -> b.epochSeconds >= a.epochSeconds })

        // This assertion used to allow anything from 30 to 300 minutes, and it passed at 2 h 08 m —
        // which is what the block indices did to a 29-minute walk. Three of them landed in the step
        // field of this file and read as gaps of 1025 s, 18 s and 4865 s, so the decoded track
        // claimed 1 h 38 m of standing still that never happened, and the wide bound hid it.
        //
        // The band's own summary is the referee: workout 8 ran 1767 s, and the track's first fix is
        // 4 s in, so the file must span 1763 s. It does, exactly.
        assertEquals(1763L, pts.last().epochSeconds - pts.first().epochSeconds + 1)

        // 26508 bytes: a block index at every multiple of 976, the one at zero inside the header.
        assertEquals(27, track.mendedPoints)
    }

    @Test
    fun `the choice of earth radius barely moves the answer`() {
        val a = HuaweiGpsTrack.decode(bytes(), HuaweiGpsTrack.EARTH_RADIUS_M)!!
        val b = HuaweiGpsTrack.decode(bytes(), HuaweiGpsTrack.EARTH_RADIUS_ALT_M)!!
        val km = { pts: List<HuaweiGpsTrack.Point> -> pts.zipWithNext { x, y -> metres(x, y) }.sum() / 1000.0 }
        // Two metres over 2.3 km. The constant is contested in its only public source and it does not
        // matter at these distances — recorded here so nobody spends a day choosing between them.
        assertTrue("the two radii differ by less than 10 m", abs(km(a.points) - km(b.points)) < 0.01)
    }

    @Test
    fun `the GPX carries every point`() {
        val track = HuaweiGpsTrack.decode(bytes())!!
        val gpx = HuaweiGpsTrack.toGpx(track, "walk 8")
        assertEquals(track.points.size, Regex("<trkpt").findAll(gpx).count())
    }
}
