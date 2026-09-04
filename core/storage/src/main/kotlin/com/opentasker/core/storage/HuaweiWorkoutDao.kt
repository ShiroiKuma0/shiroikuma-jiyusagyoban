package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The band's recorded workouts — walks, and strength sessions — as rows rather than as files.
 *
 * ## Why this exists at all
 *
 * Until 2026-09-04 a workout was a directory under `/sdcard/〇/[666] 私資料/[666][147] tracks`,
 * because a walk's GPX had to be readable by 白い熊 地図 and neither app can reach the other's
 * private storage. That reason covered the GPX and nothing else, and everything else went along
 * with it: the raw track, the summary, the heart rate, and 白い熊's own note on the walk.
 *
 * Measured, that directory held 5,246 KB of which 288 KB — the raw `track.bin` — could not be
 * re-derived. The rest was a GPX generated from those bytes and pictures re-fetchable from 地図.
 * None of it was in the app's export, so a "clear app data" or a lost phone took the authored notes
 * with it while the backup reported success (白い熊, 2026-09-04: *"all health data is contained in
 * our DB, we'd save it through export/import — so nothing is lost"*).
 *
 * ## What is kept, and what is not
 *
 * **Raw first.** The band's own bytes go in [HuaweiWorkoutBlobEntity] — the GPS file, the summary
 * container, each block of the sample stream — and everything else in this file is decoded FROM
 * them. The summary carries thirty-one TLV tags and we understand twelve; the day we learn what
 * `0x0D` means we can re-read every workout ever recorded, but only because 187 bytes per workout
 * were kept. The GPX is not stored at all: it is a lossy rendering of the track file under two
 * constants that are still unproven, and regenerating it costs milliseconds.
 *
 * **Authored last, and never overwritten.** [note] and [stops] are the only fields here the band
 * cannot re-supply. A re-fetch replaces every measurement and must leave those two alone.
 */
@Entity(
    tableName = "huawei_workouts",
    indices = [Index(value = ["sportType"]), Index(value = ["number"])],
)
data class HuaweiWorkoutEntity(
    /**
     * When the workout began, and the identity of the row.
     *
     * NOT the band's workout number, which is its index into a ring buffer and is reused once that
     * buffer wraps. Two workouts cannot begin in the same second; two workouts can certainly end up
     * numbered 8.
     */
    @PrimaryKey val startSeconds: Long,
    /** The band's own number — how a workout is ADDRESSED, kept for re-fetching, never for identity. */
    val number: Int,
    /** `start + active duration`, which is the band's arithmetic and not the wall-clock finish. */
    val endSeconds: Long? = null,
    val durationSeconds: Long? = null,
    /** The band's sport code. 2 is a walk, 140 a strength session; see `HuaweiWorkout.kind`. */
    val sportType: Int? = null,
    val kind: String = "unknown",
    val distanceMetres: Int? = null,
    val steps: Int? = null,
    val calories: Int? = null,
    val elevationGainDm: Int? = null,
    /** The band's own mean speed, decimetres per second. */
    val meanSpeedDmS: Int? = null,
    /** Seconds between per-sample readings — five on every workout measured so far. */
    val intervalSeconds: Int = 0,
    /** How many samples the stream holds, so a list can say so without decoding the blobs. */
    val sampleCount: Int = 0,
    /**
     * How many fixes the track file holds — zero for a workout that recorded none.
     *
     * A column rather than a decode, because the grid shows it on every cell and the alternative is
     * reading a 30 kB blob per row to count records in it.
     */
    val trackPoints: Int = 0,
    /** The post-workout recovery curve, one byte per reading. Twenty-five of them, and no clock. */
    val recovery: ByteArray? = null,
    /** The per-kilometre and per-mile splits, as JSON. Absent for a workout with no distance. */
    val splitsJson: String? = null,
    /** 白い熊's own words. Authored, not measured — a re-fetch must never touch it. */
    val note: String? = null,
    /** How many times 白い熊 stopped. Authored likewise, and allowed to disagree with the recorder. */
    val stops: Int? = null,
    /** 地図's id for this track, once it has been handed over. Null until then. */
    val trackId: String? = null,
    /** 地図's own arithmetic over the same route, as JSON — kept beside ours, never merged into it. */
    val chizuJson: String? = null,
    /** Which cached base map covers this route, by [HuaweiMapCutoutEntity.key]. */
    val cutoutKey: String? = null,
) {
    // Room generates neither for a class with an array field, and the defaults compare identity —
    // which would make two rows holding the same bytes unequal and quietly break any set logic.
    override fun equals(other: Any?): Boolean =
        other is HuaweiWorkoutEntity && startSeconds == other.startSeconds
    override fun hashCode(): Int = startSeconds.hashCode()
}

/**
 * One raw payload off the band, kept exactly as it arrived.
 *
 * A separate table, not a column, for one measured reason: a GPS file is about 30 kB and Android's
 * `CursorWindow` is 2 MB. A list query that carried the tracks would work for sixty walks and then
 * throw `SQLiteBlobTooBigException` on the sixty-first — a failure that arrives as a screen that
 * has stopped working, not as a warning. Here the grid never selects a blob at all.
 *
 * [name] is what the payload is: `summary`, `track`, or `samples/0`, `samples/1` … one per block the
 * band paged the stream into.
 */
@Entity(tableName = "huawei_workout_blobs", primaryKeys = ["startSeconds", "name"])
data class HuaweiWorkoutBlobEntity(
    val startSeconds: Long,
    val name: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is HuaweiWorkoutBlobEntity && startSeconds == other.startSeconds && name == other.name
    override fun hashCode(): Int = 31 * startSeconds.hashCode() + name.hashCode()
}

/**
 * A piece of base map from 白い熊 地図, shared by every walk that crosses it.
 *
 * One picture per AREA, never per walk. The first version of this kept a rendered PNG beside each
 * walk — 2.5 MB for a 120 kB track, and two walks down one street produced two pictures of that
 * street. A cutout is about 1 MB (zoom 17, five tiles by five) and one of them covers a
 * neighbourhood's worth of walking.
 *
 * Its own export category for the same reason it is its own table: it is the one thing here that is
 * large, derived AND re-creatable — 地図 can draw it again — so 白い熊 gets to decide whether it is
 * worth carrying in a backup. The qualification is that 地図 must be installed and not frozen at
 * the moment it is asked, which is not a safe assumption on this phone.
 */
@Entity(tableName = "huawei_map_cutouts")
data class HuaweiMapCutoutEntity(
    /** `z<zoom>_x<tileX>_y<tileY>_<w>x<h>` — the same identity the file name used to carry. */
    @PrimaryKey val key: String,
    val zoom: Int,
    val tileX: Int,
    val tileY: Int,
    val tilesW: Int,
    val tilesH: Int,
    val tilePx: Int,
    val png: ByteArray,
    val fetchedAtSeconds: Long,
) {
    override fun equals(other: Any?): Boolean = other is HuaweiMapCutoutEntity && key == other.key
    override fun hashCode(): Int = key.hashCode()
}

@Dao
interface HuaweiWorkoutDao {
    /** Newest first, which is the order anyone arrives wanting. Never touches a blob. */
    @Query("SELECT * FROM huawei_workouts ORDER BY startSeconds DESC")
    suspend fun all(): List<HuaweiWorkoutEntity>

    @Query("SELECT * FROM huawei_workouts WHERE sportType = :sportType ORDER BY startSeconds DESC")
    suspend fun ofSport(sportType: Int): List<HuaweiWorkoutEntity>

    @Query("SELECT * FROM huawei_workouts WHERE startSeconds = :startSeconds")
    suspend fun byStart(startSeconds: Long): HuaweiWorkoutEntity?

    /** Which starts we already hold — the cheap question the sync asks before fetching anything. */
    @Query("SELECT startSeconds FROM huawei_workouts")
    suspend fun knownStarts(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: HuaweiWorkoutEntity)

    @Query("DELETE FROM huawei_workouts WHERE startSeconds = :startSeconds")
    suspend fun delete(startSeconds: Long)

    /**
     * File 白い熊's annotation without touching a measurement.
     *
     * A whole-row upsert would work and is exactly what must not be used: the caller would have to
     * hold every measured field to write two authored ones, and the day it holds a stale copy the
     * band's figures get quietly rolled back to it.
     */
    @Query("UPDATE huawei_workouts SET note = :note, stops = :stops WHERE startSeconds = :startSeconds")
    suspend fun annotate(startSeconds: Long, note: String?, stops: Int?)

    @Query(
        "UPDATE huawei_workouts SET trackId = :trackId, chizuJson = :chizuJson, " +
            "cutoutKey = :cutoutKey WHERE startSeconds = :startSeconds",
    )
    suspend fun recordMap(startSeconds: Long, trackId: String?, chizuJson: String?, cutoutKey: String?)

    // --- blobs: fetched one at a time, by name, and never in a listing ---------------------------

    @Query("SELECT payload FROM huawei_workout_blobs WHERE startSeconds = :startSeconds AND name = :name")
    suspend fun blob(startSeconds: Long, name: String): ByteArray?

    @Query("SELECT name FROM huawei_workout_blobs WHERE startSeconds = :startSeconds ORDER BY name")
    suspend fun blobNames(startSeconds: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBlob(row: HuaweiWorkoutBlobEntity)

    @Query("DELETE FROM huawei_workout_blobs WHERE startSeconds = :startSeconds")
    suspend fun deleteBlobs(startSeconds: Long)

    // --- cutouts ---------------------------------------------------------------------------------

    @Query("SELECT png FROM huawei_map_cutouts WHERE key = :key")
    suspend fun cutout(key: String): ByteArray?

    @Query("SELECT key FROM huawei_map_cutouts")
    suspend fun cutoutKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCutout(row: HuaweiMapCutoutEntity)
}
