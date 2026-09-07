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

    // The cutouts used to be PNGs in a `_maps` folder beside the walks. They are rows now — one
    // per area, keyed by the same string that used to be the file name — so a Cutout is a
    // transform and nothing else: it says which tiles at which zoom, and the bytes live in the
    // database under [Cutout.id].

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
     * for a picture of the country. Beyond this the zoom is reduced instead.
     *
     * Raised from 6 to 8 when the framing below started asking for the whole CELL rather than for
     * the track (白い熊, 2026-09-06). A frame carries the cell's 4:3 shape, so a tall walk now needs
     * roughly a third more tiles across than its own box does, and at 6 the zoom stepped down for
     * walks that used to fit — a coarser map for exactly the long walks worth looking at. The cost
     * is per AREA, not per walk: a cutout is shared by every walk that crosses it. */
    const val MAX_TILES = 8

    /**
     * The shape of the cell a walk is drawn in — 4:3, in the grid and in the detail alike.
     *
     * This is a property of the FRAME, not of the map, and that is the whole point of it being
     * here: a cutout is cut for what the cell will show.
     */
    const val CELL_ASPECT = 4.0 / 3.0

    /**
     * Padding around the route, as a fraction of its own span — the same figure `WalkMap` pads by,
     * and it reads it from here so the two can never drift.
     */
    const val FRAME_PAD = 0.08

    /**
     * A little beyond the exact cell, absorbing the renderer's few absolute pixels of padding and
     * any cell whose real aspect is not quite [CELL_ASPECT]. Without it a cutout could satisfy the
     * arithmetic and still leave a hairline of background down one edge.
     */
    const val FRAME_SLACK = 1.05

    /**
     * The zoom [frame] does its arithmetic at.
     *
     * Any zoom would do — tile coordinates at one zoom are the coordinates at another times a power
     * of two, so an aspect ratio computed in them is the same number whichever is chosen. A high
     * one is picked for precision, and it never reaches a request: [needed] chooses the zoom that
     * is actually asked for.
     */
    private const val REF_ZOOM = 21

    /** The smallest frame that is still a place: about 100 m, in [REF_ZOOM] tiles. */
    private const val MIN_FRAME_TILES = 8.0

    /**
     * The longest edge, in pixels, a cutout is ever rendered at.
     *
     * **Tiles say how much GROUND; this says how many pixels that ground is drawn on**, and the two
     * stopped being the same question when [MAX_TILES] went to 8. At a full 256 px per tile an 8×8
     * block is 2048×2048 — a PNG past the ~2 MB cursor window (which is what crashed the walks
     * window on 2026-09-06) and a 16.8 MB bitmap in a 24 MB cache, so two of them would evict each
     * other on every scroll.
     *
     * 1536 is not a guess: it is exactly what a 6×6 block at 256 px already was, so the bytes and
     * the bitmaps stay the size they have been all along while the map covers more ground. Nothing
     * downstream cares — [Cutout.pixelOf] is given the real bitmap size and works from the ratio,
     * which is precisely why it was written that way.
     */
    const val MAX_CUTOUT_PX = 1536

    /**
     * The tile size to ask 地図 to render at, for a block this big.
     *
     * Full resolution while it fits, then whatever keeps the longest edge inside [MAX_CUTOUT_PX] —
     * never below 64, because a map drawn that coarse is not a map, and a block that large should
     * have stepped its zoom down instead.
     */
    fun tilePxFor(tilesW: Int, tilesH: Int, budgetPx: Int = MAX_CUTOUT_PX): Int {
        val longest = max(tilesW, tilesH).coerceAtLeast(1)
        return min(Mercator.TILE_PX, budgetPx / longest).coerceAtLeast(64)
    }

    /**
     * The budget for a picture meant to be **pinched into** rather than shown in a cell.
     *
     * A cell cutout is drawn at most a couple of thousand pixels wide and [MAX_CUTOUT_PX] is
     * generous for it. A viewer zoomed to 4× is asking for four times the detail across, and the
     * honest answer is that it cannot all be had: a genuinely 4×-sharp picture of a two-kilometre
     * frame is 5000 px on its long edge — an eight-figure pixel count, tens of megabytes of PNG and
     * eighty of bitmap, for one walk. 3072 buys **one zoom level** over the cell cutout, so the
     * viewer is sharp through the first half of its range and soft at the end. 白い熊 chose to let
     * the pinch run to 4× anyway (2026-09-06): the route's own shape stays readable even where the
     * map behind it has run out of detail, and stopping the gesture early would hide that a walk
     * doubles back on itself.
     */
    const val VIEWER_CUTOUT_PX = 3072

    /** A viewer picture may be a bigger block than a cell's — it is fetched for one walk, on demand. */
    const val VIEWER_MAX_TILES = 14

    /**
     * The cutout a viewer wants: the same [frame], at the finest zoom its budget can pay for.
     *
     * Deliberately the same kind of object, in the same table, under the same naming: [cover] ranks
     * on zoom, so the moment one of these is stored it becomes the best answer for its area and the
     * small cells get the sharper picture too — decoded down to their own budget, which is what
     * keeps a 3072 px image out of a 24 MB bitmap cache.
     */
    fun detailed(box: Box, preferredZoom: Int): Cutout =
        needed(box, preferredZoom, maxTiles = VIEWER_MAX_TILES)

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
    ) {
        /** The whole transform in one string — the identity, and the database key. */
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
    fun parse(key: String): Cutout? {
        val m = NAME.matchEntire(key) ?: return null
        val (z, x, y, w, h) = m.destructured
        return Cutout(z.toInt(), x.toInt(), y.toInt(), w.toInt(), h.toInt())
    }

    /** Every cutout we hold, from the keys the store lists. Unordered — [cover] does the ranking. */
    fun all(keys: List<String>): List<Cutout> = keys.mapNotNull { parse(it) }

    /**
     * The geography the CELL will show for this walk — the box a cutout has to cover.
     *
     * ## Why the track's own box is the wrong question
     *
     * It was the question until 2026-09-06, and it produced maps that stopped where the walk
     * stopped. [covers] demands no margin — its own comment says edges count — so a walk that grew
     * out to the rim of a cached cutout still counted as covered, [needed] and its [MARGIN_TILES]
     * were never consulted, and the walk was drawn on a map barely bigger than itself. The renderer
     * then fits the ROUTE into the cell, uniformly, and draws the base at that same scale: a base
     * only as tall as the route cannot fill a 4:3 cell, so a third of the frame came up empty and
     * the walk ran from the very top of the map to the very bottom with no street either side of
     * its ends. 白い熊 read that as the map being cut off, correctly, and it recurred by
     * construction every time a walk outgrew its neighbourhood's map.
     *
     * ## What is asked instead
     *
     * Pad the track by [FRAME_PAD], expand it — never crop it — to [CELL_ASPECT], and add
     * [FRAME_SLACK]. That box is exactly what the cell displays once the renderer has fitted the
     * route, so a cutout containing it fills the frame on all four sides, whatever the walk's shape
     * and whatever its length. Both [cover] and [needed] frame before they answer, which is what
     * keeps them talking about the same box: if only one of them did, a fetched cutout could fail
     * the test that asked for it and the window would ask 地図 for the same area forever.
     */
    fun frame(box: Box): Box {
        val x0 = Mercator.tileX(box.west, REF_ZOOM)
        val x1 = Mercator.tileX(box.east, REF_ZOOM)
        // Mercator y runs southward, so north is the SMALLER coordinate. Getting this backwards
        // yields a negative span that the aspect step then quietly flips.
        val y0 = Mercator.tileY(box.north, REF_ZOOM)
        val y1 = Mercator.tileY(box.south, REF_ZOOM)
        val cx = (x0 + x1) / 2.0
        val cy = (y0 + y1) / 2.0
        // A walk that never moved — one fix, or a session the band mislabelled — has no span to
        // expand, and a zero box would divide by zero at the aspect step. It still deserves a
        // picture of where it happened, so it gets the smallest frame that is still a place.
        var w = max((x1 - x0) * (1.0 + 2.0 * FRAME_PAD), MIN_FRAME_TILES)
        var h = max((y1 - y0) * (1.0 + 2.0 * FRAME_PAD), MIN_FRAME_TILES)
        // Expand the short axis. Cropping the long one would put the walk's own ends outside the
        // map again, which is the entire fault being fixed.
        if (w / h < CELL_ASPECT) w = h * CELL_ASPECT else h = w / CELL_ASPECT
        w *= FRAME_SLACK
        h *= FRAME_SLACK
        return Box(
            south = Mercator.latitudeOf(cy + h / 2.0, REF_ZOOM),
            west = Mercator.longitudeOf(cx - w / 2.0, REF_ZOOM),
            north = Mercator.latitudeOf(cy - h / 2.0, REF_ZOOM),
            east = Mercator.longitudeOf(cx + w / 2.0, REF_ZOOM),
        )
    }

    /**
     * The best cached cutout that frames [box], or null when this is somewhere new.
     *
     * "Best" is the FINEST, not the smallest. Everything that reaches the ranking already fills the
     * cell — that is what [frame] settles — so the only quality left to choose on is how much detail
     * the map has, and a tie is broken on the smaller block because a smaller one at the same zoom
     * is the one cut for this neighbourhood rather than for a drive across it. The old rule was
     * smallest-area-first, which actively preferred the tightest map that would technically do.
     */
    fun cover(keys: List<String>, box: Box): Cutout? {
        val framed = frame(box)
        return all(keys)
            .filter { it.covers(framed) }
            .minWithOrNull(compareByDescending<Cutout> { it.zoom }.thenBy { it.tilesW * it.tilesH })
    }

    /**
     * The cutout that SHOULD exist for a box — what to ask 地図 for when [cover] found nothing.
     *
     * [box] is the walk's own box; what is cut is its [frame] — the same box [cover] will test the
     * answer against, so the cutout this returns is guaranteed to satisfy the request that asked
     * for it. Snapped outward to whole tiles and grown by [MARGIN_TILES] on every side, so the
     * answer is a neighbourhood rather than a route. If that would exceed [MAX_TILES] the zoom is
     * stepped down until it fits: a coarser map of the right place beats a refusal, and it still
     * contains the frame, because snapping outward at a coarser zoom only ever adds ground.
     */
    fun needed(box: Box, preferredZoom: Int, maxTiles: Int = MAX_TILES): Cutout {
        val framed = frame(box)
        var zoom = preferredZoom.coerceIn(1, 19)
        while (true) {
            val x0 = floor(Mercator.tileX(framed.west, zoom)).toInt() - MARGIN_TILES
            val x1 = ceil(Mercator.tileX(framed.east, zoom)).toInt() + MARGIN_TILES
            val y0 = floor(Mercator.tileY(framed.north, zoom)).toInt() - MARGIN_TILES
            val y1 = ceil(Mercator.tileY(framed.south, zoom)).toInt() + MARGIN_TILES
            val w = x1 - x0
            val h = y1 - y0
            if ((w <= maxTiles && h <= maxTiles) || zoom <= 1) {
                return Cutout(
                    zoom, x0, y0, w.coerceAtLeast(1), h.coerceAtLeast(1),
                )
            }
            zoom--
        }
    }


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
