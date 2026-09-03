package com.opentasker.core.huawei

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * The walks on disk.
 *
 * One directory per walk, named for the band's workout number and its start, holding:
 *
 * ```
 * walk-<n>-<startSeconds>/
 * ├── track.bin      the raw file off the band — kept, always
 * ├── track.gpx      the decoded route
 * ├── walk.json      the band's own summary, plus whatever 地図 tells us
 * ├── map-thumb.png  from 白い熊 地図, once the walk has been shared with it
 * └── map.png        the large one, same source
 * ```
 *
 * ## Why the raw file never goes
 *
 * The decoder was wrong about this format for a week — every published description says the header
 * is 32 bytes and it is 33 — and the only reason that was fixable in an afternoon is that the raw
 * bytes were still on disk to re-decode. The next surprise will be the same shape. A GPX regenerated
 * from a bad decode is a walk that never happened, and nothing downstream can tell it from a real
 * one; the `.bin` is the thing that can always be re-read.
 *
 * ## Why the pictures live here rather than in 地図
 *
 * The grid has to draw forty thumbnails without asking another app anything. A path into a sister
 * app's storage is a path that breaks when it reorganises, and a frozen app cannot answer at all —
 * 白い熊 freezes apps aggressively. So 地図 renders, and we keep a copy.
 */
object HuaweiWalkLibrary {

    /**
     * Where walks live unless a task says otherwise — 白い熊's own tracks folder, which is also
     * 白い熊 地図's default output directory. Both apps pointing at one place is the point: a walk
     * and the map drawn of it are the same object, and 地図 named this folder before either half of
     * the hand-off existed.
     *
     * Deliberately **not** under `[979] バックアップ/…Huawei Band 11 Pro/`. That directory is the
     * watch-face archive and holds 45 captured faces that cannot be re-captured; walks were briefly
     * pointed at a `walks/` subfolder of it, and 白い熊 moved them out (2026-08-23).
     */
    const val DEFAULT_DIR = "/sdcard/〇/[666] 私資料/[666][147] tracks"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 地図's reading of a walk. Every field is optional — it answers with what it could compute. */
    data class ChizuReading(
        val distanceMetres: Double? = null,
        val durationSeconds: Long? = null,
        val movingSeconds: Long? = null,
        /**
         * Time with the recorder running, summed over the track's chunks — the **only** figure here
         * directly comparable with the band's own duration, which measures the same thing. A
         * disagreement between them is a finding; a disagreement between this and [durationSeconds]
         * is not, because those measure different things.
         */
        val activeSeconds: Long? = null,
        val climbMetres: Double? = null,
        val descentMetres: Double? = null,
        /** `map`, `basemap` or `none` — how much real cartography was under the track. */
        val detail: String? = null,
    ) {
        val isEmpty: Boolean
            get() = distanceMetres == null && durationSeconds == null && movingSeconds == null &&
                activeSeconds == null && climbMetres == null && descentMetres == null && detail == null
    }

    /**
     * One walk, as the grid needs it.
     *
     * [mapPath] being null is a first-class state, not a failure: it means this walk has not been
     * handed to 地図 yet, and the cell shows its stats and an invitation rather than a blank.
     */
    data class Walk(
        val dir: File,
        val number: Int,
        val startSeconds: Long,
        val endSeconds: Long?,
        val distanceMetres: Int?,
        /**
         * Steps, calories and climb, as the band itself counted them.
         *
         * These were decoded by [HuaweiWorkout.Summary] from the first day and then dropped on the
         * floor here, so the band's own screen showed figures this app could not (白い熊,
         * 2026-08-30). They are the band's arithmetic, not ours, and are kept in its units:
         * elevation arrives in decimetres and is converted only where it is shown.
         */
        val steps: Int? = null,
        val calories: Int? = null,
        val elevationGainDm: Int? = null,
        val kind: String,
        val points: Int,
        val thumbPath: String?,
        val mapPath: String?,
        val trackId: String?,
        /**
         * What 白い熊 地図 made of the same route, or null until it has been asked.
         *
         * Kept beside the band's figures and never merged with them. Two independent measurements
         * of one route are how a decoder that reads the format slightly wrongly gets caught — this
         * one already did once — so a disagreement here is a finding, not a number to reconcile.
         */
        val chizu: ChizuReading?,
        /**
         * 白い熊's own words about this walk, and how many times they stopped on it.
         *
         * **Authored, not measured** — and that is why they live in `walk.json` beside the band's
         * figures rather than in a preferences file keyed by walk id. A walk is a directory on
         * 白い熊's own archive (`[666][147] tracks`); keeping the annotation inside it means the note
         * travels with the walk when the tree is copied or restored, and survives the app's data
         * being cleared. The two writers below both carry them forward explicitly, so a
         * re-download from the band and a fresh map from 地図 leave them untouched.
         *
         * The stop count is deliberately not derived from the track's stationary gaps, though the
         * gaps are there in the data: the band pauses and resumes on its own, so a gap is the
         * RECORDER stopping and not necessarily 白い熊 (a real walk here spanned 2 h 08 m of wall
         * clock for 29 m of recording). This number is the walker's answer, and it is allowed to
         * disagree with the recorder's.
         */
        val note: String? = null,
        val stops: Int? = null,
    ) {
        val id: String get() = dir.name
        val gpx: File get() = File(dir, "track.gpx")
        val raw: File get() = File(dir, "track.bin")
        val hasMap: Boolean get() = mapPath != null
        /**
         * True when the picture we hold has no real map under it — only 地図's bundled world
         * basemap, or nothing. Worth saying out loud: a route on a pale ground is not a failed
         * render, it is a region that has not been downloaded, and the two look identical.
         */
        val mapIsBlank: Boolean get() = chizu?.detail in setOf("basemap", "none")
        val durationSeconds: Long? get() = endSeconds?.let { it - startSeconds }
    }

    /** Every walk in [dir], newest first — which is the order anyone arrives wanting. */
    fun list(dir: File): List<Walk> =
        dir.listFiles { f -> f.isDirectory && f.name.startsWith("walk-") }
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.startSeconds }
            .orEmpty()

    fun read(dir: File): Walk? {
        val meta = File(dir, "walk.json").takeIf { it.isFile } ?: return null
        val obj = runCatching { json.parseToJsonElement(meta.readText()) as? JsonObject }
            .getOrNull() ?: return null
        fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun num(k: String) = obj[k]?.jsonPrimitive?.doubleOrNull
        fun long(k: String) = obj[k]?.jsonPrimitive?.longOrNull

        val start = long("startSeconds") ?: return null
        // A path recorded in the file but no longer on disk is treated as absent rather than shown
        // as a broken image — 地図 may have been reinstalled, or the file swept.
        fun existing(k: String) = str(k)?.takeIf { File(it).isFile }
        return Walk(
            dir = dir,
            number = long("number")?.toInt() ?: 0,
            startSeconds = start,
            endSeconds = long("endSeconds"),
            distanceMetres = num("distanceMetres")?.toInt(),
            steps = num("steps")?.toInt(),
            calories = num("calories")?.toInt(),
            elevationGainDm = num("elevationGainDm")?.toInt(),
            kind = str("kind") ?: "walk",
            points = long("points")?.toInt() ?: 0,
            thumbPath = existing("thumbPath"),
            mapPath = existing("mapPath"),
            trackId = str("trackId"),
            chizu = ChizuReading(
                distanceMetres = num("chizuDistanceMetres"),
                durationSeconds = long("chizuDurationSeconds"),
                movingSeconds = long("chizuMovingSeconds"),
                activeSeconds = long("chizuActiveSeconds"),
                climbMetres = num("chizuClimbMetres"),
                descentMetres = num("chizuDescentMetres"),
                detail = str("chizuMapDetail"),
            ).takeIf { !it.isEmpty },
            note = str("note"),
            stops = long("stops")?.toInt(),
        )
    }

    /**
     * Write a freshly fetched walk, keeping anything 地図 has already told us about it.
     *
     * Re-downloading a walk must not throw away its map: the band is the authority on the route and
     * 地図 is the authority on the picture, and neither should be able to erase the other's work.
     */
    fun write(
        root: File,
        number: Int,
        startSeconds: Long,
        endSeconds: Long?,
        distanceMetres: Int?,
        steps: Int? = null,
        calories: Int? = null,
        elevationGainDm: Int? = null,
        kind: String,
        points: Int,
        raw: ByteArray,
        gpx: String,
    ): Walk {
        val dir = File(root, "walk-$number-$startSeconds").apply { mkdirs() }
        File(dir, "track.bin").writeBytes(raw)
        File(dir, "track.gpx").writeText(gpx)
        val kept = read(dir)
        save(
            dir,
            buildJsonObject {
                put("number", number)
                put("startSeconds", startSeconds)
                endSeconds?.let { put("endSeconds", it) }
                distanceMetres?.let { put("distanceMetres", it) }
                steps?.let { put("steps", it) }
                calories?.let { put("calories", it) }
                elevationGainDm?.let { put("elevationGainDm", it) }
                put("kind", kind)
                put("points", points)
                kept?.trackId?.let { put("trackId", it) }
                kept?.thumbPath?.let { put("thumbPath", it) }
                kept?.mapPath?.let { put("mapPath", it) }
                kept?.chizu?.let { putChizu(it) }
                // The band can re-supply every figure above; it cannot re-supply these. A walk
                // fetched a second time must not arrive stripped of what 白い熊 wrote on it.
                kept?.note?.let { put("note", it) }
                kept?.stops?.let { put("stops", it) }
            },
        )
        return requireNotNull(read(dir))
    }

    /** Record what 地図 handed back, leaving everything the band said untouched. */
    fun recordMap(
        walk: Walk,
        trackId: String?,
        thumbPath: String?,
        mapPath: String?,
        chizu: ChizuReading? = null,
    ): Walk {
        if (!File(walk.dir, "walk.json").isFile) return walk
        save(
            walk.dir,
            buildJsonObject {
                put("number", walk.number)
                put("startSeconds", walk.startSeconds)
                walk.endSeconds?.let { put("endSeconds", it) }
                walk.distanceMetres?.let { put("distanceMetres", it) }
                walk.steps?.let { put("steps", it) }
                walk.calories?.let { put("calories", it) }
                walk.elevationGainDm?.let { put("elevationGainDm", it) }
                put("kind", walk.kind)
                put("points", walk.points)
                // Null means "地図 did not say this time", never "forget what it said before" —
                // a partial answer must not erase a picture that is still on disk.
                (trackId ?: walk.trackId)?.let { put("trackId", it) }
                (thumbPath ?: walk.thumbPath)?.let { put("thumbPath", it) }
                (mapPath ?: walk.mapPath)?.let { put("mapPath", it) }
                (chizu?.takeIf { !it.isEmpty } ?: walk.chizu)?.let { putChizu(it) }
                walk.note?.let { put("note", it) }
                walk.stops?.let { put("stops", it) }
            },
        )
        return read(walk.dir) ?: walk
    }

    /**
     * File 白い熊's own annotation on a walk, leaving everything measured untouched.
     *
     * Both values are passed WHOLE rather than patched: null means "there is none", which is how a
     * note is deleted (blank text) and how a stop count is withdrawn (tapping the number on file).
     * The caller therefore always states the annotation it wants the walk to end up with, and there
     * is no "leave this one alone" case to get wrong.
     */
    fun annotate(walk: Walk, note: String?, stops: Int?): Walk {
        if (!File(walk.dir, "walk.json").isFile) return walk
        val cleanNote = note?.trim()?.takeIf { it.isNotEmpty() }
        save(
            walk.dir,
            buildJsonObject {
                put("number", walk.number)
                put("startSeconds", walk.startSeconds)
                walk.endSeconds?.let { put("endSeconds", it) }
                walk.distanceMetres?.let { put("distanceMetres", it) }
                walk.steps?.let { put("steps", it) }
                walk.calories?.let { put("calories", it) }
                walk.elevationGainDm?.let { put("elevationGainDm", it) }
                put("kind", walk.kind)
                put("points", walk.points)
                walk.trackId?.let { put("trackId", it) }
                walk.thumbPath?.let { put("thumbPath", it) }
                walk.mapPath?.let { put("mapPath", it) }
                walk.chizu?.let { putChizu(it) }
                cleanNote?.let { put("note", it) }
                stops?.let { put("stops", it) }
            },
        )
        return read(walk.dir) ?: walk
    }

    /**
     * Write `walk.json` whole, every time.
     *
     * Built as a real [JsonObject] rather than patched as text. The earlier version filtered lines
     * out of the previous file and appended new ones, which works only while every value stays on
     * its own line — a format nothing enforces, and one bad walk name away from a file that no
     * longer parses and a walk that vanishes from the grid.
     */
    private fun JsonObjectBuilder.putChizu(c: ChizuReading) {
        c.distanceMetres?.let { put("chizuDistanceMetres", it) }
        c.durationSeconds?.let { put("chizuDurationSeconds", it) }
        c.movingSeconds?.let { put("chizuMovingSeconds", it) }
        c.activeSeconds?.let { put("chizuActiveSeconds", it) }
        c.climbMetres?.let { put("chizuClimbMetres", it) }
        c.descentMetres?.let { put("chizuDescentMetres", it) }
        c.detail?.let { put("chizuMapDetail", it) }
    }

    private fun save(dir: File, obj: JsonObject) {
        File(dir, "walk.json").writeText(json.encodeToString(JsonObject.serializer(), obj))
    }
}
