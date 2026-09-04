package com.opentasker.core.huawei

import android.content.Context
import com.opentasker.core.storage.HuaweiWorkoutBlobEntity
import com.opentasker.core.storage.HuaweiWorkoutDao
import com.opentasker.core.storage.HuaweiWorkoutEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one-time move of the workout archive off shared storage and into the database.
 *
 * ## What comes across, and what does not
 *
 * **The raw track and the annotation.** Those are the two things that cannot be re-made: the band
 * drops a workout from its ring buffer eventually, and nothing anywhere else holds 白い熊's note or
 * their stop count.
 *
 * **Not the GPX**, which is a lossy rendering of the track file under an earth radius and a datum
 * that are both unproven — it is regenerated on demand. **Not the per-walk pictures**, which are
 * 地図's work over an area that one shared cutout now covers for every walk that crosses it: on
 * walk 8 alone they were 2,088 KB, two copies of the same two images under different names
 * (白い熊, 2026-09-04: *"we don't want them imported into DB"*).
 *
 * **And not the shared cutout in `_maps/`**, deliberately, though it would have been the easiest
 * thing here to lift. 白い熊's call, and the right one: fetching it through the new URI contract is
 * a real end-to-end test of `EXPORT_BASEMAP`, and copying the file across would have skipped the
 * one path in this whole change that had never been exercised. The window asks 地図 for a missing
 * area by itself, so an archive left behind here costs a round trip, not a picture.
 *
 * ## The heart rate that has no bytes behind it
 *
 * A workout fetched before this change kept its heart rate as decoded arrays in `workout.json`; the
 * band's own sample blocks were never stored. Re-fetching would supply them — the band still holds
 * these ten — but "the band still has it" is precisely the assumption a one-way migration must not
 * make. So the old `effort` object is carried across verbatim, as the JSON it already was, under a
 * blob name that says what it is. `effortOf` reads real sample blocks first and falls back to this
 * only when there are none, so a re-fetch quietly replaces a legacy row with the band's own bytes
 * and nothing has to remember to clean up.
 *
 * ## The directory is moved, never deleted
 *
 * Verified first — every row read back — and then the tree is MOVED to 白い熊's backup archive
 * rather than removed. Deleting the original of a one-way migration on the app's own say-so is not
 * a decision code gets to make; the app's job is to make it safe to delete, and 白い熊's to delete it.
 */
object HuaweiWorkoutImport {

    private const val PREFS = "huawei_workout_import"
    private const val KEY_DONE = "imported"

    /** Where the archive is parked once its contents are in the database. */
    const val ARCHIVE_ROOT = "/sdcard/〇/[979] バックアップ"

    /** Where workouts used to live. Read once, then moved; never written to again. */
    const val LEGACY_DIR = "/sdcard/〇/[666] 私資料/[666][147] tracks"

    /** Legacy decoded effort, kept verbatim. Named so its provenance is unmistakable. */
    const val BLOB_LEGACY_EFFORT = "legacy/effort.json"

    private val json = Json { ignoreUnknownKeys = true }

    data class Result(
        val imported: Int = 0,
        val skipped: Int = 0,
        val movedTo: String? = null,
        val message: String = "",
    )

    /**
     * Import once, and remember that it ran.
     *
     * The flag is not "is the table empty": that is also true the moment 白い熊 deletes the only
     * workout there is, and a re-import firing then would resurrect it from a directory that was
     * supposed to be gone. Same trap the rehab seed carries a note about.
     */
    suspend fun runOnce(context: Context, dao: HuaweiWorkoutDao, dir: File): Result {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return Result(message = "already imported")
        if (!dir.isDirectory) {
            prefs.edit().putBoolean(KEY_DONE, true).apply()
            return Result(message = "nothing on disk to import")
        }
        val result = import(dao, dir)
        prefs.edit().putBoolean(KEY_DONE, true).apply()
        return result
    }

    /** The import itself, without the flag — so a test can run it and a task can force it. */
    suspend fun import(dao: HuaweiWorkoutDao, dir: File): Result {
        val folders = dir.listFiles { f -> f.isDirectory }
            ?.filter { meta(it) != null }
            .orEmpty()
        if (folders.isEmpty()) return Result(message = "no workouts on disk")

        var imported = 0
        var skipped = 0
        for (folder in folders) {
            if (readOne(dao, folder)) imported++ else skipped++
        }

        // Read every one of them back before anything is moved. An import that reported success
        // over rows that are not there is the one outcome that loses the archive.
        val starts = dao.knownStarts().toSet()
        val expected = folders.mapNotNull { startOf(it) }.toSet()
        val missing = expected - starts
        if (missing.isNotEmpty()) {
            return Result(imported, skipped, null, "NOT moved — ${missing.size} did not read back")
        }

        val moved = park(dir)
        return Result(
            imported = imported,
            skipped = skipped,
            movedTo = moved?.absolutePath,
            message = if (moved != null) {
                "$imported workouts imported; the old archive is at ${moved.name}"
            } else {
                "$imported workouts imported; the old archive could not be moved and is untouched"
            },
        )
    }

    private fun meta(folder: File): File? =
        listOf("workout.json", "walk.json").map { File(folder, it) }.firstOrNull { it.isFile }

    private fun obj(folder: File): JsonObject? = meta(folder)
        ?.let { runCatching { json.parseToJsonElement(it.readText()).jsonObject }.getOrNull() }

    private fun startOf(folder: File): Long? =
        obj(folder)?.get("startSeconds")?.jsonPrimitive?.longOrNull

    private suspend fun readOne(dao: HuaweiWorkoutDao, folder: File): Boolean {
        val o = obj(folder) ?: return false
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
        fun long(k: String) = o[k]?.jsonPrimitive?.longOrNull
        fun dbl(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        val start = long("startSeconds") ?: return false

        val raw = File(folder, "track.bin").takeIf { it.isFile }?.readBytes()
        // Counted from the bytes rather than trusted from the record: `points` in the old file was
        // written by whatever decoder ran that day, and the bytes are the thing that is still true.
        val points = raw?.let { HuaweiGpsTrack.decode(it)?.points?.size } ?: 0

        val effort = o["effort"]
        val chizu = HuaweiWorkoutStore.ChizuReading(
            distanceMetres = dbl("chizuDistanceMetres"),
            durationSeconds = long("chizuDurationSeconds"),
            movingSeconds = long("chizuMovingSeconds"),
            activeSeconds = long("chizuActiveSeconds"),
            climbMetres = dbl("chizuClimbMetres"),
            descentMetres = dbl("chizuDescentMetres"),
            detail = str("chizuMapDetail"),
        )

        val kind = str("kind") ?: "walk"
        dao.upsert(
            HuaweiWorkoutEntity(
                startSeconds = start,
                number = int("number") ?: 0,
                endSeconds = long("endSeconds"),
                durationSeconds = long("endSeconds")?.let { it - start },
                sportType = int("sportType") ?: if (kind == "walk") 2 else null,
                kind = kind,
                distanceMetres = int("distanceMetres"),
                steps = int("steps"),
                calories = int("calories"),
                elevationGainDm = int("elevationGainDm"),
                meanSpeedDmS = null,
                intervalSeconds = effortField(effort, "intervalSeconds")?.jsonPrimitive?.intOrNull ?: 0,
                sampleCount = intsOf(effort, "heart").size,
                trackPoints = points,
                recovery = intsOf(effort, "recovery").takeIf { it.isNotEmpty() }
                    ?.map { it.toByte() }?.toByteArray(),
                splitsJson = null,
                note = str("note"),
                stops = int("stops"),
                trackId = str("trackId"),
                chizuJson = null,
                cutoutKey = null,
            ),
        )
        // The two writers that must not be lost, and the one that is only a fallback.
        raw?.let { dao.putBlob(HuaweiWorkoutBlobEntity(start, HuaweiWorkoutStore.BLOB_TRACK, it)) }
        effort?.let {
            dao.putBlob(
                HuaweiWorkoutBlobEntity(start, BLOB_LEGACY_EFFORT, it.toString().toByteArray()),
            )
        }
        // 地図's reading goes through the store so its JSON shape has exactly one writer.
        HuaweiWorkoutStore.byId(dao, start.toString())?.let { w ->
            HuaweiWorkoutStore.recordMap(dao, w, str("trackId"), chizu.takeIf { !it.isEmpty }, null)
        }
        return true
    }

    /**
     * Move the imported tree into the backup archive, under a name that says when and why.
     *
     * A rename inside `/sdcard` is atomic and instant. If it fails — a different mount, a card that
     * will not take the name — nothing is deleted and the caller is told, because the alternative
     * (copy then delete) is the version that can half-finish.
     */
    private fun park(dir: File): File? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val archive = File(ARCHIVE_ROOT).apply { mkdirs() }
        val target = File(archive, "${dir.name} (取り込み済 $stamp)")
        return if (dir.renameTo(target)) target else null
    }

    /** One field of the old `effort` object, or null when the workout predates it entirely. */
    private fun effortField(effort: kotlinx.serialization.json.JsonElement?, key: String) =
        effort?.let { runCatching { it.jsonObject[key] }.getOrNull() }

    private fun intsOf(effort: kotlinx.serialization.json.JsonElement?, key: String): List<Int> =
        effortField(effort, key)
            ?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { it.jsonPrimitive.intOrNull }
            .orEmpty()
}
