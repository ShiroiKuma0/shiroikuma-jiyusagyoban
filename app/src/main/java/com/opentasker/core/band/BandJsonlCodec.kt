package com.opentasker.core.band

import com.opentasker.core.storage.BandDailyEntity
import com.opentasker.core.storage.BandSampleEntity
import com.opentasker.core.storage.BandSleepEntity
import com.opentasker.core.storage.StorageJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One JSON object per line — the unbounded archive that lets the DB be pruned safely.
 *
 * String in, String out, nothing else: the writer does the IO, so every encoding decision is
 * JVM-testable.
 *
 * Decoding goes through [StorageJson], the tolerant instance, so a line written by a newer build with
 * an extra field never breaks an older reader. That is exactly why that instance exists.
 *
 * Six line shapes. A sync writes one header, N records, one census line:
 *   {"t":"sync",   …}   what this sync was, and against which firmware
 *   {"t":"s",      …}   one sample
 *   {"t":"d",      …}   one day's totals
 *   {"t":"z",      …}   one sleep segment
 *   {"t":"census", …}   what each stream did
 *   {"t":"repair", …}   rows re-emitted from the DB for syncs that never reached the file
 */

/**
 * Encoding instance. Identical to [StorageJson] except that it WRITES defaults.
 *
 * kotlinx.serialization omits any property equal to its default, and `t` — the line's type tag —
 * is a default, so the shared instance produced lines with no type on them at all. A separate
 * instance rather than changing [StorageJson]: that one also encodes the DB's embedded task actions,
 * and switching defaults on there would change every row this app writes.
 *
 * Decoding still goes through [StorageJson], as the archive's readers should stay tolerant.
 */
private val BandArchiveJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
data class BandJsonlHeader(
    val t: String = "sync",
    val id: Long,
    val at: String,
    val zone: String,
    val addr: String,
    val fw: String? = null,
    val batt: Int? = null,
    val mtu: Int? = null,
    val from: Long,
    val src: String,
    val v: Int = 1,
)

@Serializable
data class BandJsonlSample(
    val t: String = "s",
    val m: String,
    val ts: Long,
    val e: Long,
    val v: Double,
    val sid: Long,
)

@Serializable
data class BandJsonlDaily(
    val t: String = "d",
    val date: Long,
    val steps: Long,
    val dist: Double,
    val kcal: Double,
    val sid: Long,
)

@Serializable
data class BandJsonlSleep(
    val t: String = "z",
    val start: Long,
    val n: Int,
    val stages: String,
    val deep: Int,
    val light: Int,
    val rem: Int,
    val awake: Int,
    val sid: Long,
)

@Serializable
data class BandJsonlCensus(
    val t: String = "census",
    val id: Long,
    val ok: Boolean,
    val ms: Long,
    val streams: Map<String, BandStreamStat>,
    val backup: String? = null,
)

/**
 * The closing marker of a repair block — see [BandArchiveRepair].
 *
 * Written **after** the rows it covers, for the same reason the census is written after a sync's
 * records: a marker that is present is a marker whose rows landed. [ids] are the syncs the block
 * accounted for, whether or not any of them still had rows to re-emit (retention may have pruned
 * them) — recording them either way is what stops the next sync re-examining the same ids forever.
 */
@Serializable
data class BandJsonlRepair(
    val t: String = "repair",
    val at: String,
    val ids: List<Long>,
    /** How many record lines this block wrote. Zero is a legitimate, informative outcome. */
    val n: Int,
    val v: Int = 1,
)

object BandJsonlCodec {

    fun encode(row: BandSampleEntity): String = BandArchiveJson.encodeToString(
        BandJsonlSample(m = row.metric, ts = row.localTs, e = row.epochMs, v = row.value, sid = row.syncId),
    )

    fun encode(row: BandDailyEntity): String = BandArchiveJson.encodeToString(
        BandJsonlDaily(
            date = row.localDate,
            steps = row.steps,
            dist = row.distanceM,
            kcal = row.calories,
            sid = row.syncId,
        ),
    )

    fun encode(row: BandSleepEntity): String {
        val seg = BandSleepSegment(row.startLocalTs, row.minutes, row.stages)
        return BandArchiveJson.encodeToString(
            BandJsonlSleep(
                start = row.startLocalTs,
                n = row.minutes,
                stages = row.stages,
                deep = seg.deep,
                light = seg.light,
                rem = seg.rem,
                awake = seg.awake,
                sid = row.syncId,
            ),
        )
    }

    fun encode(header: BandJsonlHeader): String = BandArchiveJson.encodeToString(header)

    fun encode(census: BandJsonlCensus): String = BandArchiveJson.encodeToString(census)

    fun encode(repair: BandJsonlRepair): String = BandArchiveJson.encodeToString(repair)

    /**
     * What kind of line this is, or null if it is not readable.
     *
     * A kill mid-write can leave a torn final line. Every line is standalone JSON, so a reader drops
     * an unparseable one and carries on — which is why the archive is append-only and NOT written
     * via temp-file-and-rename (that is incompatible with appending).
     */
    fun typeOf(line: String): String? = runCatching {
        (StorageJson.parseToJsonElement(line.trim()) as? JsonObject)
            ?.get("t")?.jsonPrimitive?.content
    }.getOrNull()

    /** Samples only, skipping headers, census lines and any torn line. */
    fun decodeSamples(lines: Sequence<String>): List<BandJsonlSample> =
        lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || typeOf(trimmed) != "s") return@mapNotNull null
            runCatching { StorageJson.decodeFromString<BandJsonlSample>(trimmed) }.getOrNull()
        }.toList()
}
