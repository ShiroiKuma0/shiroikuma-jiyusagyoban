package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Storage for the Hume Band's health history. A sibling of [RunLogEntity] — same shape of problem,
 * a time series that is appended to and pruned by age.
 *
 * **The dedupe key is the band's own wall clock, never epoch millis.** `localTs` is yyyyMMddHHmmss
 * derived byte-for-byte from the frame with no timezone involved, so re-syncing the same record in a
 * different zone, or across a DST fall-back hour, produces the identical key. Epoch millis as the key
 * would silently double every row once a year, in the ambiguous hour.
 *
 * A bonus falls out of that choice: calendar bucketing is integer division, and therefore DST-correct
 * by construction — `localTs / 100` = minute, `/ 10000` = hour, `/ 1000000` = day.
 */
@Entity(
    tableName = "band_samples",
    primaryKeys = ["metric", "localTs"],
    indices = [Index(value = ["metric", "epochMs"])],
)
data class BandSampleEntity(
    val metric: String,
    /** yyyyMMddHHmmss, straight from the frame's BCD bytes. THE dedupe key. */
    val localTs: Long,
    /** [localTs] resolved in the phone's zone at sync time. Plotting convenience only, and disposable. */
    val epochMs: Long,
    val value: Double,
    val syncId: Long,
)

/** One calendar day's totals. Keyed on the date alone: today's row grows all day and is replaced. */
@Entity(tableName = "band_daily")
data class BandDailyEntity(
    @PrimaryKey val localDate: Long,
    val steps: Long,
    val distanceM: Double,
    val calories: Double,
    /**
     * The vendor SDK calls this ExerciseMinutes, but it reads ~0.4× steps against real frames, so
     * the label is wrong and the field is stored raw under a neutral name. Build nothing on it.
     */
    val rawExercise: Long,
    /** Bytes [21..26], hex. The SDK claims a step goal here; real frames read zero. Also raw. */
    val rawTail: String,
    val syncId: Long,
)

/**
 * One sleep SEGMENT, not one night.
 *
 * A 0x53 frame is 130 bytes and the stage bytes start at [10], so one record covers at most 120
 * minutes. A night is therefore several rows and the UI stitches contiguous ones together. Modelling
 * this as one-row-per-night is the mistake that is painful to undo later.
 *
 * [stages] is raw digit characters — 1 deep, 2 light, 3 REM, 5 awake — which is the same bytes on
 * disk as a ByteArray, readable in a DB browser and in the JSONL, and needs no TypeConverter. This
 * database has none and should keep having none.
 */
@Entity(tableName = "band_sleep")
data class BandSleepEntity(
    @PrimaryKey val startLocalTs: Long,
    val minutes: Int,
    val stages: String,
    val syncId: Long,
)

/**
 * One row per sync, with the per-stream census as JSON.
 *
 * **Never pruned.** It is a few rows a day and its entire value is the multi-day series: the band's
 * ring-buffer depth can only be measured by comparing what was asked for against what came back,
 * over days of varied gaps. A future tidy-up that eats this table destroys the instrument.
 */
@Entity(tableName = "band_syncs")
data class BandSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val address: String,
    val firmware: String?,
    val battery: Int?,
    val mtu: Int?,
    val requestedFrom: Long,
    val source: String,
    val statsJson: String,
    val message: String,
)

@Dao
interface BandSampleDao {
    /**
     * Returns one id per row, `-1` where the row was already present.
     *
     * That single return value is simultaneously the duplicate counter AND the archive filter: only
     * the rows that came back with a real id are written to the JSONL, which is what keeps
     * DB-inserted and JSONL-written exactly equivalent.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(rows: List<BandSampleEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM band_samples")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM band_samples WHERE metric = :metric")
    suspend fun countFor(metric: String): Int

    @Query("SELECT MIN(localTs) FROM band_samples WHERE metric = :metric")
    suspend fun oldestLocalTs(metric: String): Long?

    @Query("SELECT MAX(localTs) FROM band_samples WHERE metric = :metric")
    suspend fun newestLocalTs(metric: String): Long?

    @Query("SELECT * FROM band_samples WHERE metric = :metric AND localTs >= :fromLocalTs ORDER BY localTs")
    suspend fun since(metric: String, fromLocalTs: Long): List<BandSampleEntity>

    /**
     * One chart chunk, ascending. Keyed on [BandSampleEntity.epochMs], not `localTs`, because the
     * charts plot real elapsed time and a chunk boundary must not move when the phone changes zone.
     *
     * Deliberately a one-shot `suspend fun` and NOT a Room `Flow`. Room invalidation is
     * table-granular, so a Flow over this range would re-emit — and re-run the whole filter chain —
     * on every single insert during a sync, thousands of times per sync. Historical chunks are
     * immutable once the day is past; today's chunk is invalidated explicitly, once per completed
     * sync.
     */
    @Query(
        "SELECT * FROM band_samples WHERE metric = :metric " +
            "AND epochMs >= :fromEpochMs AND epochMs < :toEpochMs ORDER BY epochMs",
    )
    suspend fun rangeAsc(metric: String, fromEpochMs: Long, toEpochMs: Long): List<BandSampleEntity>

    @Query("SELECT MIN(epochMs) FROM band_samples")
    suspend fun oldestEpochMs(): Long?

    @Query("SELECT MAX(epochMs) FROM band_samples")
    suspend fun newestEpochMs(): Long?

    @Query("DELETE FROM band_samples WHERE localTs < :cutoffLocalTs")
    suspend fun deleteOlderThan(cutoffLocalTs: Long): Int

    /** Oldest-first, so pruning to a row budget drops the least useful rows. */
    @Query(
        "DELETE FROM band_samples WHERE rowid IN " +
            "(SELECT rowid FROM band_samples ORDER BY localTs ASC LIMIT :count)",
    )
    suspend fun deleteOldest(count: Int): Int
}

@Dao
interface BandDailyDao {
    /** REPLACE: today's row grows all day — 4,709 steps at 13:25 became 6,235 at 15:23. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<BandDailyEntity>)

    @Query("SELECT * FROM band_daily ORDER BY localDate DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BandDailyEntity>

    @Query("SELECT * FROM band_daily WHERE localDate = :localDate")
    suspend fun forDate(localDate: Long): BandDailyEntity?

    @Query("DELETE FROM band_daily WHERE localDate < :cutoffDate")
    suspend fun deleteOlderThan(cutoffDate: Long): Int
}

@Dao
interface BandSleepDao {
    @Query("SELECT * FROM band_sleep WHERE startLocalTs = :startLocalTs")
    suspend fun find(startLocalTs: Long): BandSleepEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(row: BandSleepEntity)

    /**
     * Extend only if longer.
     *
     * A re-read of a night still in progress must never shorten one already recorded: the band
     * reports the segment as it stands, so an early read of a 40-minute segment and a later read of
     * the same segment at 120 minutes are the same record, and the longer one wins. Returns the rows
     * that were actually written, which is what the archive filter needs.
     */
    @androidx.room.Transaction
    suspend fun insertOrExtend(rows: List<BandSleepEntity>): List<BandSleepEntity> {
        val written = mutableListOf<BandSleepEntity>()
        for (row in rows) {
            val existing = find(row.startLocalTs)
            if (existing == null || row.minutes > existing.minutes) {
                replace(row)
                written += row
            }
        }
        return written
    }

    @Query("SELECT * FROM band_sleep ORDER BY startLocalTs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BandSleepEntity>

    @Query("SELECT COUNT(*) FROM band_sleep")
    suspend fun count(): Int

    @Query("DELETE FROM band_sleep WHERE startLocalTs < :cutoffLocalTs")
    suspend fun deleteOlderThan(cutoffLocalTs: Long): Int
}

@Dao
interface BandSyncDao {
    @Insert
    suspend fun insert(row: BandSyncEntity): Long

    @Query("UPDATE band_syncs SET finishedAt = :finishedAt, ok = :ok, statsJson = :statsJson, message = :message WHERE id = :id")
    suspend fun finish(id: Long, finishedAt: Long, ok: Boolean, statsJson: String, message: String)

    @Query("UPDATE band_syncs SET firmware = :firmware, battery = :battery, mtu = :mtu WHERE id = :id")
    suspend fun stampDevice(id: Long, firmware: String?, battery: Int?, mtu: Int?)

    @Query("SELECT * FROM band_syncs ORDER BY startedAt DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<BandSyncEntity>>

    @Query("SELECT * FROM band_syncs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BandSyncEntity>

    @Query("SELECT * FROM band_syncs WHERE ok = 1 ORDER BY startedAt DESC LIMIT 1")
    suspend fun lastSuccessful(): BandSyncEntity?
}
