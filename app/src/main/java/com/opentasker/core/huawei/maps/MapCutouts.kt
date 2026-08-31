package com.opentasker.core.huawei.maps

import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The shared map cutouts every walk is drawn over.
 *
 * ## The problem this replaces
 *
 * Every walk was handed to 白い熊 地図, which rendered the route onto its own framing and returned
 * a picture. Two walks down the same street produced two 2.5 MB pictures of that street, each
 * useless to the other, and every one of them also became an entry in 地図's own library. A walk is
 * about 120 kB of track; the map was twenty times the walk.
 *
 * ## What replaces it
 *
 * A cutout is a block of standard Web Mercator tiles: `z/x/y` plus a size in tiles. That identifier
 * IS the transform — given it, any coordinate projects to a pixel with no stored bounding box to go
 * stale — so the same streets always resolve to the same cutout, and a walk keeps only its track.
 * The route is drawn over the cutout when it is looked at.
 *
 * A handful of cutouts covers everywhere 白い熊 actually walks. Somewhere new is detected here, not
 * guessed at: [cover] returns null when nothing on disk contains the track, and that is the signal
 * to ask 地図 for one base image — once, for the area, not for the walk.
 */
object MapCutouts {

    /** The sub-folder of the walk root. Leading underscore so it sorts away from `walk-*`. */
    const val DIR = "_maps"

    /**
     * How much bigger than the track a cutout is cut.
     *
     * A cutout that only just contains a route is useless to the next walk that goes one street
     * further, and re-fetching then would defeat the point. A margin of whole tiles is cheap —
     * tiles at these zooms are a few hundred metres — and it is what makes one cutout serve a
     * neighbourhood rather than a route.
     */
    const val MARGIN_TILES = 1

    /** Biggest block we will ask for, so a stray point on the far side of the country cannot ask
     * for a picture of the country. Beyond this the zoom is reduced instead. */
    const val MAX_TILES = 6

    /**
     * One cached base image.
     *
     * [tilesW] and [tilesH] are in tiles; the PNG is expected to be exactly `tilesW × TILE_PX` by
     * `tilesH × TILE_PX`. Nothing here trusts that blindly — [pixelOf] takes the real bitmap size —
     * but it is what a renderer is asked for.
     */
    data class Cutout(
        val zoom: Int,
        val tileX: Int,
        val tileY: Int,
        val tilesW: Int,
        val tilesH: Int,
        val file: File,
    ) {
        /** The file name, which carries the whole transform and is therefore the identity. */
        val id: String get() = name(zoom, tileX, tileY, tilesW, tilesH)

        /** Does this cover the box, with no margin demanded? Edges count as covered. */
        fun covers(box: Box): Boolean {
            val x0 = Mercator.tileX(box.west, zoom)
            val x1 = Mercator.tileX(box.east, zoom)
            val y0 = Mercator.tileY(box.north, zoom)
            val y1 = Mercator.tileY(box.south, zoom)
            return x0 >= tileX && x1 <= tileX + tilesW && y0 >= tileY && y1 <= tileY + tilesH
        }

        /**
         * Where a coordinate lands in an image [widthPx] × [heightPx].
         *
         * The image size is passed in rather than computed from the tile count so a cutout that was
         * rendered at a different scale — a retina render, or one 地図 clamped — still projects
         * correctly. The transform is a ratio, not a pixel count.
         */
        fun pixelOf(
            latitudeDeg: Double,
            longitudeDeg: Double,
            widthPx: Int,
            heightPx: Int,
        ): Pair<Float, Float> {
            val fx = (Mercator.tileX(longitudeDeg, zoom) - tileX) / tilesW
            val fy = (Mercator.tileY(latitudeDeg, zoom) - tileY) / tilesH
            return (fx * widthPx).toFloat() to (fy * heightPx).toFloat()
        }
    }

    /** A geographic bounding box, in degrees. */
    data class Box(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
    ) {
        val centreLat: Double get() = (south + north) / 2.0
        val centreLon: Double get() = (west + east) / 2.0

        companion object {
            /** The box around a track, or null when it has no usable points. */
            fun of(points: List<Pair<Double, Double>>): Box? {
                if (points.isEmpty()) return null
                var s = Double.MAX_VALUE
                var w = Double.MAX_VALUE
                var n = -Double.MAX_VALUE
                var e = -Double.MAX_VALUE
                for ((lat, lon) in points) {
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    if (lat == 0.0 && lon == 0.0) continue      // the band's "no fix yet" point
                    s = min(s, lat); n = max(n, lat)
                    w = min(w, lon); e = max(e, lon)
                }
                return if (s > n || w > e) null else Box(s, w, n, e)
            }
        }
    }

    /** `z16_x35210_y21484_3x2.png` — every field of the transform, in the name. */
    fun name(zoom: Int, tileX: Int, tileY: Int, tilesW: Int, tilesH: Int): String =
        "z${zoom}_x${tileX}_y${tileY}_${tilesW}x$tilesH.png"

    /** Parse one back, or null if the name is not ours. */
    fun parse(file: File): Cutout? {
        val m = NAME.matchEntire(file.name) ?: return null
        val (z, x, y, w, h) = m.destructured
        return Cutout(z.toInt(), x.toInt(), y.toInt(), w.toInt(), h.toInt(), file)
    }

    /** Every cutout on disk, newest-looking first is irrelevant — order is by area, smallest first. */
    fun all(walkRoot: File): List<Cutout> =
        File(walkRoot, DIR).listFiles()?.mapNotNull { parse(it) }
            ?.filter { it.file.length() > 0 }
            // Smallest first, so a walk that fits a tight cutout is drawn at the tightest scale
            // available rather than on whichever wide one happened to be listed first.
            ?.sortedBy { it.tilesW * it.tilesH }
            ?: emptyList()

    /** The best cached cutout containing [box], or null when this is somewhere new. */
    fun cover(walkRoot: File, box: Box): Cutout? =
        all(walkRoot).firstOrNull { it.covers(box) }

    /**
     * The cutout that SHOULD exist for a box — what to ask 地図 for when [cover] found nothing.
     *
     * Snapped outward to whole tiles and grown by [MARGIN_TILES] on every side, so the answer is a
     * neighbourhood rather than a route. If that would exceed [MAX_TILES] the zoom is stepped down
     * until it fits: a coarser map of the right place beats a refusal.
     */
    fun needed(walkRoot: File, box: Box, preferredZoom: Int): Cutout {
        var zoom = preferredZoom.coerceIn(1, 19)
        while (true) {
            val x0 = floor(Mercator.tileX(box.west, zoom)).toInt() - MARGIN_TILES
            val x1 = ceil(Mercator.tileX(box.east, zoom)).toInt() + MARGIN_TILES
            val y0 = floor(Mercator.tileY(box.north, zoom)).toInt() - MARGIN_TILES
            val y1 = ceil(Mercator.tileY(box.south, zoom)).toInt() + MARGIN_TILES
            val w = x1 - x0
            val h = y1 - y0
            if ((w <= MAX_TILES && h <= MAX_TILES) || zoom <= 1) {
                return Cutout(
                    zoom, x0, y0, w.coerceAtLeast(1), h.coerceAtLeast(1),
                    File(File(walkRoot, DIR), name(zoom, x0, y0, w.coerceAtLeast(1), h.coerceAtLeast(1))),
                )
            }
            zoom--
        }
    }

    /** Where a new cutout's file goes, creating the folder. */
    fun dir(walkRoot: File): File = File(walkRoot, DIR).apply { mkdirs() }

    private val NAME = Regex("""z(\d+)_x(-?\d+)_y(-?\d+)_(\d+)x(\d+)\.png""")
}

/**
 * A walk reduced to what drawing it needs: its points, and the cutout they fit inside.
 *
 * [cutout] being null is a first-class answer and the whole trigger of the feature — it means this
 * walk is somewhere no cached map covers, and exactly one request to 地図 is owed for the AREA.
 */
data class WalkPlot(
    val points: List<Pair<Double, Double>>,
    val box: MapCutouts.Box?,
    val cutout: MapCutouts.Cutout?,
) {
    val hasTrack: Boolean get() = points.size >= 2
    /** Somewhere new: there is a route to draw and nothing to draw it on. */
    val needsMap: Boolean get() = hasTrack && cutout == null
}
