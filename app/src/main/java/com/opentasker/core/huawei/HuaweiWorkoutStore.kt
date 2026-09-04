package com.opentasker.core.huawei

import com.opentasker.core.storage.HuaweiMapCutoutEntity
import com.opentasker.core.storage.HuaweiWorkoutBlobEntity
import com.opentasker.core.storage.HuaweiWorkoutDao
import com.opentasker.core.storage.HuaweiWorkoutEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The band's workouts, in the database.
 *
 * ## Why this replaced a directory
 *
 * Every workout used to be a folder under `/sdcard/〇/[666] 私資料/[666][147] tracks`, and the one
 * real reason was that 白い熊 地図 had to read a GPX and neither app can reach the other's private
 * storage. Everything else moved out there with it — the raw track, the summary, the heart rate,
 * and 白い熊's own note — and none of it was in the app's export. A "clear app data" therefore took
 * the authored record with it while the backup reported success.
 *
 * Measured before the move: 5,246 KB on disk, of which 288 KB was irreplaceable. The rest was a
 * GPX generated from those bytes and pictures 地図 can draw again.
 *
 * The GPX is no longer stored anywhere. It is a lossy rendering of the track file — deltas turned
 * into degrees under an earth radius and a datum that are both still unproven — so keeping it would
 * mean keeping a derived file that could go stale against a fixed decoder. It is regenerated when
 * something asks for it, and handed to 地図 through a content URI that exists for one round trip.
 *
 * ## Raw first
 *
 * The band's own bytes live in blobs and everything else is decoded from them. The summary carries
 * thirty-one TLV tags and twelve are understood; the day one more is worked out, every workout ever
 * recorded can be re-read — but only because 187 bytes each were kept.
 */
object HuaweiWorkoutStore {

    private val json = Json { ignoreUnknownKeys = true }

    /** The blob names. Free-form in the table, fixed here so nothing invents a spelling. */
    const val BLOB_SUMMARY = "summary"
    const val BLOB_TRACK = "track"
    fun blobSamples(block: Int) = "samples/$block"

    /** 地図's own arithmetic over the same route — kept beside ours, never merged into it. */
    data class ChizuReading(
        val distanceMetres: Double? = null,
        val durationSeconds: Long? = null,
        val movingSeconds: Long? = null,
        /**
         * Time with the recorder running — the **only** figure here directly comparable with the
         * band's own duration, because it measures the same thing. A disagreement between those two
         * is a finding; one between this and [durationSeconds] is not.
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
     * What the workout cost, as the band measured it.
     *
     * The heart rate is the band's own five-second stream from the workout service — not the
     * per-minute history the ordinary sync stores, which is a different instrument at a twelfth of
     * the resolution and does not know a workout was happening.
     *
     * A zero in [heart] is "no reading", not a pulse of zero: the first sample of every workout is
     * one, taken before the sensor has anything to average.
     */
    data class Effort(
        val intervalSeconds: Int = 0,
        val heart: List<Int> = emptyList(),
        val speedDmS: List<Int> = emptyList(),
        val stepsPerInterval: List<Int> = emptyList(),
        val recovery: List<Int> = emptyList(),
        val splits: List<HuaweiWorkout.Split> = emptyList(),
    ) {
        private val live: List<Int> get() = heart.filter { it > 0 }
        val samples: Int get() = live.size
        val maxHeart: Int? get() = live.maxOrNull()
        val minHeart: Int? get() = live.minOrNull()
        val meanHeart: Double? get() = live.takeIf { it.isNotEmpty() }?.average()
        /** How far the heart fell once the work stopped. See [HuaweiWorkout.Summary.recovery]. */
        val recoveryDrop: Int?
            get() = recovery.filter { it > 0 }.takeIf { it.size >= 2 }?.let { it.first() - it.last() }
        val isEmpty: Boolean get() = heart.isEmpty() && recovery.isEmpty() && splits.isEmpty()
    }

    /**
     * One workout, as the screens need it — every scalar, and no blob.
     *
     * The heart-rate stream is NOT here. It is decoded from the sample blobs by [effortOf] when a
     * workout is opened, because a grid of forty cells wants a mean and a count, not forty
     * five-hundred-element lists.
     */
    data class Workout(
        val startSeconds: Long,
        val number: Int,
        val endSeconds: Long? = null,
        val sportType: Int? = null,
        val kind: String = "unknown",
        val distanceMetres: Int? = null,
        val steps: Int? = null,
        val calories: Int? = null,
        val elevationGainDm: Int? = null,
        val meanSpeedDmS: Int? = null,
        val intervalSeconds: Int = 0,
        val sampleCount: Int = 0,
        val trackPoints: Int = 0,
        val recovery: List<Int> = emptyList(),
        val splits: List<HuaweiWorkout.Split> = emptyList(),
        val note: String? = null,
        val stops: Int? = null,
        val trackId: String? = null,
        val chizu: ChizuReading? = null,
        val cutoutKey: String? = null,
        private val storedDuration: Long? = null,
    ) {
        /** Stable across a re-fetch and across a restore, unlike the band's own workout number. */
        val id: String get() = startSeconds.toString()
        val isStrength: Boolean get() = sportType == HuaweiWorkout.STRENGTH
        val hasTrack: Boolean get() = trackPoints > 0
        val durationSeconds: Long? get() = storedDuration ?: endSeconds?.let { it - startSeconds }
        /** How far the heart fell after the work stopped — from the curve alone, no blob needed. */
        val recoveryDrop: Int?
            get() = recovery.filter { it > 0 }.takeIf { it.size >= 2 }?.let { it.first() - it.last() }
        val hasHeart: Boolean get() = sampleCount > 0 || recovery.isNotEmpty()
    }

    // --- reading ----------------------------------------------------------------------------

    suspend fun all(dao: HuaweiWorkoutDao): List<Workout> = dao.all().map { it.toWorkout() }

    /** Walks, or lifts. The filter is the sport code, never the kind string. */
    suspend fun ofKind(dao: HuaweiWorkoutDao, strength: Boolean): List<Workout> =
        dao.all().map { it.toWorkout() }.filter { it.isStrength == strength }

    suspend fun byId(dao: HuaweiWorkoutDao, id: String): Workout? =
        id.toLongOrNull()?.let { dao.byStart(it)?.toWorkout() }

    /**
     * The heart rate, decoded from the blobs on demand.
     *
     * Decoded rather than stored twice: the sample blocks are the band's own bytes and the decode is
     * checked three ways against figures the band states independently, so a second copy in a
     * column would only be a thing that could disagree with them.
     */
    suspend fun effortOf(dao: HuaweiWorkoutDao, workout: Workout): Effort? {
        val blocks = dao.blobNames(workout.startSeconds)
            .filter { it.startsWith("samples/") }
            .sortedBy { it.removePrefix("samples/").toIntOrNull() ?: 0 }
            .mapNotNull { name ->
                dao.blob(workout.startSeconds, name)?.let {
                    HuaweiWorkout.parseSamples(listOf(HuaweiProtocol.Tlv(0x81, it)))
                }
            }
        if (blocks.isEmpty()) return legacyEffort(dao, workout)
        val effort = Effort(
            intervalSeconds = blocks.first().intervalSeconds,
            heart = blocks.flatMap { it.heart },
            speedDmS = blocks.flatMap { it.speedDmS },
            stepsPerInterval = blocks.flatMap { it.steps },
            recovery = workout.recovery,
            splits = workout.splits,
        )
        return effort.takeIf { !it.isEmpty }
    }

    /**
     * The heart rate of a workout that was fetched before the raw blocks were kept.
     *
     * Written once by the disk import and never by a fetch, so it disappears of its own accord the
     * first time the band is asked for that workout again. Read only when there are no real sample
     * blocks: the band's own bytes always win.
     */
    private suspend fun legacyEffort(dao: HuaweiWorkoutDao, workout: Workout): Effort? {
        val bytes = dao.blob(workout.startSeconds, "legacy/effort.json") ?: return null
        val o = runCatching { json.parseToJsonElement(String(bytes)).jsonObject }.getOrNull() ?: return null
        fun ints(k: String) = o[k]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { v -> v.jsonPrimitive.intOrNull }.orEmpty()
        return Effort(
            intervalSeconds = o["intervalSeconds"]?.jsonPrimitive?.intOrNull ?: workout.intervalSeconds,
            heart = ints("heart"),
            speedDmS = ints("speedDmS"),
            stepsPerInterval = ints("stepsPerInterval"),
            recovery = workout.recovery,
            splits = workout.splits,
        ).takeIf { !it.isEmpty }
    }

    suspend fun trackOf(dao: HuaweiWorkoutDao, workout: Workout): ByteArray? =
        dao.blob(workout.startSeconds, BLOB_TRACK)

    /** The route as GPX, regenerated. Nothing keeps one: it is derived, and derived from bytes. */
    suspend fun gpxOf(dao: HuaweiWorkoutDao, workout: Workout, name: String): String? {
        val raw = trackOf(dao, workout) ?: return null
        val track = HuaweiGpsTrack.decode(raw) ?: return null
        return HuaweiGpsTrack.toGpx(track, name)
    }

    // --- writing ----------------------------------------------------------------------------

    /**
     * Store a freshly fetched workout, leaving everything the band cannot re-supply alone.
     *
     * The annotation and 地図's answers are read back from the existing row and carried forward
     * rather than passed in, so a caller holding a stale copy cannot roll them back.
     */
    suspend fun put(
        dao: HuaweiWorkoutDao,
        summary: HuaweiWorkout.Summary,
        startSeconds: Long,
        sampleBlocks: List<ByteArray>,
        splits: List<HuaweiWorkout.Split>,
        summaryRaw: ByteArray?,
        trackRaw: ByteArray?,
        trackPoints: Int,
        sampleCount: Int,
        intervalSeconds: Int,
    ) {
        val kept = dao.byStart(startSeconds)
        dao.upsert(
            HuaweiWorkoutEntity(
                startSeconds = startSeconds,
                number = summary.number,
                endSeconds = summary.endSeconds,
                durationSeconds = summary.durationSeconds,
                sportType = summary.type,
                kind = summary.kind,
                distanceMetres = summary.distanceMetres,
                steps = summary.steps,
                calories = summary.calories,
                elevationGainDm = summary.elevationGainDm,
                meanSpeedDmS = summary.meanSpeedDmS,
                intervalSeconds = intervalSeconds,
                sampleCount = sampleCount,
                trackPoints = trackPoints,
                recovery = summary.recovery.takeIf { it.isNotEmpty() }
                    ?.map { it.toByte() }?.toByteArray(),
                splitsJson = splits.takeIf { it.isNotEmpty() }?.let { splitsToJson(it) },
                note = kept?.note,
                stops = kept?.stops,
                trackId = kept?.trackId,
                chizuJson = kept?.chizuJson,
                cutoutKey = kept?.cutoutKey,
            ),
        )
        summaryRaw?.let { dao.putBlob(HuaweiWorkoutBlobEntity(startSeconds, BLOB_SUMMARY, it)) }
        trackRaw?.let { dao.putBlob(HuaweiWorkoutBlobEntity(startSeconds, BLOB_TRACK, it)) }
        sampleBlocks.forEachIndexed { i, payload ->
            dao.putBlob(HuaweiWorkoutBlobEntity(startSeconds, blobSamples(i), payload))
        }
    }

    suspend fun annotate(dao: HuaweiWorkoutDao, workout: Workout, note: String?, stops: Int?) {
        dao.annotate(workout.startSeconds, note?.trim()?.takeIf { it.isNotEmpty() }, stops)
    }

    suspend fun recordMap(
        dao: HuaweiWorkoutDao,
        workout: Workout,
        trackId: String?,
        chizu: ChizuReading?,
        cutoutKey: String?,
    ) {
        // Null means "地図 did not say this time", never "forget what it said before".
        dao.recordMap(
            startSeconds = workout.startSeconds,
            trackId = trackId ?: workout.trackId,
            chizuJson = (chizu?.takeIf { !it.isEmpty } ?: workout.chizu)?.let { chizuToJson(it) },
            cutoutKey = cutoutKey ?: workout.cutoutKey,
        )
    }

    suspend fun forget(dao: HuaweiWorkoutDao, workout: Workout) {
        dao.deleteBlobs(workout.startSeconds)
        dao.delete(workout.startSeconds)
    }

    // --- cutouts ----------------------------------------------------------------------------

    suspend fun cutout(dao: HuaweiWorkoutDao, key: String): ByteArray? = dao.cutout(key)

    suspend fun putCutout(
        dao: HuaweiWorkoutDao,
        key: String,
        zoom: Int,
        tileX: Int,
        tileY: Int,
        tilesW: Int,
        tilesH: Int,
        tilePx: Int,
        png: ByteArray,
    ) {
        dao.putCutout(
            HuaweiMapCutoutEntity(
                key = key, zoom = zoom, tileX = tileX, tileY = tileY,
                tilesW = tilesW, tilesH = tilesH, tilePx = tilePx, png = png,
                fetchedAtSeconds = System.currentTimeMillis() / 1000,
            ),
        )
    }

    /**
     * Write one workout's heart rate out as a file 白い熊 can take somewhere else.
     *
     * The database already holds all of this, and holds it properly — this is the door OUT, for a
     * copy that leaves the app. What it must therefore carry is its own clock: `startSeconds` and
     * `intervalSeconds` beside the array, so every sample has an instant without repeating a
     * timestamp five hundred times. A bare list of numbers would not be an export.
     *
     * The recovery curve goes in with `spacingSeconds` explicitly null. The band never says how far
     * apart those twenty-five readings are, and a file that implied a number would be inventing the
     * one thing this whole feature has refused to invent.
     */
    fun exportHeart(
        workout: Workout,
        effort: Effort,
        dir: java.io.File,
        nowMillis: Long = System.currentTimeMillis(),
    ): java.io.File? {
        if (effort.heart.isEmpty() && effort.recovery.isEmpty()) return null
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(java.util.Date(nowMillis))
        val out = java.io.File(dir, "hr-${workout.kind}-${workout.number}_$stamp.json")
        val obj = buildJsonObject {
            put("workout", workout.number)
            put("kind", workout.kind)
            workout.sportType?.let { put("sportType", it) }
            put("startSeconds", workout.startSeconds)
            workout.endSeconds?.let { put("endSeconds", it) }
            workout.durationSeconds?.let { put("durationSeconds", it) }
            workout.calories?.let { put("calories", it) }
            workout.distanceMetres?.takeIf { it > 0 }?.let { put("distanceMetres", it) }
            workout.steps?.takeIf { it > 0 }?.let { put("steps", it) }
            workout.note?.let { put("note", it) }
            putJsonObject("heart") {
                put("startSeconds", workout.startSeconds)
                put("intervalSeconds", effort.intervalSeconds)
                put("unit", "bpm")
                // Zero means the band took no reading for that interval, and it is kept rather than
                // dropped: removing it would silently shift every later sample earlier in time.
                put("zeroMeansNoReading", true)
                putJsonArray("bpm") { effort.heart.forEach { add(it) } }
            }
            if (effort.recovery.isNotEmpty()) {
                putJsonObject("recovery") {
                    put("unit", "bpm")
                    put("spacingSeconds", kotlinx.serialization.json.JsonNull)
                    putJsonArray("bpm") { effort.recovery.forEach { add(it) } }
                }
            }
            if (effort.speedDmS.isNotEmpty()) {
                putJsonObject("speed") {
                    put("unit", "dm/s")
                    putJsonArray("value") { effort.speedDmS.forEach { add(it) } }
                }
            }
            if (effort.stepsPerInterval.isNotEmpty()) {
                putJsonArray("stepsPerInterval") { effort.stepsPerInterval.forEach { add(it) } }
            }
        }
        return runCatching {
            out.writeText(json.encodeToString(JsonObject.serializer(), obj))
            out
        }.getOrNull()
    }

    /**
     * Write the route out as GPX — regenerated, never stored.
     *
     * The file names the earth radius it used. Two constants are still candidates for turning the
     * band's metre deltas back into degrees, and the datum is unsettled too, so a GPX that leaves
     * this app has to say which reading produced it or it stops being interpretable the day we
     * learn which is right.
     */
    suspend fun exportGpx(
        dao: HuaweiWorkoutDao,
        workout: Workout,
        dir: java.io.File,
        nowMillis: Long = System.currentTimeMillis(),
    ): java.io.File? {
        val gpx = gpxOf(dao, workout, "${workout.kind} ${workout.number}") ?: return null
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US)
            .format(java.util.Date(nowMillis))
        val out = java.io.File(dir, "${workout.kind}-${workout.number}_$stamp.gpx")
        val stated = gpx.replaceFirst(
            "<trk>",
            "<!-- decoded with earthRadiusM=${HuaweiGpsTrack.EARTH_RADIUS_M}; " +
                "datum unconfirmed (WGS-84 assumed) -->\n  <trk>",
        )
        return runCatching { out.writeText(stated); out }.getOrNull()
    }

    // --- the two JSON columns -----------------------------------------------------------------

    private fun splitsToJson(splits: List<HuaweiWorkout.Split>): String =
        json.encodeToString(
            kotlinx.serialization.json.JsonArray.serializer(),
            buildJsonArray {
                splits.forEach { s ->
                    add(
                        buildJsonObject {
                            put("index", s.index)
                            put("mile", s.mile)
                            put("seconds", s.seconds)
                            put("cumulativeSeconds", s.cumulativeSeconds)
                            s.partialDecimetres?.let { put("partialDecimetres", it) }
                        },
                    )
                }
            },
        )

    private fun splitsFromJson(text: String?): List<HuaweiWorkout.Split> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { row ->
            val o = runCatching { row.jsonObject }.getOrNull() ?: return@mapNotNull null
            fun n(k: String) = o[k]?.jsonPrimitive?.intOrNull
            HuaweiWorkout.Split(
                index = n("index") ?: return@mapNotNull null,
                mile = o["mile"]?.jsonPrimitive?.booleanOrNull ?: false,
                seconds = n("seconds") ?: return@mapNotNull null,
                cumulativeSeconds = n("cumulativeSeconds") ?: 0,
                partialDecimetres = n("partialDecimetres"),
            )
        }
    }

    private fun chizuToJson(c: ChizuReading): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            c.distanceMetres?.let { put("distanceMetres", it) }
            c.durationSeconds?.let { put("durationSeconds", it) }
            c.movingSeconds?.let { put("movingSeconds", it) }
            c.activeSeconds?.let { put("activeSeconds", it) }
            c.climbMetres?.let { put("climbMetres", it) }
            c.descentMetres?.let { put("descentMetres", it) }
            c.detail?.let { put("detail", it) }
        },
    )

    private fun chizuFromJson(text: String?): ChizuReading? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        fun l(k: String) = o[k]?.jsonPrimitive?.longOrNull
        return ChizuReading(
            distanceMetres = d("distanceMetres"),
            durationSeconds = l("durationSeconds"),
            movingSeconds = l("movingSeconds"),
            activeSeconds = l("activeSeconds"),
            climbMetres = d("climbMetres"),
            descentMetres = d("descentMetres"),
            detail = o["detail"]?.jsonPrimitive?.contentOrNull,
        ).takeIf { !it.isEmpty }
    }

    private fun HuaweiWorkoutEntity.toWorkout() = Workout(
        startSeconds = startSeconds,
        number = number,
        endSeconds = endSeconds,
        sportType = sportType,
        kind = kind,
        distanceMetres = distanceMetres,
        steps = steps,
        calories = calories,
        elevationGainDm = elevationGainDm,
        meanSpeedDmS = meanSpeedDmS,
        intervalSeconds = intervalSeconds,
        sampleCount = sampleCount,
        trackPoints = trackPoints,
        recovery = recovery?.map { it.toInt() and 0xFF }.orEmpty(),
        splits = splitsFromJson(splitsJson),
        note = note,
        stops = stops,
        trackId = trackId,
        chizu = chizuFromJson(chizuJson),
        cutoutKey = cutoutKey,
        storedDuration = durationSeconds,
    )
}
