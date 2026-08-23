package com.opentasker.core.huawei

import kotlin.math.PI
import kotlin.math.cos

/**
 * Decoding a workout's `<n>_gps.bin` into coordinates.
 *
 * ## The shape of the file
 *
 * Thirty-two bytes nobody has ever described, then a flag byte, an absolute start time, and the
 * starting longitude and latitude as doubles **in degrees**. After that, fixed-size records that
 * carry the walk as CUMULATIVE DISPLACEMENT IN METRES from that starting point — not as coordinates.
 * Reconstructing the track means adding those metres up and projecting them back onto the globe.
 *
 * **Everything inside this file is LITTLE-endian**, which is the opposite of the TLV layer that
 * carried it. That is not a quirk worth being clever about; it is simply a different format that
 * happens to arrive over the same wire, and mixing the two up produces coordinates in the ocean.
 *
 * ## Two things we do not know, stated rather than hidden
 *
 * **The Earth radius is contested.** Turning metres back into degrees needs one, and the only public
 * source contradicts itself: it uses 6378245 (Krasovsky 1940) while recording that the value its own
 * reverse engineering produced was 6383807, with the author unsure which is right. They differ by
 * 0.087% — about 0.9 m per kilometre walked from the start point. So [EARTH_RADIUS_M] is a
 * calibration constant, not a fact, and [decode] takes it as a parameter so a real track can settle
 * it. A five-kilometre walk that closes a loop back to its start is the experiment.
 *
 * **The datum is unknown.** Huawei's firmware handles both WGS-84 and GCJ-02 — China's deliberately
 * offset datum — and carries separate fields for them elsewhere in the protocol. Which one a
 * recorded track is stored in is not documented anywhere. The check is unmistakable on the first
 * real walk: WGS-84 lands on the road, GCJ-02 lands 100–700 m off it, consistently and in one
 * direction. Until that walk exists, this decodes as WGS-84 and does not pretend to know.
 *
 * Neither unknown is a reason to wait. Both are answered by one walk, and everything else about the
 * format is ordinary.
 */
object HuaweiGpsTrack {

    /**
     * The radius used to turn cumulative metres back into degrees.
     *
     * Krasovsky 1940, the value the only working parser settles on. See the class note: this is a
     * calibration parameter and the alternative candidate is 6383807.0.
     */
    const val EARTH_RADIUS_M = 6_378_245.0

    /** The other candidate, kept named so a calibration run has something to compare against. */
    const val EARTH_RADIUS_ALT_M = 6_383_807.0

    /** One fix. [altitudeRaw] is in the file's own unit, which is not known to be metres. */
    data class Point(
        val epochSeconds: Long,
        val latitude: Double,
        val longitude: Double,
        val altitudeRaw: Int? = null,
        val paused: Boolean = false,
    )

    data class Track(
        val startSeconds: Long,
        val points: List<Point>,
        /** True when the file carried per-point altitude at all. */
        val hasAltitude: Boolean,
    ) {
        val isEmpty: Boolean get() = points.isEmpty()
    }

    private const val HEADER_SKIP = 32
    private const val FLAGS = HEADER_SKIP
    private const val START_TIME = HEADER_SKIP + 1
    private const val START_LON = HEADER_SKIP + 5
    private const val START_LAT = HEADER_SKIP + 13
    private const val PAD_AFTER_START = 9

    /** Without altitude the record is 15 bytes; with it, 19. */
    private const val RECORD = 15
    private const val RECORD_WITH_ALTITUDE = 19

    /**
     * Decode a track, or null when the bytes are not one.
     *
     * Refuses rather than half-succeeds. A partially understood track is a walk that never happened,
     * and once it is a list of coordinates nothing downstream can tell it from a real one — the same
     * reasoning the sleep parser follows.
     */
    fun decode(bytes: ByteArray, earthRadiusM: Double = EARTH_RADIUS_M): Track? {
        if (bytes.size < START_LAT + 8) return null

        val flags = bytes[FLAGS].toInt() and 0xFF
        val hasAltitude = (flags and 0x03) == 0x03

        val startTime = le32(bytes, START_TIME).toLong()
        if (startTime !in PLAUSIBLE_EPOCH) return null

        val lon0 = leDouble(bytes, START_LON)
        val lat0 = leDouble(bytes, START_LAT)
        if (lat0 !in -90.0..90.0 || lon0 !in -180.0..180.0) return null
        // 0,0 is in the Gulf of Guinea and is what an unfilled header looks like. A walk does not
        // start there, and treating it as one would draw a line from Africa to wherever the deltas
        // lead.
        if (lat0 == 0.0 && lon0 == 0.0) return null

        var offset = START_LAT + 8 + (if (hasAltitude) 8 else 0) + PAD_AFTER_START
        val stride = if (hasAltitude) RECORD_WITH_ALTITUDE else RECORD

        val rad = PI / 180.0
        val lat0Rad = lat0 * rad
        val lon0Rad = lon0 * rad
        val cosLat = cos(lat0Rad)
        // A track that began at a pole would divide by ~0 turning metres into longitude. Not a real
        // case for 白い熊, and cheaper to refuse than to emit coordinates that run off the map.
        if (kotlin.math.abs(cosLat) < 1e-9) return null

        var time = startTime
        var northM = 0.0
        var eastM = 0.0
        val points = ArrayList<Point>()

        while (offset + stride <= bytes.size) {
            val dt = le16(bytes, offset)
            val dLon = leFloat(bytes, offset + 4)
            val dLat = leFloat(bytes, offset + 8)
            val paused = (bytes[offset + 14].toInt() and 0xFF) == 1
            val altitude = if (hasAltitude) le16(bytes, offset + 15) else null
            offset += stride

            // A delta that is not finite would poison every later point, since these accumulate.
            if (!dLon.isFinite() || !dLat.isFinite()) break

            time += dt.toLong()
            northM += dLat
            eastM += dLon

            val lat = (northM / earthRadiusM + lat0Rad) / rad
            val lon = (eastM / earthRadiusM / cosLat + lon0Rad) / rad
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) break

            points += Point(time, lat, lon, altitude, paused)
        }

        return Track(startTime, points, hasAltitude)
    }

    /**
     * The track as GPX.
     *
     * GPX rather than our own shape because the point of having it is handing it somewhere else —
     * 白い熊 地図, or any map at all — and a format every tool already reads costs nothing here.
     * Paused points are still written: the pause is a fact about the walk, and dropping them would
     * silently straighten a route through wherever 白い熊 stopped.
     */
    fun toGpx(track: Track, name: String): String {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val body = track.points.joinToString("\n") { p ->
            val ele = p.altitudeRaw?.let { "<ele>$it</ele>" } ?: ""
            "      <trkpt lat=\"%.7f\" lon=\"%.7f\">$ele<time>%s</time></trkpt>"
                .format(java.util.Locale.US, p.latitude, p.longitude, iso.format(p.epochSeconds * 1000L))
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="白い熊 自由作業盤" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>${name.replace("&", "&amp;").replace("<", "&lt;")}</name>
    <trkseg>
$body
    </trkseg>
  </trk>
</gpx>
"""
    }

    private val PLAUSIBLE_EPOCH = 1_600_000_000L..2_500_000_000L

    private fun le16(b: ByteArray, i: Int): Int {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun leFloat(b: ByteArray, i: Int): Float = Float.fromBits(le32(b, i))

    private fun leDouble(b: ByteArray, i: Int): Double {
        var bits = 0L
        for (n in 7 downTo 0) bits = (bits shl 8) or (b[i + n].toLong() and 0xFF)
        return Double.fromBits(bits)
    }
}
