package com.opentasker.core.huawei.maps

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator, the projection every slippy map on earth already agrees on.
 *
 * Chosen because it makes a cached map cutout addressable by NAME. A cutout is a block of standard
 * tiles at a standard zoom, so its identifier — zoom, tile x, tile y, size — is also, exactly, its
 * geographic transform. There is no separate bounding box to store, nothing to drift out of step
 * with the pixels, and two walks over the same streets land on the same cutout by construction
 * rather than by a similarity test that would have to be tuned.
 *
 * The alternative was to keep each walk's own framing, which is what 地図 does today: it picks a
 * view to suit one route, so the image it returns fits that walk and nothing else. Two walks down
 * the same street get two 2.5 MB pictures of the same street.
 */
object Mercator {

    /** Standard slippy-map tile edge. Every tile server and every renderer uses this. */
    const val TILE_PX = 256

    /** How far a tile spans on the ground, at the equator, for picking a zoom. */
    fun tileMetres(zoom: Int, latitudeDeg: Double): Double =
        EARTH_CIRCUMFERENCE_M * cos(latitudeDeg * PI / 180.0) / (1 shl zoom)

    /** Fractional tile x for a longitude — the whole part is the tile, the rest is the offset in it. */
    fun tileX(longitudeDeg: Double, zoom: Int): Double =
        (longitudeDeg + 180.0) / 360.0 * (1 shl zoom)

    /**
     * Fractional tile y for a latitude.
     *
     * `asinh(tan(φ))` rather than the more familiar `ln(tan + sec)`: the two are the same function
     * and this one keeps its precision near the equator, where the other subtracts two nearly equal
     * quantities.
     */
    fun tileY(latitudeDeg: Double, zoom: Int): Double {
        val lat = latitudeDeg.coerceIn(-MAX_LAT_DEG, MAX_LAT_DEG) * PI / 180.0
        return (1.0 - asinh(tan(lat)) / PI) / 2.0 * (1 shl zoom)
    }

    /** Longitude of a tile's left edge. */
    fun longitudeOf(tileX: Double, zoom: Int): Double = tileX / (1 shl zoom) * 360.0 - 180.0

    /** Latitude of a tile's top edge. */
    fun latitudeOf(tileY: Double, zoom: Int): Double {
        val n = PI * (1.0 - 2.0 * tileY / (1 shl zoom))
        return atan(sinh(n)) * 180.0 / PI
    }

    /** The tile a coordinate falls in, floored — the grid cell, not the offset within it. */
    fun tileOf(latitudeDeg: Double, longitudeDeg: Double, zoom: Int): Pair<Int, Int> =
        floor(tileX(longitudeDeg, zoom)).toInt() to floor(tileY(latitudeDeg, zoom)).toInt()

    /**
     * The zoom at which [spanMetres] fits inside [pixels], never finer than [maxZoom].
     *
     * Picked from the ground distance rather than from a degree span because a degree of longitude
     * is a different distance at every latitude, and a walk framed by degrees would be drawn at a
     * different scale in Prague than in Tokyo.
     */
    fun zoomFor(spanMetres: Double, pixels: Int, latitudeDeg: Double, maxZoom: Int = 17): Int {
        if (spanMetres <= 0.0) return maxZoom
        // metres-per-pixel at zoom z is circumference·cos(lat) / (256 · 2^z); solve for z.
        val target = EARTH_CIRCUMFERENCE_M * cos(latitudeDeg * PI / 180.0) * pixels /
            (TILE_PX * spanMetres)
        val z = floor(ln(target) / ln(2.0)).toInt()
        return z.coerceIn(1, maxZoom)
    }

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    /** Web Mercator is undefined at the poles and every implementation clips here. */
    const val MAX_LAT_DEG = 85.051_128_78
}
