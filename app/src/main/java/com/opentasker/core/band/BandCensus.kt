package com.opentasker.core.band

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDateTime

/**
 * The census — what the band still holds, and what it has thrown away.
 *
 * ## Why this is measured the way it is
 *
 * The band's buffers are ring buffers: they overwrite their oldest record silently. Nothing on our
 * side deletes anything — the Hume app never sends the destructive mode either, and a factory reset
 * is the only thing that empties the band — so what is measured here is the band's own eviction.
 *
 * The decisive fact, measured over ten syncs on 2026-08-06 and **not** true of most BLE devices:
 * **the band ignores the requested start date and returns its entire buffer every time.** Sync 8
 * asked for records from 2026-08-05 07:41 and was given heart rate from 2026-08-01 18:59. Every
 * stream behaved the same way on every sync.
 *
 * That makes [BandStreamStat.oldestLocalTs] a direct reading of the buffer floor, free, on every
 * sync — which is the whole detector. Watch the floor: while it stands still nothing was evicted, and
 * when it moves forward the band threw away everything it passed over.
 *
 * ## What was here before, and why it is gone
 *
 * This file used to estimate loss as `expectedRecords − inserted`, where `expectedRecords` came from
 * a nominal cadence. Every number it ever produced was wrong:
 *
 * - `hr` is documented at 120 s and really runs at a 240 s median, so expectation ran 2x high;
 * - `detail` was listed at 60 s, but one detail record is a **ten-minute** bucket, so it ran 10x high
 *   — one sync reported "detail lost 1913" having lost precisely nothing.
 *
 * And it could not be repaired by fixing the constants, because the band skips slots constantly: the
 * periodic heart-rate series fills only 51-77 % of its own nominal slots overnight while demonstrably
 * on the wrist. Any cadence-based expectation manufactures loss out of a band that simply did not
 * measure. The floor is an observation; an expectation is a guess.
 *
 * Pure Kotlin on purpose: the arithmetic is the part worth testing, and it must be testable without a
 * device.
 */

/** What one stream did during one sync. */
@Serializable
data class BandStreamStat(
    val frames: Int = 0,
    val pages: Int = 0,
    val records: Int = 0,
    val inserted: Int = 0,
    val duplicates: Int = 0,
    /** The oldest record the band still holds for this stream — its ring buffer's floor. */
    val oldestLocalTs: Long? = null,
    val newestLocalTs: Long? = null,
    /**
     * `newest − oldest`: how much wall clock the band is still holding.
     *
     * This is the headroom — how long a sync could be missed before this stream starts losing. For a
     * stream that has been seen evicting it is the capacity; for one that has not, it is only a lower
     * bound on the capacity, because the buffer may simply not be full yet.
     */
    val bufferDepthSec: Long = 0,
    /**
     * How far the floor moved since the last sync that read this stream. Greater than zero means the
     * band evicted records — which is not by itself a loss, since we may already hold them.
     */
    val floorAdvancedSec: Long = 0,
    /**
     * The window the band evicted **before we ever read it**. The only honest loss number.
     *
     * `oldest(now) − newest(previous)`: everything between the newest record we banked last time and
     * the oldest the band can still give us is gone for good. Zero whenever the floor, however far it
     * moved, stayed behind what we had already stored.
     */
    val lostWindowSec: Long = 0,
    /**
     * Longest and shortest notification seen on this stream, in bytes.
     *
     * Kept because everything downstream assumes **one notification is one frame** — the frame-counted
     * paging rule is meaningless otherwise. A max above `MTU − 3` would mean fragmentation has
     * appeared; a max collapsing toward 20 would mean the MTU negotiation silently failed and frames
     * are arriving truncated. Both are invisible without this.
     */
    val maxFrameBytes: Int = 0,
    val minFrameBytes: Int = 0,
    val elapsedMs: Long = 0,
    val end: String = "",
    val error: String? = null,
)

/** Sampling cadences measured on the device, in seconds. The nominal reference — see the KDoc. */
val BAND_CADENCE_SEC: Map<String, Int> = mapOf(
    "hr" to 120,
    "hrv" to 120,
    "spo2" to 600,
    "temp" to 1800,
    "detail" to 60,
)

/**
 * What the series of syncs has established about one stream's buffer.
 *
 * [everEvicted] is what makes [minDepthSec] meaningful. A buffer that has been seen to roll has had
 * its capacity pinned by saturation; one that never has is only known to hold *at least* what we have
 * watched it hold.
 */
data class BandCapacityEstimate(
    val stream: String,
    val maxRecordsSeen: Int,
    val minDepthSec: Long,
    val maxDepthSec: Long,
    val everEvicted: Boolean,
    /** Total permanently lost across the whole series, in seconds. */
    val lostSec: Long,
    val confidence: String,
)

/** How long can be missed before the tightest stream starts losing, and which stream that is. */
data class BandHeadroom(val stream: String, val depthSec: Long, val measured: Boolean)

/** One sync's worth of input to the estimator, in the order they happened. */
data class BandSyncSummary(
    val startedAt: Long,
    val gapHours: Double,
    val stats: Map<String, BandStreamStat>,
)

object BandCensus {

    /**
     * Seconds from one band wall-clock stamp to another, or null if either is unreadable.
     *
     * Deliberately a LOCAL difference with no zone: both stamps come off the same device clock, and
     * introducing a zone here would make a DST boundary shift a buffer depth by an hour for no
     * reason. `yyyyMMddHHmmss` in, seconds out.
     */
    fun secondsBetween(fromTs: Long?, toTs: Long?): Long? {
        val from = parseLocalTs(fromTs) ?: return null
        val to = parseLocalTs(toTs) ?: return null
        return Duration.between(from, to).seconds
    }

    /** How much wall clock the band is still holding for a stream. */
    fun bufferDepthSec(oldestLocalTs: Long?, newestLocalTs: Long?): Long =
        secondsBetween(oldestLocalTs, newestLocalTs)?.coerceAtLeast(0) ?: 0

    /**
     * How far the buffer floor moved forward since the previous read of this stream.
     *
     * Movement alone is not loss — a floor can advance a full day over records we banked days ago.
     */
    fun floorAdvancedSec(previousOldestLocalTs: Long?, oldestLocalTs: Long?): Long =
        secondsBetween(previousOldestLocalTs, oldestLocalTs)?.coerceAtLeast(0) ?: 0

    /**
     * The window that was evicted before we ever read it — real, permanent loss.
     *
     * Everything between the newest record banked on the previous read and the oldest the band can
     * still produce is gone. Zero when the floor stayed behind what we already hold, which is the
     * normal case and stays true no matter how far the floor moves.
     */
    fun lostWindowSec(previousNewestLocalTs: Long?, oldestLocalTs: Long?): Long =
        secondsBetween(previousNewestLocalTs, oldestLocalTs)?.coerceAtLeast(0) ?: 0

    /**
     * The tightest stream: how long a sync can be missed before something starts being lost.
     *
     * Prefers streams that have actually been seen evicting, because for those the depth *is* the
     * capacity. Falls back to the shallowest of the rest — a lower bound, flagged by
     * [BandHeadroom.measured] being false — so a freshly reset band, whose buffers are all shallow
     * merely because they are empty, does not read as an emergency.
     */
    fun tightest(stats: Map<String, BandStreamStat>, evicting: Set<String> = emptySet()): BandHeadroom? {
        val usable = stats.filter { (_, s) -> s.error == null && s.bufferDepthSec > 0 }
        if (usable.isEmpty()) return null
        val rolled = usable.filterKeys { it in evicting }
        val pick = (rolled.ifEmpty { usable }).minByOrNull { it.value.bufferDepthSec } ?: return null
        return BandHeadroom(pick.key, pick.value.bufferDepthSec, measured = pick.key in evicting)
    }

    /**
     * What the whole series says about each stream.
     *
     * A stream that errored on a given sync contributes nothing from it — it read nothing, so it
     * proves nothing, and counting it as "no eviction" would claim a depth that was never observed.
     */
    fun summarize(syncs: List<BandSyncSummary>): List<BandCapacityEstimate> {
        val keys = syncs.flatMap { it.stats.keys }.distinct().sorted()
        return keys.map { key ->
            var maxRecords = 0
            var minDepth = Long.MAX_VALUE
            var maxDepth = 0L
            var evicted = false
            var lost = 0L
            for (sync in syncs) {
                val stat = sync.stats[key] ?: continue
                if (stat.error != null) continue
                maxRecords = maxOf(maxRecords, stat.records)
                if (stat.floorAdvancedSec > 0) evicted = true
                lost += stat.lostWindowSec
                if (stat.bufferDepthSec > 0) {
                    minDepth = minOf(minDepth, stat.bufferDepthSec)
                    maxDepth = maxOf(maxDepth, stat.bufferDepthSec)
                }
            }
            BandCapacityEstimate(
                stream = key,
                maxRecordsSeen = maxRecords,
                minDepthSec = if (minDepth == Long.MAX_VALUE) 0 else minDepth,
                maxDepthSec = maxDepth,
                everEvicted = evicted,
                lostSec = lost,
                confidence = when {
                    lost > 0 -> "losing"
                    evicted -> "measured"
                    maxDepth > 0 -> "at least"
                    else -> "unknown"
                },
            )
        }
    }

    /** `yyyyMMddHHmmss` to a local date-time, or null if it is not one. */
    internal fun parseLocalTs(localTs: Long?): LocalDateTime? {
        if (localTs == null || localTs <= 0) return null
        return runCatching {
            LocalDateTime.of(
                (localTs / 10_000_000_000L).toInt(),
                ((localTs / 100_000_000L) % 100).toInt(),
                ((localTs / 1_000_000L) % 100).toInt(),
                ((localTs / 10_000L) % 100).toInt(),
                ((localTs / 100L) % 100).toInt(),
                (localTs % 100).toInt(),
            )
        }.getOrNull()
    }
}
