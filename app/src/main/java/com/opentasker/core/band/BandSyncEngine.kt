package com.opentasker.core.band

import android.content.Context
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.BandDailyEntity
import com.opentasker.core.storage.BandSampleEntity
import com.opentasker.core.storage.BandSleepEntity
import com.opentasker.core.storage.BandSyncEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What one sync did. */
sealed interface BandSyncOutcome {
    data class Ok(val syncId: Long, val summary: String, val warning: String?) : BandSyncOutcome
    data class Skipped(val reason: String) : BandSyncOutcome
    data class Failed(val reason: String) : BandSyncOutcome
}

/** Everything a sync needs, already resolved — parsing lives in the Action so it stays testable. */
data class BandSyncRequest(
    val address: String,
    val from: BandLocalTime,
    val streams: List<BandStream>,
    val timeoutSec: Int,
    val backup: Boolean,
    val backupDir: java.io.File,
    val source: String,
)

/**
 * Connect, drain every stream, land the data, close.
 *
 * Runs on an APPLICATION-scoped CoroutineScope rather than a ViewModel's, so backgrounding the app
 * mid-sync cannot cancel it. There is deliberately no foreground service: a manual sync is 10–30 s
 * with the app visible, and an Action invoked from a Profile already runs inside AutomationService,
 * which is one. Plain GATT needs no FGS type on Android 14+.
 */
object BandSyncEngine {

    /** One sync at a time, process-wide. A second request is Skipped, not queued. */
    private val running = Mutex()

    /** Application-scoped on purpose — see the class KDoc. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val statsJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun sync(
        context: Context,
        db: AppDatabase,
        request: BandSyncRequest,
        onProgress: (BandSyncProgress) -> Unit = {},
    ): BandSyncOutcome {
        if (!running.tryLock()) {
            // Not a failure: TaskRunner already treats Skip as non-failing, and a second press while
            // one is in flight is a normal thing for 白い熊 to do.
            return BandSyncOutcome.Skipped("a sync is already running")
        }
        try {
            return runLocked(context, db, request, onProgress)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runLocked(
        context: Context,
        db: AppDatabase,
        request: BandSyncRequest,
        onProgress: (BandSyncProgress) -> Unit,
    ): BandSyncOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        BandSyncState.begin(request.streams.size)
        onProgress(BandSyncState.progress.value)

        val syncId = db.bandSyncDao().insert(
            BandSyncEntity(
                startedAt = startedAt,
                finishedAt = 0,
                ok = false,
                address = request.address,
                firmware = null,
                battery = null,
                mtu = null,
                requestedFrom = request.from.toLocalTs(),
                source = request.source,
                statsJson = "{}",
                message = "",
            ),
        )

        val client = BandGattClient(context)
        val stats = linkedMapOf<String, BandStreamStat>()
        var firmware: String? = null
        var battery: Int? = null

        try {
            when (val opened = client.open(request.address)) {
                is BandConnectResult.Failed -> {
                    finish(db, syncId, startedAt, false, stats, opened.reason)
                    BandSyncState.finish(opened.reason)
                    return@withContext BandSyncOutcome.Failed(opened.reason)
                }
                is BandConnectResult.Ready -> Unit
            }

            // Info first: a firmware change invalidates the capacity series rather than silently
            // poisoning it, so the census row is stamped before any stream is read.
            BandSyncState.phase("device")
            firmware = readFirmware(client)
            battery = readBattery(client)
            db.bandSyncDao().stampDevice(syncId, firmware, battery, client.grantedMtu)

            val payload = client.grantedMtu - 3
            val previous = db.bandSyncDao().lastSuccessful()
            val gapSeconds = previous?.let { (startedAt - it.startedAt) / 1000 } ?: 0L

            val whole = withTimeoutOrNull(request.timeoutSec * 1000L) {
                request.streams.forEachIndexed { index, stream ->
                    BandSyncState.stream(stream.key, index)
                    onProgress(BandSyncState.progress.value)

                    if (stream == BandStream.SLEEP && payload < BandGattClient.MIN_USABLE_PAYLOAD) {
                        // A truncated sleep frame still parses into plausible-looking numbers, which
                        // is worse than no data. Record why and move on.
                        stats[stream.key] = BandStreamStat(
                            end = "SKIPPED",
                            error = "MTU payload $payload < ${BandGattClient.MIN_USABLE_PAYLOAD}",
                        )
                        return@forEachIndexed
                    }

                    stats[stream.key] = drainStream(client, stream, request.from, db, syncId, zone, gapSeconds)
                    BandSyncState.counted(
                        stats.values.sumOf { it.records },
                        stats.values.sumOf { it.inserted },
                    )
                    onProgress(BandSyncState.progress.value)
                }
                true
            }

            val timedOut = whole == null
            val inserted = stats.values.sumOf { it.inserted }
            val records = stats.values.sumOf { it.records }
            val erroredStreams = stats.values.count { it.error != null }

            var backupNote: String? = null
            if (request.backup) {
                backupNote = writeArchive(db, syncId, request, stats, startedAt, zone, firmware, battery, client.grantedMtu)
            }

            val summary = "$inserted new of $records read across ${stats.size} streams"
            val warning = when {
                timedOut -> "the session timed out after ${request.timeoutSec}s — later streams were skipped"
                erroredStreams > 0 -> "$erroredStreams stream(s) did not complete"
                else -> null
            }
            finish(db, syncId, startedAt, true, stats, listOfNotNull(summary, warning, backupNote).joinToString(" · "))
            BandSyncState.finish(summary)
            BandSyncOutcome.Ok(syncId, summary, warning)
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            finish(db, syncId, startedAt, false, stats, reason)
            BandSyncState.finish(reason)
            BandSyncOutcome.Failed(reason)
        } finally {
            // Unconditionally, on every path — a leaked BluetoothGatt causes status 133 next time.
            client.close()
        }
    }

    /**
     * One stream, start to terminator.
     *
     * A stream that times out is RECORDED and the sync continues to the next one: the band's ring
     * buffer is the real risk, so banking six streams beats aborting the run over one.
     */
    private suspend fun drainStream(
        client: BandGattClient,
        stream: BandStream,
        from: BandLocalTime,
        db: AppDatabase,
        syncId: Long,
        zone: ZoneId,
        gapSeconds: Long,
    ): BandStreamStat {
        val began = System.currentTimeMillis()
        val machine = BandStreamMachine(stream)
        if (!client.send(BandCommand.start(stream, from))) {
            return BandStreamStat(end = "ERROR", error = "could not send the request", elapsedMs = 0)
        }

        var end = BandStreamEnd.IDLE_TIMEOUT
        loop@ while (true) {
            val frame = client.nextFrame(BandGattClient.FRAME_IDLE_TIMEOUT_MS) ?: break@loop
            when (val step = machine.onFrame(frame)) {
                is BandStreamStep.Await -> Unit
                is BandStreamStep.SendContinue -> client.send(step.command)
                is BandStreamStep.Done -> {
                    end = step.reason
                    break@loop
                }
            }
        }

        val parsed = machine.parsed()
        val written = persist(db, syncId, parsed, zone)
        // Banked as each stream lands; the header and census bracket them at the end, so a stream
        // that times out later cannot cost the archive what earlier streams already wrote.
        bank(syncId, written.lines)
        val expected = BandCensus.expectedRecords(stream.key, gapSeconds)
        return BandStreamStat(
            frames = machine.frames,
            pages = machine.pages,
            records = parsed.recordCount,
            inserted = written.inserted,
            duplicates = parsed.recordCount - written.inserted,
            oldestLocalTs = written.oldest,
            newestLocalTs = written.newest,
            expectedRecords = expected,
            lostRecords = BandCensus.lostRecords(expected, written.inserted),
            elapsedMs = System.currentTimeMillis() - began,
            end = end.name,
            error = if (end == BandStreamEnd.IDLE_TIMEOUT) "no frame for ${BandGattClient.FRAME_IDLE_TIMEOUT_MS}ms" else null,
        )
    }

    private data class Written(
        val inserted: Int,
        val oldest: Long?,
        val newest: Long?,
        val lines: List<String>,
    )

    /**
     * DB first, archive second.
     *
     * insertIgnoringDuplicates returns -1 for every row already present, and that one value is
     * simultaneously the duplicate counter AND the filter deciding what reaches the JSONL. The
     * invariant it buys: DB-inserted ⇔ JSONL-written, one line each, no duplicates across syncs ever.
     */
    private suspend fun persist(
        db: AppDatabase,
        syncId: Long,
        parsed: BandParsedFrame,
        zone: ZoneId,
    ): Written {
        val lines = mutableListOf<String>()
        var inserted = 0

        val sampleRows = parsed.samples.map {
            BandSampleEntity(it.metric, it.localTs, epochMillis(it.localTs, zone), it.value, syncId)
        }
        if (sampleRows.isNotEmpty()) {
            val ids = db.bandSampleDao().insertIgnoringDuplicates(sampleRows)
            val fresh = sampleRows.filterIndexed { i, _ -> ids.getOrNull(i) != -1L }
            inserted += fresh.size
            lines += fresh.map(BandJsonlCodec::encode)
        }

        val dailyRows = parsed.daily.map {
            BandDailyEntity(it.localDate, it.steps, it.distanceM, it.calories, it.rawExercise, it.rawTail, syncId)
        }
        if (dailyRows.isNotEmpty()) {
            db.bandDailyDao().upsert(dailyRows)
            inserted += dailyRows.size
            lines += dailyRows.map(BandJsonlCodec::encode)
        }

        val sleepRows = parsed.sleep.map { BandSleepEntity(it.startLocalTs, it.minutes, it.stages, syncId) }
        if (sleepRows.isNotEmpty()) {
            val written = db.bandSleepDao().insertOrExtend(sleepRows)
            inserted += written.size
            lines += written.map(BandJsonlCodec::encode)
        }

        val stamps = parsed.samples.map { it.localTs } +
            parsed.sleep.map { it.startLocalTs } +
            parsed.daily.map { it.localDate * 1_000_000 }
        return Written(inserted, stamps.minOrNull(), stamps.maxOrNull(), lines)
    }

    private suspend fun writeArchive(
        db: AppDatabase,
        syncId: Long,
        request: BandSyncRequest,
        stats: Map<String, BandStreamStat>,
        startedAt: Long,
        zone: ZoneId,
        firmware: String?,
        battery: Int?,
        mtu: Int,
    ): String? = runCatching {
        val writer = BandJsonlWriter(request.backupDir)
        val header = BandJsonlCodec.encode(
            BandJsonlHeader(
                id = syncId,
                at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                    java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedAt), zone),
                ),
                zone = zone.id,
                addr = request.address,
                fw = firmware,
                batt = battery,
                mtu = mtu,
                from = request.from.toLocalTs(),
                src = request.source,
            ),
        )
        val census = BandJsonlCodec.encode(
            BandJsonlCensus(
                id = syncId,
                ok = true,
                ms = System.currentTimeMillis() - startedAt,
                streams = stats,
            ),
        )
        // The record lines were collected as each stream landed; header and census bracket them.
        writer.appendAll(listOf(header) + archiveLines.getOrDefault(syncId, emptyList()) + listOf(census), LocalDate.now())
        archiveLines.remove(syncId)
        null
    }.getOrElse { "backup:failed:${it.message}" }

    /** Lines banked per sync as streams land, flushed once at the end. */
    private val archiveLines = mutableMapOf<Long, MutableList<String>>()

    private fun bank(syncId: Long, lines: List<String>) {
        if (lines.isEmpty()) return
        archiveLines.getOrPut(syncId) { mutableListOf() } += lines
    }

    private suspend fun finish(
        db: AppDatabase,
        syncId: Long,
        startedAt: Long,
        ok: Boolean,
        stats: Map<String, BandStreamStat>,
        message: String,
    ) {
        db.bandSyncDao().finish(
            id = syncId,
            finishedAt = System.currentTimeMillis(),
            ok = ok,
            statsJson = runCatching { statsJson.encodeToString(stats) }.getOrDefault("{}"),
            message = message,
        )
    }

    private suspend fun readFirmware(client: BandGattClient): String? {
        if (!client.send(BandCommand.info(BandInfoQuery.FIRMWARE))) return null
        val reply = awaitReply(client, BandInfoQuery.FIRMWARE.opcode) ?: return null
        return BandProtocol.parseFirmware(reply)
    }

    private suspend fun readBattery(client: BandGattClient): Int? {
        if (!client.send(BandCommand.info(BandInfoQuery.BATTERY))) return null
        val reply = awaitReply(client, BandInfoQuery.BATTERY.opcode) ?: return null
        return BandProtocol.parseBattery(reply)
    }

    /** Info replies share the notify characteristic with stream frames, so match on the opcode. */
    private suspend fun awaitReply(client: BandGattClient, opcode: Int): ByteArray? {
        repeat(INFO_REPLY_ATTEMPTS) {
            val frame = client.nextFrame(INFO_REPLY_TIMEOUT_MS) ?: return null
            if (frame.isNotEmpty() && (frame[0].toInt() and 0xFF) == opcode) return frame
        }
        return null
    }

    private fun epochMillis(localTs: Long, zone: ZoneId): Long = runCatching {
        LocalDateTime.of(
            (localTs / 10_000_000_000L).toInt(),
            ((localTs / 100_000_000L) % 100).toInt(),
            ((localTs / 1_000_000L) % 100).toInt(),
            ((localTs / 10_000L) % 100).toInt(),
            ((localTs / 100L) % 100).toInt(),
            (localTs % 100).toInt(),
        ).atZone(zone).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private const val INFO_REPLY_TIMEOUT_MS = 3_000L
    private const val INFO_REPLY_ATTEMPTS = 4
}

/** yyyyMMddHHmmss, the same shape the records use. */
fun BandLocalTime.toLocalTs(): Long =
    year.toLong() * 10_000_000_000L +
        month.toLong() * 100_000_000L +
        day.toLong() * 1_000_000L +
        hour.toLong() * 10_000L +
        minute.toLong() * 100L +
        second.toLong()
