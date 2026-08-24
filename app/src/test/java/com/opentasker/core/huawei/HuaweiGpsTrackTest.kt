package com.opentasker.core.huawei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The track decoder, against files this test builds itself.
 *
 * These fixtures were written before any real track existed, and the first real one — 白い熊's walk
 * of 2026-08-23 — corrected them: the header is 33 bytes, not the 32 every published description
 * gives. That error did not throw. It shifted every field by a byte and produced a start time in
 * 2043 and a starting position in the ocean, which the decoder happened to refuse. The arithmetic is
 * what caught it: at 32 the payload does not divide by the record size, and at 33 it divides exactly.
 *
 * What these tests prove is everything downstream of that layout: the little-endian reads, the
 * cumulative metre accumulation, the projection back to degrees, the altitude stride, the refusals.
 */
class HuaweiGpsTrackTest {

    private val startTime = 1_787_400_000L      // 2026-08-22, in the plausible window
    private val lat0 = 45.000000                // a synthetic origin, not a real place
    private val lon0 = -30.000000

    /** Little-endian, like everything inside the file and unlike everything outside it. */
    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    private fun leFloat(v: Float) = le32(v.toRawBits())

    private fun leDouble(v: Double): ByteArray {
        val bits = v.toRawBits()
        return ByteArray(8) { ((bits shr (8 * it)) and 0xFF).toByte() }
    }

    /**
     * Build a track file.
     *
     * [deltas] are (seconds, east metres, north metres, paused) — the file stores displacement, not
     * position, which is the single most surprising thing about the format.
     */
    private fun file(
        deltas: List<Quad>,
        withAltitude: Boolean = false,
        start: Long = startTime,
        lat: Double = lat0,
        lon: Double = lon0,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // 33, not 32 — measured against 白い熊's own first walk; see HuaweiGpsTrack.
        out.write(ByteArray(33) { 0x7F })                    // the header nobody has described
        out.write(if (withAltitude) 0x03 else 0x00)
        out.write(le32(start.toInt()))
        out.write(leDouble(lon))
        out.write(leDouble(lat))
        if (withAltitude) out.write(leDouble(215.0))
        out.write(ByteArray(9))                              // padding, contents unknown
        for ((dt, east, north, paused) in deltas) {
            out.write(le16(dt))
            out.write(le16(0))                               // bearing, unread
            out.write(leFloat(east))
            out.write(leFloat(north))
            out.write(0)                                     // accuracy, unread
            out.write(0)                                     // velocity, unread
            out.write(if (paused) 1 else 0)
            if (withAltitude) {
                out.write(le16(215))
                out.write(le16(0))
            }
        }
        return out.toByteArray()
    }

    private data class Quad(val dt: Int, val east: Float, val north: Float, val paused: Boolean = false)

    @Test
    fun `a straight walk north lands where the arithmetic says`() {
        // 100 metres north, three times. The deltas are cumulative, so the third point is 300 m out.
        val track = HuaweiGpsTrack.decode(
            file(listOf(Quad(1, 0f, 100f), Quad(1, 0f, 100f), Quad(1, 0f, 100f))),
        )
        assertNotNull(track)
        assertEquals(3, track!!.points.size)

        val perMetre = 1.0 / HuaweiGpsTrack.EARTH_RADIUS_M / (PI / 180.0)
        assertEquals(lat0 + 100 * perMetre, track.points[0].latitude, 1e-9)
        assertEquals(lat0 + 300 * perMetre, track.points[2].latitude, 1e-9)
        // Nothing moved east, so longitude must not drift at all.
        assertEquals(lon0, track.points[2].longitude, 1e-12)
    }

    @Test
    fun `eastward movement is scaled by the cosine of the starting latitude`() {
        val track = HuaweiGpsTrack.decode(file(listOf(Quad(1, 1000f, 0f))))!!
        val rad = PI / 180.0
        val expected = lon0 + (1000.0 / HuaweiGpsTrack.EARTH_RADIUS_M / cos(lat0 * rad)) / rad
        assertEquals(expected, track.points[0].longitude, 1e-9)
        // At 50° north a metre east is worth appreciably more longitude than a metre north is worth
        // latitude. If this ever reads as equal, the cosine has been dropped.
        val northOnly = HuaweiGpsTrack.decode(file(listOf(Quad(1, 0f, 1000f))))!!
        assertTrue(
            "east must move further in degrees than north at this latitude",
            abs(track.points[0].longitude - lon0) > abs(northOnly.points[0].latitude - lat0),
        )
    }

    @Test
    fun `time accumulates from the header, not from each record alone`() {
        val track = HuaweiGpsTrack.decode(file(listOf(Quad(5, 1f, 1f), Quad(7, 1f, 1f))))!!
        assertEquals(startTime + 5, track.points[0].epochSeconds)
        assertEquals(startTime + 12, track.points[1].epochSeconds)
    }

    @Test
    fun `the pause flag survives, and the paused point is still a point`() {
        val track = HuaweiGpsTrack.decode(
            file(listOf(Quad(1, 1f, 1f), Quad(60, 0f, 0f, paused = true), Quad(1, 1f, 1f))),
        )!!
        // Dropping paused points would silently straighten the route through wherever 白い熊 stopped.
        assertEquals(3, track.points.size)
        assertTrue(track.points[1].paused)
        assertTrue(!track.points[0].paused)
    }

    @Test
    fun `the altitude flag changes the record stride, not just the fields`() {
        val deltas = listOf(Quad(1, 10f, 10f), Quad(1, 10f, 10f), Quad(1, 10f, 10f))
        val plain = HuaweiGpsTrack.decode(file(deltas))!!
        val tall = HuaweiGpsTrack.decode(file(deltas, withAltitude = true))!!
        assertEquals(3, plain.points.size)
        assertEquals(3, tall.points.size)
        assertTrue(tall.hasAltitude)
        assertTrue(!plain.hasAltitude)
        assertEquals(215, tall.points[0].altitudeRaw)
        assertNull(plain.points[0].altitudeRaw)
        // Reading a 19-byte file at a 15-byte stride would still "work" and produce nonsense, so the
        // coordinates have to agree between the two.
        assertEquals(plain.points[2].latitude, tall.points[2].latitude, 1e-12)
    }

    @Test
    fun `the earth radius is a parameter, because its value is contested`() {
        val deltas = listOf(Quad(1, 0f, 5000f))
        val krasovsky = HuaweiGpsTrack.decode(deltas.let { file(it) })!!
        val alt = HuaweiGpsTrack.decode(
            file(deltas), earthRadiusM = HuaweiGpsTrack.EARTH_RADIUS_ALT_M,
        )!!
        assertTrue(
            "the two candidate radii must actually differ, or calibration is pointless",
            abs(krasovsky.points[0].latitude - alt.points[0].latitude) > 1e-9,
        )
    }

    @Test
    fun `a file that is not a track is refused rather than half-read`() {
        assertNull("too short", HuaweiGpsTrack.decode(ByteArray(20)))
        assertNull("all zeroes: no start time, no position", HuaweiGpsTrack.decode(ByteArray(200)))
        assertNull(
            "a start in the Gulf of Guinea is an unfilled header, not a walk",
            HuaweiGpsTrack.decode(file(listOf(Quad(1, 1f, 1f)), lat = 0.0, lon = 0.0)),
        )
        assertNull(
            "a start time outside the plausible window",
            HuaweiGpsTrack.decode(file(listOf(Quad(1, 1f, 1f)), start = 100L)),
        )
    }

    @Test
    fun `a truncated tail yields the points that were whole`() {
        val whole = file(listOf(Quad(1, 1f, 1f), Quad(1, 1f, 1f), Quad(1, 1f, 1f)))
        val cut = whole.copyOfRange(0, whole.size - 7)     // half of the last record
        val track = HuaweiGpsTrack.decode(cut)!!
        assertEquals(2, track.points.size)
    }

    @Test
    fun `the GPX carries every point, with coordinates that round-trip`() {
        val track = HuaweiGpsTrack.decode(file(listOf(Quad(1, 100f, 100f), Quad(1, 100f, 100f))))!!
        val gpx = HuaweiGpsTrack.toGpx(track, "walk 7")
        assertEquals(2, Regex("<trkpt").findAll(gpx).count())
        assertTrue(gpx.contains("<name>walk 7</name>"))
        assertTrue("must be parseable as XML", gpx.trimStart().startsWith("<?xml"))
        // Seven decimals is ~1 cm — the track's own precision is far coarser, so nothing is lost.
        assertTrue(gpx.contains("lat=\"%.7f\"".format(java.util.Locale.US, track.points[0].latitude)))
    }
}
