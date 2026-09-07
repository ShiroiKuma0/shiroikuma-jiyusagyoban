package com.opentasker.core.huawei.maps

import java.io.File

/**
 * Reading a walk's coordinates back off disk.
 *
 * The GPX is the source rather than `track.bin`: it is the artefact both this app and 地図 already
 * agree on, it is plain text, and if it is ever wrong that is a bug worth seeing here rather than
 * hiding behind a second decoder that might disagree with it.
 */
object WalkTrack {

    /** Every `lat`/`lon` pair in file order. Malformed attributes are skipped, not guessed at. */
    fun read(gpx: File): List<Pair<Double, Double>> {
        if (!gpx.isFile) return emptyList()
        val text = runCatching { gpx.readText() }.getOrNull() ?: return emptyList()
        return POINT.findAll(text).mapNotNull { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lon = m.groupValues[2].toDoubleOrNull()
            if (lat == null || lon == null) null else lat to lon
        }.toList()
    }

    /**
     * Thin a track down to at most [limit] points, keeping the first and the last.
     *
     * A walk is a couple of thousand points and a grid cell is under two hundred pixels wide, so
     * most of them land on a pixel that is already painted. Every one still costs a projection and
     * a path segment while scrolling. Even sampling rather than anything cleverer: the shape of a
     * walk survives it, and a simplification that moved points would make the drawn route disagree
     * with the recorded one.
     */
    fun thin(points: List<Pair<Double, Double>>, limit: Int): List<Pair<Double, Double>> {
        if (points.size <= limit || limit < 2) return points
        val step = points.size.toDouble() / (limit - 1)
        val out = ArrayList<Pair<Double, Double>>(limit)
        var i = 0.0
        while (out.size < limit - 1) {
            out.add(points[i.toInt().coerceAtMost(points.size - 1)])
            i += step
        }
        out.add(points.last())
        return out
    }

    /**
     * Everything drawing one walk needs, resolved against the cutouts we hold.
     *
     * Takes the coordinates rather than a file: the route comes from the band's own `track.bin`,
     * decoded on the way out of the database, and no GPX is kept anywhere to read.
     */
    fun plot(
        points: List<Pair<Double, Double>>,
        cutoutKeys: List<String>,
    ): WalkPlot {
        val box = MapCutouts.Box.of(points) ?: return WalkPlot(points, null, null)
        return WalkPlot(points, box, MapCutouts.cover(cutoutKeys, box))
    }

    /**
     * How much of a walk the band actually recorded, against what it says the walk was.
     *
     * ## Why this has to be shown
     *
     * On 2026-09-06 白い熊 looked at a there-and-back walk that had drawn as ONE line and said,
     * correctly, that this is impossible: two GPS legs never overlay. They were right, and the
     * reason was not the drawing — the band's GPS did not fix until nineteen minutes in, so the
     * outbound leg was never written to the file at all. Four of the fifteen walks on the phone that
     * day were partial the same way, and nothing anywhere said so: the screen printed the band's own
     * "2.55 km" directly above a map showing 1.25 km of it.
     *
     * A picture that silently omits half a walk is worse than no picture. This is the number that
     * makes it visible.
     *
     * ## Why the polyline may exceed the band's figure
     *
     * It usually does, by 3–15 %: a polyline through per-second fixes accumulates GPS jitter, while
     * the band reports a smoothed distance. So only a SHORTFALL means anything, and [partial] is
     * deliberately slack about it — every healthy walk measured sat between 1.03 and 1.15, and every
     * broken one at 0.83 or below.
     */
    data class Coverage(
        /** When the first fix landed, as seconds after the workout began. The TTFF, measured. */
        val firstFixDelaySeconds: Long,
        /** The drawn route's own length. */
        val routeMetres: Int,
        /** What the band says the walk was. Zero when it does not say. */
        val bandMetres: Int,
    ) {
        val fraction: Double get() = if (bandMetres > 0) routeMetres.toDouble() / bandMetres else 1.0

        /**
         * Materially less than the walk. The threshold is loose on purpose — see the class note.
         */
        val partial: Boolean get() = bandMetres > 0 && fraction < 0.9

        /** How far the band says was walked with no fix to show for it. */
        val missingMetres: Int get() = (bandMetres - routeMetres).coerceAtLeast(0)
    }

    /**
     * Measure one walk's coverage.
     *
     * [trackStartSeconds] is the first fix's own timestamp out of the band's file — NOT the
     * workout's start. The difference between the two is the whole point: it is the time to first
     * fix, which is the only measurement of the satellite-assistance data this app can take by
     * itself, on every walk, without anyone setting up a test.
     */
    fun coverage(
        points: List<Pair<Double, Double>>,
        trackStartSeconds: Long,
        workoutStartSeconds: Long,
        bandMetres: Int,
    ): Coverage = Coverage(
        firstFixDelaySeconds = (trackStartSeconds - workoutStartSeconds).coerceAtLeast(0),
        routeMetres = metres(points).toInt(),
        bandMetres = bandMetres,
    )

    /**
     * The polyline's length in metres, by the haversine.
     *
     * Not the band's distance and not meant to be: this measures what was DRAWN, which is exactly
     * the quantity that has to be compared against what the band claims.
     */
    fun metres(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val (lat1, lon1) = points[i]
            val (lat2, lon2) = points[i + 1]
            val p = Math.PI / 180.0
            val a = kotlin.math.sin((lat2 - lat1) * p / 2).let { it * it } +
                kotlin.math.cos(lat1 * p) * kotlin.math.cos(lat2 * p) *
                kotlin.math.sin((lon2 - lon1) * p / 2).let { it * it }
            total += 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
        }
        return total
    }

    /** The zoom a walk of this size deserves, for asking 地図 for a cutout. */
    fun zoomFor(box: MapCutouts.Box, viewPixels: Int = 720): Int {
        // The diagonal in metres, roughly — good enough to choose a zoom, and it never has to be
        // better than that because the cutout is snapped to whole tiles afterwards anyway.
        val latM = (box.north - box.south) * 111_320.0
        val lonM = (box.east - box.west) * 111_320.0 *
            kotlin.math.cos(box.centreLat * kotlin.math.PI / 180.0)
        val span = kotlin.math.max(latM, lonM)
        return Mercator.zoomFor(span, viewPixels, box.centreLat, maxZoom = 17)
    }

    private val POINT = Regex("""lat="([-0-9.]+)"\s+lon="([-0-9.]+)"""")
}
