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

    /** Everything drawing one walk needs, resolved against what is cached. */
    fun plot(walkRoot: File, gpx: File, viewPixels: Int = 720): WalkPlot {
        val points = read(gpx)
        val box = MapCutouts.Box.of(points)
            ?: return WalkPlot(points, null, null)
        return WalkPlot(points, box, MapCutouts.cover(walkRoot, box))
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
