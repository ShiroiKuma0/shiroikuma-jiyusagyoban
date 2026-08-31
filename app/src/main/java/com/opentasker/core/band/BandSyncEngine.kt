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
import kotlinx.serialization.decodeFromString
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

        // The archive write, on EVERY exit path — success, timeout, exception, failed connect.
        //
        // It used to sit on the success path alone, and that was the defect: `persist()` commits
        // each stream's rows to the database as that stream lands and banks the matching JSONL
        // lines, so a sync that banked rows and then threw left the database holding rows the file
        // had never heard of. The banked lines were dropped on the floor — and leaked, since only
        // the success path ever cleared the map. Not theoretical: syncs 28 and 41 vanished from
        // 白い熊's archive exactly this way, taking 27 heart-rate rows of 2026-08-08 with them, and
        // the file stayed 27 rows short of the DB until the two were audited against each other by
        // hand.
        //
        // A failed sync now writes its header and a census with `ok:false`. That is worth having on
        // its own account: a hole in the id sequence was the only trace such a sync left behind.
        suspend fun writeArchiveAndRepair(ok: Boolean, error: String?): String? {
            if (!request.backup) return null
            val wrote = writeArchive(
                syncId, request, stats, startedAt, zone, firmware, battery, client.grantedMtu, ok, error,
            )
            // AFTER the write, so this sync's own census is on disk and its rows are not re-emitted
            // — and so that a write which just failed is itself repaired, in the same run.
            val repaired = repairArchive(db, request, zone)
            return listOfNotNull(wrote, repaired).joinToString(" · ").ifEmpty { null }
        }

        try {
            when (val opened = client.open(request.address)) {
                is BandConnectResult.Failed -> {
                    val note = writeArchiveAndRepair(ok = false, error = opened.reason)
                    finish(
                        db, syncId, startedAt, false, stats,
                        listOfNotNull(opened.reason, note).joinToString(" · "),
                    )
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
            // Per STREAM, not per sync: 同期状態 probes with streams=hr alone, and taking the last
            // successful sync wholesale would hand every other stream a null previous-read and blind
            // the loss detector for exactly one cycle after every status check.
            val previousReads = previousReadsPerStream(db)

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

                    stats[stream.key] = drainStream(
                        client, stream, request.from, db, syncId, zone,
                        previousReads[stream.key],
                    )
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

            val warning = when {
                timedOut -> "the session timed out after ${request.timeoutSec}s — later streams were skipped"
                erroredStreams > 0 -> "$erroredStreams stream(s) did not complete"
                else -> null
            }
            val backupNote = writeArchiveAndRepair(ok = warning == null, error = warning)

            val summary = "$inserted new of $records read across ${stats.size} streams"
            finish(db, syncId, startedAt, true, stats, listOfNotNull(summary, warning, backupNote).joinToString(" · "))
            BandSyncState.finish(summary)
            BandSyncOutcome.Ok(syncId, summary, warning)
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            // The rows this sync already committed must reach the file even though it is ending
            // badly. Guarded, because a second throw here would replace the real reason with a
            // misleading one — the archive is the backup, not the point of the sync.
            val note = runCatching { writeArchiveAndRepair(ok = false, error = reason) }.getOrNull()
            finish(db, syncId, startedAt, false, stats, listOfNotNull(reason, note).joinToString(" · "))
            BandSyncState.finish(reason)
            BandSyncOutcome.Failed(reason)
        } finally {
            // Nothing banked may outlive the sync that banked it, on any path. The old code removed
            // the entry only after a successful write, so every failure leaked its lines forever.
            archiveLines.remove(syncId)
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
        previous: BandStreamStat?,
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
        return BandStreamStat(
            frames = machine.frames,
            pages = machine.pages,
            records = parsed.recordCount,
            inserted = written.inserted,
            duplicates = parsed.recordCount - written.inserted,
            oldestLocalTs = written.oldest,
            newestLocalTs = written.newest,
            // The band hands back its whole ring buffer regardless of the date we asked for, so
            // `oldest` IS the buffer floor and these three are a direct reading of it.
            bufferDepthSec = BandCensus.bufferDepthSec(written.oldest, written.newest),
            floorAdvancedSec = BandCensus.floorAdvancedSec(previous?.oldestLocalTs, written.oldest),
            lostWindowSec = BandCensus.lostWindowSec(previous?.newestLocalTs, written.oldest),
            maxFrameBytes = machine.maxFrameBytes,
            minFrameBytes = machine.minFrameBytes,
            elapsedMs = System.currentTimeMillis() - began,
            end = end.name,
            error = if (end == BandStreamEnd.IDLE_TIMEOUT) "no frame for ${BandGattClient.FRAME_IDLE_TIMEOUT_MS}ms" else null,
        )
    }

    /**
     * The last real read of each stream, newest first.
     *
     * Scans back over recent syncs rather than taking the single latest, because a stream is only
     * evidence about itself: a `streams=hr` probe says nothing about where HRV's floor was, and
     * treating it as the previous read would report a spurious loss on the next full sync.
     */
    private suspend fun previousReadsPerStream(db: AppDatabase): Map<String, BandStreamStat> {
        val out = mutableMapOf<String, BandStreamStat>()
        for (row in db.bandSyncDao().recent(PREVIOUS_READ_LOOKBACK)) {
            if (!row.ok) continue
            val stats = runCatching {
                statsJson.decodeFromString<Map<String, BandStreamStat>>(row.statsJson)
            }.getOrNull() ?: continue
            for ((key, stat) in stats) {
                if (key in out || stat.error != null || stat.newestLocalTs == null) continue
                out[key] = stat
            }
        }
        return out
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

    /**
     * Header, this sync's banked record lines, census — in that order, census LAST.
     *
     * The order is the guarantee: a census line in the file means every record line before it
     * landed, which is what lets [BandArchiveRepair] treat "has a census" as "is archived".
     */
    private fun writeArchive(
        syncId: Long,
        request: BandSyncRequest,
        stats: Map<String, BandStreamStat>,
        startedAt: Long,
        zone: ZoneId,
        firmware: String?,
        battery: Int?,
        mtu: Int,
        ok: Boolean,
        error: String?,
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
                ok = ok,
                ms = System.currentTimeMillis() - startedAt,
                streams = stats,
                backup = error,
            ),
        )
        // The record lines were collected as each stream landed; header and census bracket them.
        writer.appendAll(listOf(header) + archiveLines.getOrDefault(syncId, emptyList()) + listOf(census), LocalDate.now())
        archiveLines.remove(syncId)
        null
    }.getOrElse { "backup:failed:${it.message}" }

    /**
     * Re-emit the rows of any sync whose lines never reached the file — see [BandArchiveRepair].
     *
     * Runs on every sync, not on demand, because the failure it repairs is silent: the database and
     * the archive drift apart with nothing to show for it, and the drift is only visible to someone
     * counting both. It is cheap — a `startsWith` scan for the few dozen bracket lines in the month
     * file, then a query that returns nothing at all in the normal case.
     *
     * Note that it heals across the `backup=false` switch too: a sync run with the archive off still
     * commits its rows, and a later sync with it on will write them. That is the right way round —
     * the invariant is "every row in the DB has a line in the file", and `backup` says whether this
     * run writes the file, not whether its readings deserve to be in it.
     */
    private suspend fun repairArchive(
        db: AppDatabase,
        request: BandSyncRequest,
        zone: ZoneId,
    ): String? = runCatching {
        val files = BandArchiveRepair.monthlyFiles(request.backupDir)
        val oldestMonth = BandArchiveRepair.oldestMonth(files) ?: return@runCatching null
        val archived = BandArchiveRepair.archivedSyncIds(files)
        val recent = db.bandSyncDao().recent(BandArchiveRepair.LOOKBACK_SYNCS).map { it.id to it.startedAt }
        val missing = BandArchiveRepair.missingSyncIds(recent, archived, oldestMonth) { startedAt ->
            val date = java.time.Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
            date.year * 100 + date.monthValue
        }
        if (missing.isEmpty()) return@runCatching null

        val lines = mutableListOf<String>()
        for (ids in missing.chunked(BandArchiveRepair.ID_CHUNK)) {
            lines += db.bandSampleDao().forSyncs(ids).map(BandJsonlCodec::encode)
            lines += db.bandDailyDao().forSyncs(ids).map(BandJsonlCodec::encode)
            lines += db.bandSleepDao().forSyncs(ids).map(BandJsonlCodec::encode)
        }
        // Records first, marker last — the same rule the census follows, for the same reason.
        val marker = BandJsonlCodec.encode(
            BandJsonlRepair(
                at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(java.time.ZonedDateTime.now(zone)),
                ids = missing.sorted(),
                n = lines.size,
            ),
        )
        BandJsonlWriter(request.backupDir).appendAll(lines + marker, LocalDate.now())
        "repaired:${lines.size} row(s) from ${missing.size} sync(s)"
    }.getOrElse { "repair:failed:${it.message}" }

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

    /**
     * Everything a Profile needs in order to decide whether to warn.
     *
     * Read back out of the database rather than returned from a run, because the case that matters
     * most is the one where the run **failed**: a sync that could not connect still has to be able to
     * say how long it has been since one did, and how much headroom is left before that becomes loss.
     */
    suspend fun status(db: AppDatabase): BandStatus = withContext(Dispatchers.IO) {
        val rows = db.bandSyncDao().recent(STATUS_LOOKBACK)
        val decoded = rows.filter { it.ok }.mapNotNull { row ->
            val stats = runCatching {
                statsJson.decodeFromString<Map<String, BandStreamStat>>(row.statsJson)
            }.getOrNull() ?: return@mapNotNull null
            row to stats
        }
        val last = decoded.firstOrNull()
        val evicting = decoded.flatMap { (_, stats) ->
            stats.filterValues { it.error == null && it.floorAdvancedSec > 0 }.keys
        }.toSet()
        val lastStats = last?.second.orEmpty()
        val lost = lastStats.filterValues { it.error == null && it.lostWindowSec > 0 }
        // The freshest charge reading from ANY recent attempt, successful or not: the band is asked
        // for it immediately after connecting, so a sync that later failed part-way through still
        // read a perfectly good battery level, and discarding it would age the number for nothing.
        val battery = rows.firstOrNull { it.battery != null }
        BandStatus(
            lastSuccessAtMillis = last?.first?.startedAt,
            headroom = BandCensus.tightest(lastStats, evicting),
            lostSec = lost.values.sumOf { it.lostWindowSec },
            lostStreams = lost.keys.sorted(),
            batteryPct = battery?.battery,
            batteryAtMillis = battery?.startedAt,
        )
    }

    private const val INFO_REPLY_TIMEOUT_MS = 3_000L
    private const val INFO_REPLY_ATTEMPTS = 4

    /**
     * How far back to look for a stream's previous read.
     *
     * Generous rather than tight: 同期状態 fires an hr-only probe whenever 白い熊 checks the status,
     * so several consecutive rows can be partial, and a short window would silently give up on the
     * quieter streams.
     */
    private const val PREVIOUS_READ_LOOKBACK = 40
    private const val STATUS_LOOKBACK = 40
}

/** The answer to "is anything about to be lost, and has anything been?" — see [BandSyncEngine.status]. */
data class BandStatus(
    val lastSuccessAtMillis: Long?,
    val headroom: BandHeadroom?,
    val lostSec: Long,
    val lostStreams: List<String>,
    /** The band's own charge, as of [batteryAtMillis]. Null until a sync has read one. */
    val batteryPct: Int? = null,
    /**
     * When that charge was read.
     *
     * It travels WITH the percentage on purpose. The band is only asked while a sync is connected,
     * so a bare "76 %" on screen could be six hours old and reading as current — the number is
     * meaningless without the moment it belongs to.
     */
    val batteryAtMillis: Long? = null,
) {
    /** Hours since the charge was read, or null if none has been. */
    fun batteryAgeHours(nowMillis: Long): Double? =
        batteryAtMillis?.let { (nowMillis - it) / 3_600_000.0 }

    /** Hours since the last successful sync, or null if there has never been one. */
    fun ageHours(nowMillis: Long): Double? =
        lastSuccessAtMillis?.let { (nowMillis - it) / 3_600_000.0 }

    /**
     * How much of the shallowest buffer has been consumed since the last successful sync, as a
     * percentage. 100 means the next record to fall off the end is one we have never seen.
     *
     * Null only when there is nothing to divide by — no successful sync yet, or no measured depth —
     * in which case there is no pressure to report rather than infinite pressure.
     */
    fun pressurePct(nowMillis: Long): Int? {
        val depthHours = headroom?.depthSec?.takeIf { it > 0 }?.div(3600.0) ?: return null
        val age = ageHours(nowMillis) ?: return null
        return ((age / depthHours) * 100).toInt().coerceAtLeast(0)
    }
}

/** yyyyMMddHHmmss, the same shape the records use. */
fun BandLocalTime.toLocalTs(): Long =
    year.toLong() * 10_000_000_000L +
        month.toLong() * 100_000_000L +
        day.toLong() * 1_000_000L +
        hour.toLong() * 10_000L +
        minute.toLong() * 100L +
        second.toLong()
