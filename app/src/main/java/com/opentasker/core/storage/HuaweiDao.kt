package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Storage for the Huawei Band 11 Pro — deliberately its OWN tables, separate from the Hume band's.
 *
 * 白い熊's direction: both bands run in parallel for a long comparison period, the Hume path stays
 * untouched, and the two data sets stay separable until we are confident nothing is missing. Only
 * then do we merge and retire Hume.
 *
 * So this is additive: new tables, nothing altered. The alternative — widening `band_samples` /
 * `band_daily` / `band_sleep` with a device discriminator — would mean rewriting their primary keys,
 * which SQLite cannot do in place: a full create/copy/drop/rename over every existing Hume row, with
 * losing the Hume association as the one unrecoverable outcome. That risk buys nothing during a
 * phase whose whole point is keeping the two apart.
 *
 * **The two devices' metrics are NOT interchangeable and must never be pooled.** Hume's `hrv` is a
 * device-state index with no unit; Huawei's HRV (once service 0x19 lands) is real RR-interval-derived
 * HRV. Hume's heart rate carries two distinct populations; Huawei's per-minute HR is a third thing
 * again. Comparison means putting them side by side, never averaging across them.
 */

/**
 * One metric reading at one minute.
 *
 * Keyed on `(metric, epochSeconds)`: the band's grid is per-minute, so that pair is the natural
 * dedupe key and a re-sync of an overlapping window simply overwrites identical rows.
 *
 * **Absent is not zero.** The band records a field only in minutes where it measured one, so a
 * missing row means "not measured" — never "measured zero". Nothing here fabricates a reading for a
 * minute the band left empty.
 */
@Entity(
    tableName = "huawei_samples",
    primaryKeys = ["metric", "epochSeconds"],
    indices = [Index(value = ["epochSeconds"])],
)
data class HuaweiSampleEntity(
    /** "steps", "calories", "distance", "hr", "spo2", "resting_hr", or "unknown_XX". */
    val metric: String,
    /** UTC seconds, straight from the record's base timestamp plus its minute offset. */
    val epochSeconds: Long,
    val value: Double,
    val syncId: Long,
)

/**
 * One sync attempt. Never pruned — it is the only instrument that measures the band's real ring
 * buffer depth over time, exactly as `band_syncs` is for the Hume band.
 */
@Entity(tableName = "huawei_syncs")
data class HuaweiSyncEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val finishedAt: Long,
    val ok: Boolean,
    val address: String,
    val firmware: String?,
    val battery: Int?,
    /** Epoch seconds of the window we asked the band for. */
    val requestedFrom: Long,
    val requestedTo: Long,
    /** How many records the band said it held for that window. */
    val recordCount: Int,
    /** How many it actually gave us — a gap here is worth noticing. */
    val recordsFetched: Int,
    /**
     * Epoch seconds of the OLDEST sample this sync really returned, or null if it returned none.
     *
     * This is the only instrument that measures how far back the band will actually answer from.
     * It cannot be derived from the requested window — the band silently returns less than it was
     * asked for — so it has to be recorded at the moment it is observed or it is gone.
     */
    val oldestReturnedSeconds: Long?,
    val samplesWritten: Int,
    val source: String,
    val message: String,
)

@Dao
interface HuaweiSampleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<HuaweiSampleEntity>)

    @Query("SELECT COUNT(*) FROM huawei_samples")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM huawei_samples WHERE metric = :metric")
    suspend fun countFor(metric: String): Int

    @Query("SELECT MIN(epochSeconds) FROM huawei_samples WHERE metric = :metric")
    suspend fun oldest(metric: String): Long?

    @Query("SELECT MAX(epochSeconds) FROM huawei_samples WHERE metric = :metric")
    suspend fun newest(metric: String): Long?

    @Query("SELECT DISTINCT metric FROM huawei_samples ORDER BY metric")
    suspend fun metrics(): List<String>

    /** Across every metric — the report header's span, and one edge of the two-band overlap. */
    @Query("SELECT MIN(epochSeconds) FROM huawei_samples")
    suspend fun oldestAny(): Long?

    @Query("SELECT MAX(epochSeconds) FROM huawei_samples")
    suspend fun newestAny(): Long?

    /**
     * Observed intervals between consecutive samples of one metric, for the coverage card.
     *
     * The band's real cadence is unmeasured, and every gate value in the Huawei chart specs is
     * provisional until it is. This is the query that replaces those guesses with numbers.
     */
    @Query(
        "SELECT epochSeconds FROM huawei_samples WHERE metric = :metric " +
            "AND epochSeconds BETWEEN :from AND :to ORDER BY epochSeconds",
    )
    suspend fun timesFor(metric: String, from: Long, to: Long): List<Long>

    @Query(
        "SELECT * FROM huawei_samples WHERE metric = :metric " +
            "AND epochSeconds BETWEEN :from AND :to ORDER BY epochSeconds",
    )
    suspend fun range(metric: String, from: Long, to: Long): List<HuaweiSampleEntity>

    /**
     * Everything in a window, for comparing the two bands side by side. The caller reads the Hume
     * rows separately and lines them up on time — the two are never joined in SQL, because that
     * would invite treating them as one series.
     */
    @Query(
        "SELECT * FROM huawei_samples WHERE epochSeconds BETWEEN :from AND :to " +
            "ORDER BY epochSeconds, metric",
    )
    suspend fun window(from: Long, to: Long): List<HuaweiSampleEntity>
}

@Dao
interface HuaweiSyncDao {
    @Insert
    suspend fun start(row: HuaweiSyncEntity): Long

    @Query(
        "UPDATE huawei_syncs SET finishedAt = :finishedAt, ok = :ok, firmware = :firmware, " +
            "battery = :battery, recordCount = :recordCount, recordsFetched = :recordsFetched, " +
            "oldestReturnedSeconds = :oldestReturnedSeconds, " +
            "samplesWritten = :samplesWritten, message = :message WHERE id = :id",
    )
    suspend fun finish(
        id: Long,
        finishedAt: Long,
        ok: Boolean,
        firmware: String?,
        battery: Int?,
        recordCount: Int,
        recordsFetched: Int,
        oldestReturnedSeconds: Long?,
        samplesWritten: Int,
        message: String,
    )

    @Query("SELECT * FROM huawei_syncs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HuaweiSyncEntity>

    @Query("SELECT COUNT(*) FROM huawei_syncs")
    suspend fun count(): Int
}
