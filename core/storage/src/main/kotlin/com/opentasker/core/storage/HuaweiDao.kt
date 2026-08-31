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

/**
 * One stage block of one night, from `sequence_data` stream 700013.
 *
 * Stored as SEGMENTS rather than as a nightly summary because that is what the band actually sends:
 * the file carries no totals at all, and Huawei Health computes its headline figures the same way we
 * do. Keeping the segments means a later change of mind about how to summarise costs a query rather
 * than another night's wait.
 *
 * [sessionStart] is the band's own bed time and groups a night. It is NOT the first segment's start:
 * awake blocks can sit outside the session at both ends — see `HuaweiSleep`.
 */
@Entity(
    tableName = "huawei_sleep",
    primaryKeys = ["startSeconds"],
    indices = [Index(value = ["sessionStart"])],
)
data class HuaweiSleepEntity(
    /** UTC seconds this block begins. Unique, so re-reading a night overwrites rather than doubles. */
    val startSeconds: Long,
    val durationSeconds: Int,
    /** 1 light, 2 REM, 3 deep, 4 awake — the band's own numbering, kept raw. */
    val stage: Int,
    /** The band's bed time for this night; groups the segments. */
    val sessionStart: Long,
    /** The band's wake time. */
    val sessionEnd: Long,
    val syncId: Long,
)

/**
 * What one metric did over a window. Not an entity — a projection Room fills from an aggregate.
 *
 * [n] is carried so the caller can tell "averaged over 300 samples" from "averaged over 2", which
 * is the difference between a heart rate and a rumour.
 */
data class HuaweiSampleStats(
    val mean: Double?,
    val low: Double?,
    val high: Double?,
    val n: Int,
)

@Dao
interface HuaweiSleepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<HuaweiSleepEntity>)

    @Query("SELECT COUNT(*) FROM huawei_sleep")
    suspend fun count(): Int

    /** Every segment of every night overlapping the window, oldest first. */
    @Query(
        "SELECT * FROM huawei_sleep WHERE sessionEnd >= :from AND sessionStart <= :to " +
            "ORDER BY startSeconds ASC",
    )
    suspend fun window(from: Long, to: Long): List<HuaweiSleepEntity>

    /** The most recent night's bed time, or null when none is stored. */
    /** Every night on record, oldest first — the register and the recovery baseline need all of them. */
    @Query("SELECT DISTINCT sessionStart FROM huawei_sleep ORDER BY sessionStart")
    suspend fun sessionStarts(): List<Long>

    @Query("SELECT MAX(sessionStart) FROM huawei_sleep")
    suspend fun newestSession(): Long?

    @Query("SELECT * FROM huawei_sleep WHERE sessionStart = :sessionStart ORDER BY startSeconds ASC")
    suspend fun session(sessionStart: Long): List<HuaweiSleepEntity>
}

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

    /**
     * Every instant the band described SOMETHING at, whatever the metric.
     *
     * This is what separates "you did not walk" from "we never asked". The band omits a field it has
     * nothing to report for, so a minute carrying a heart rate but no steps is a minute with zero
     * steps — while a minute carrying nothing at all is a hole. Only the union of all metrics can
     * tell those apart, and drawing them the same way reported 白い熊's night as data loss.
     */
    @Query(
        "SELECT DISTINCT epochSeconds FROM huawei_samples " +
            "WHERE epochSeconds BETWEEN :from AND :to ORDER BY epochSeconds",
    )
    suspend fun recordedSeconds(from: Long, to: Long): List<Long>

    /**
     * Mean, extremes and count for one metric over a window — for putting a figure beside a walk.
     *
     * Returns null when the window holds nothing, which is a real answer: the band records a walk's
     * heart rate only if the sensor was on, and an average silently taken over no rows would read as
     * a measurement rather than an absence.
     */
    @Query(
        "SELECT AVG(value) AS mean, MIN(value) AS low, MAX(value) AS high, COUNT(*) AS n " +
            "FROM huawei_samples WHERE metric = :metric AND epochSeconds BETWEEN :from AND :to",
    )
    suspend fun statsFor(metric: String, from: Long, to: Long): HuaweiSampleStats?

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
