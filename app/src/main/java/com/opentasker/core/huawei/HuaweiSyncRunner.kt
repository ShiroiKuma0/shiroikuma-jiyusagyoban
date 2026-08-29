package com.opentasker.core.huawei

import android.content.Context
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.HuaweiSampleEntity
import com.opentasker.core.storage.HuaweiSleepEntity
import com.opentasker.core.storage.HuaweiSyncEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TimeZone
import kotlin.random.Random

/**
 * Driving a whole Huawei sync: connect, handshake, walk the windows, write, disconnect.
 *
 * This file — and not [HuaweiSyncEngine] — is where `Context`, Room and the radio live. The split is
 * deliberate: `HuaweiSyncEngine` holds the record-to-sample conversion, which is the layer that can
 * silently corrupt data, and it stays pure so it can be unit-tested. Folding orchestration into it
 * would produce the shape `BandSyncEngine` has, where the tested logic and the untestable plumbing
 * share one file. `HuaweiSafetyGuardTest` enforces the split.
 *
 * **Every exit path writes the `huawei_syncs` row.** Success, failure, timeout, exception. A row
 * left with `finishedAt = 0` is indistinguishable from a sync still in flight, and the Hume band
 * already taught this lesson expensively: rows were banked and then thrown away, and syncs 28 and 41
 * were lost with nothing on record to say so.
 *
 * **The link is never held.** A standing Bluetooth session on this phone once drained 1322 mAh in a
 * day, and the band is single-connection: while we hold it, nothing else can reach it.
 */
object HuaweiSyncRunner {

    /** Process-wide. A second request is refused outright rather than queued — see [Outcome.Skipped]. */
    private val running = Mutex()

    /** App-scoped, so a sync started from the window survives that window closing. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Request(
        val address: String,
        /** Epoch-second windows, newest first, from [HuaweiSyncArgs.resolve]. */
        val windows: List<LongRange>,
        val timeoutSec: Int,
        val maxRecords: Int = 4096,
        /** "window" · "action" · "pair" — recorded so a surprising row can be traced to its caller. */
        val source: String,
    )

    sealed interface Outcome {
        data class Ok(val syncId: Long, val summary: String, val warning: String?) : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Serve for at least this long after configuring: the reference run's watch face appeared about
     * ten seconds after the configuration set finished, so leaving earlier risks cutting the setup
     * short even if the band happens to pause.
     */
    /**
     * How far back to ask for sleep, regardless of how little the sample sync is fetching.
     *
     * Three days rather than one so a couple of missed syncs still recover the nights in between,
     * and it is cheap: the band answers with one file per night, not per minute.
     */
    private const val SLEEP_LOOKBACK_SEC = 3L * 86_400

    private const val SERVE_MIN_MS = 20_000L

    /**
     * Treat this much silence as "the band is done with us". The longest gap between messages in
     * the reference run was 9 s, so this clears it without waiting out chatter that is no longer
     * part of setting up.
     */
    private const val SERVE_QUIET_MS = 12_000L

    /** 16 lowercase hex characters, minted once and then reused so the band keeps recognising us. */
    private fun mintAuthId(): String =
        (1..16).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")

    /**
     * Open a session, hand it to [block], and always close it.
     *
     * For diagnostics that need to ask the band things no ordinary sync asks. It takes the same
     * process-wide lock as a sync — the band is single-connection, and a probe racing a scheduled
     * sync would produce garbage in both.
     */
    suspend fun <T> withSession(
        context: Context,
        address: String,
        timeoutMs: Long = SESSION_TIMEOUT_MS,
        block: suspend (HuaweiSession, HuaweiClient) -> T,
    ): Result<T> {
        val client = HuaweiRfcommClient(context)
        // The lock, the watchdog, the close and the unlock all live in HuaweiSessionGuard — pure
        // Kotlin, so the promises they make can actually be tested against a transport that
        // misbehaves. They were made here, untested, and two of them did not hold.
        return HuaweiSessionGuard.guard(running, client, timeoutMs, scope) {
            // INSIDE the guard, which it used to not be. `connect()` blocks uninterruptibly, so a
            // band that is switched off or already speaking to somebody else parked the session
            // before any watchdog covering the session had been armed.
            client.open(address)?.let { throw IllegalStateException(it) }
            val session = HuaweiSession(client)
            val api = HuaweiClient(session)
            val link = api.linkParams()
            api.deviceStatus()
            val authId = HuaweiSettings.authIdSelf(context)
                ?: throw IllegalStateException("not bound — pair the band first")
            api.securityNegotiation(link.deviceSupportType, authId, android.os.Build.MODEL)
            val token = HuaweiSettings.authToken(context)
                ?: throw IllegalStateException("no stored token — pair the band first")
            api.authenticate(authId, token, Random.nextLong(1L, Long.MAX_VALUE))
            // The six Huawei Health sends on EVERY reconnect, without which the band never speaks.
            //
            // Until 2026-08-25 this went straight from authenticate to the work, and in that state
            // the band answers everything we ask and volunteers NOTHING — no weather pull, no
            // satellite request, no RR stream. Every listening window we ran was silent, through a
            // link proven alive each 30 s, while 白い熊 pressed buttons on the band for nothing.
            //
            // Health follows the same handshake with these six and the band's first unsolicited
            // frame lands 104 ms later. Sending them here turned 90 s of silence into 30 frames:
            // 0x0F/0x04 (send me weather) immediately, DataSync, a battery event, and 0x19/0x03 RR
            // intervals every five seconds. That is the band behaving as it does for Health.
            //
            // All six are reads or declarations Health issues on every ordinary connect; none
            // writes a setting, a clock, a language or any wizard state. They cost ~200 ms.
            runCatching { api.announceCompanion() }
            block(session, api)
        }
    }

    /**
     * How long any single session may run before the socket is closed under it.
     *
     * Generous, because a watch face is a megabyte over Bluetooth — but finite, because the failure
     * it guards against is a permanently held lock, which looks like a broken band.
     */
    private const val SESSION_TIMEOUT_MS = 420_000L

    /**
     * Set the language shown ON THE BAND.
     *
     * The band has no language menu of its own — the companion owns the setting — so this command
     * is the only way to change it short of a factory reset, and even that only holds until some
     * companion pushes a locale over it.
     *
     * Stored on success so every later pair re-asserts it. Stored ONLY on success: recording a
     * language the band refused would make the app claim a state the wrist disagrees with, and
     * would keep re-asserting the refusal forever.
     */
    suspend fun setBandLocale(
        context: Context,
        address: String,
        locale: String,
        imperial: Boolean,
    ): Result<Boolean> = withSession(context, address) { _, api ->
        // Through the announce, because a bare push is ACKed and ignored — see
        // [HuaweiClient.pushLocale]. Still stored on the ACK rather than on proof, because there
        // may be no proof to be had: what we store is "the language this phone asked for", and the
        // dialog is careful to present it as exactly that.
        api.pushLocale(HuaweiSettings.deviceName(context) ?: "HUAWEI Band", locale, imperial)
            .also { if (it) HuaweiSettings.setBandLocale(context, locale) }
    }

    /**
     * Fetch the RR-interval file and store its windows.
     *
     * **Everything the band recorded is stored, including windows Huawei Health would not display.**
     * Discarding at ingest is irreversible, and the count that decides publishability is stored
     * alongside — so the presentation layer can apply Health's threshold while the evidence for
     * revisiting it survives. Doing it the other way round would mean re-wearing the band to change
     * our minds.
     *
     * @return how many windows were written.
     */
    private suspend fun storeRri(
        db: AppDatabase,
        session: HuaweiSession,
        syncId: Long,
        fromSeconds: Long,
        toSeconds: Long,
    ): Int {
        val file = HuaweiFileClient(session).fetch(
            HuaweiFileClient.RRI_DATA, HuaweiFileClient.RRI_TYPE, fromSeconds, toSeconds,
        )
        if (file !is HuaweiFileClient.Result.Data) return 0
        val windows = HuaweiRri.parse(file.bytes)
        if (windows.isEmpty()) return 0
        val rows = windows.flatMap { w ->
            w.raw.map { (field, value) ->
                HuaweiSampleEntity(HuaweiRriKeys.metricFor(field), w.startSeconds, value, syncId)
            }
        }
        rows.chunked(500).forEach { db.huaweiSampleDao().upsert(it) }
        return windows.size
    }

    /**
     * Fetch the night file and store its segments.
     *
     * Stored per SEGMENT, keyed by start time, so re-reading a night the band still holds overwrites
     * it rather than doubling it — which matters because every sync asks for an overlapping window
     * on purpose.
     *
     * @return how many segments were written; 0 when the band held no night for the window.
     */
    private suspend fun storeSleep(
        db: AppDatabase,
        session: HuaweiSession,
        syncId: Long,
        fromSeconds: Long,
        toSeconds: Long,
    ): Int {
        val file = HuaweiFileClient(session).fetch(
            HuaweiFileClient.SEQUENCE_DATA, HuaweiFileClient.SEQUENCE_TYPE,
            fromSeconds, toSeconds, id = HuaweiFileClient.SLEEP_STREAM_ID,
        )
        if (file !is HuaweiFileClient.Result.Data) return 0
        // EVERY night in the file, not the first. The file is append-only, so taking one session
        // pinned the app to the oldest night it had ever seen — see HuaweiSleep.parseAll.
        val nights = HuaweiSleep.parseAll(file.bytes)
        if (nights.isEmpty()) return 0
        nights.forEach { night ->
            db.huaweiSleepDao().upsert(
                night.segments.map {
                    HuaweiSleepEntity(
                        startSeconds = it.startSeconds,
                        durationSeconds = it.durationSeconds,
                        stage = it.stage.code,
                        sessionStart = night.startSeconds,
                        sessionEnd = night.endSeconds,
                        syncId = syncId,
                    )
                },
            )
        }
        return nights.sumOf { it.segments.size }
    }

    /**
     * Install a watch face.
     *
     * The name is what the band files it under and Health always uses `<assetId>_<version>`, so the
     * two identifiers are derived from the filename rather than asked for separately — a face called
     * `7185695173_2.1.1.bin` carries everything the band needs.
     */
    /** One recorded walk, with wherever its track was written. */
    data class Walk(
        val summary: HuaweiWorkout.Summary,
        val trackPoints: Int,
        val gpxPath: String?,
        val note: String? = null,
    )

    /**
     * List the band's recorded workouts and, for the outdoor ones, fetch and decode their tracks.
     *
     * One session for all of it: the band serves a single connection, and a walk is a list request,
     * a summary request and a file transfer per workout — three round trips each that would each
     * cost a fresh handshake if this were split up.
     *
     * A workout whose track will not decode still yields its summary. Losing a route is a shame;
     * losing the fact that 白い熊 walked five kilometres because its coordinates would not parse
     * would be worse, and it is the kind of failure that hides.
     */
    suspend fun fetchWorkouts(
        context: Context,
        address: String,
        fromSeconds: Long,
        toSeconds: Long,
        outDir: java.io.File?,
    ): Result<List<Walk>> = withSession(context, address) { session, _ ->
        val cfg = HuaweiCommands
        val listed = HuaweiWorkout.parseList(
            session.decrypt(
                session.request(cfg.SVC_WORKOUT, cfg.WORKOUT_LIST, cfg.workoutList(fromSeconds, toSeconds)),
            ),
        )
        outDir?.mkdirs()
        listed.map { entry ->
            val summary = runCatching {
                HuaweiWorkout.parseSummary(
                    session.decrypt(
                        session.request(cfg.SVC_WORKOUT, cfg.WORKOUT_TOTALS, cfg.workoutTotals(entry.number)),
                    ),
                )
            }.getOrNull() ?: HuaweiWorkout.Summary(entry.number)

            if (!entry.hasTrack) return@map Walk(summary, 0, null, "no track recorded")

            val file = runCatching {
                HuaweiFileClient(session).fetch(
                    cfg.gpsTrackName(entry.number), HuaweiFileClient.GPS_TYPE, 0, 0,
                )
            }.getOrNull()
            if (file !is HuaweiFileClient.Result.Data) {
                return@map Walk(summary, 0, null, "the band would not send the track")
            }
            val track = HuaweiGpsTrack.decode(file.bytes)
                ?: return@map Walk(summary, 0, null, "${file.bytes.size} B of track that did not decode")

            // One directory per walk, holding the raw bytes as well as the route. Keeping the raw
            // file is what made the header off-by-one fixable in an afternoon: a GPX regenerated
            // from a bad decode is a walk that never happened, and only the .bin can be re-read.
            val stored = outDir?.let { root ->
                HuaweiWalkLibrary.write(
                    root = root,
                    number = entry.number,
                    startSeconds = summary.startSeconds ?: track.startSeconds,
                    endSeconds = summary.endSeconds,
                    distanceMetres = summary.distanceMetres,
                    kind = summary.kind,
                    points = track.points.size,
                    raw = file.bytes,
                    gpx = HuaweiGpsTrack.toGpx(track, "${summary.kind} ${entry.number}"),
                )
            }
            Walk(summary, track.points.size, stored?.gpx?.absolutePath)
        }
    }

    /** What the band is holding, and how much room is left. One short session. */
    suspend fun listWatchFaces(
        context: Context,
        address: String,
    ): Result<HuaweiUploadClient.FaceStore?> = withSession(context, address) { session, _ ->
        HuaweiUploadClient(session).listWatchFaces()
    }

    /**
     * Remove one face from the band.
     *
     * Returns false rather than throwing when the band still lists the face afterwards — a delete
     * that did not happen is a normal answer here, not an error, and the caller shows the list.
     */
    suspend fun deleteWatchFace(
        context: Context,
        address: String,
        assetId: String,
        version: String,
    ): Result<Boolean> = withSession(context, address) { session, _ ->
        HuaweiUploadClient(session).deleteWatchFace(assetId, version)
    }

    /**
     * Bring a face the band already holds to the front, without sending it again.
     *
     * See `HuaweiUploadClient.activate`: the protocol has no select command, so this is the install
     * command sent for a face already present, and the return value is the band's own list agreeing
     * that the face is now the one on screen.
     */
    suspend fun activateWatchFace(
        context: Context,
        address: String,
        assetId: String,
        version: String,
    ): Result<Boolean> = withSession(context, address) { session, _ ->
        HuaweiUploadClient(session).activate(assetId, version)
    }

    /**
     * @param evict a face to remove first, as (assetId, version) — the band being full is answered
     *   by 白い熊 choosing what to give up, and the removal has to happen in the SAME session as the
     *   install that needs the slot. Two sessions would mean two connections and a window in which
     *   the band is one face lighter for no reason.
     */
    suspend fun uploadWatchFace(
        context: Context,
        address: String,
        file: java.io.File,
        evict: Pair<String, String>? = null,
        onProgress: (Int) -> Unit = {},
    ): Result<HuaweiUploadClient.Outcome> = withSession(context, address) { session, _ ->
        val name = file.name.removeSuffix(".bin")
        val assetId = name.substringBefore('_')
        val version = name.substringAfter('_', "")
        require(version.isNotEmpty()) { "$name is not <assetId>_<version>" }
        // The metadata sidecar is not optional: the band needs the face's signed store record
        // before it will take, or keep, the bytes.
        val meta = java.io.File(file.parentFile, "$name.json")
        require(meta.isFile) { "missing ${meta.name} beside the face — capture it with the file" }
        val client = HuaweiUploadClient(session)
        if (evict != null) {
            // Refuse to go on if the removal did not take. Installing anyway would hit the same
            // full band, and 白い熊 would have given up a face for nothing.
            val gone = client.deleteWatchFace(evict.first, evict.second)
            if (!gone) {
                return@withSession HuaweiUploadClient.Outcome(
                    ok = false, bytesSent = 0, blocks = 0,
                    message = "${evict.first} is still on the band — nothing was installed",
                    store = client.listWatchFaces(),
                )
            }
        }
        client.installWatchFace(
            assetId = assetId,
            version = version,
            bytes = file.readBytes(),
            metaJson = meta.readText(),
            onProgress = onProgress,
        )
    }

    /**
     * Push weather (and optionally a position) to the band.
     *
     * Both are sends rather than requests: the band displays what it was last told and answers
     * neither reliably, so this reports what it managed to send rather than pretending to a
     * confirmation the protocol does not offer.
     */
    suspend fun pushWeather(
        context: Context,
        address: String,
        place: String,
        temperatureC: Int,
        humidity: Int?,
        highC: Int?,
        lowC: Int?,
        latitude: Double?,
        longitude: Double?,
        uvIndex: Int? = null,
        windKmh: Int? = null,
        /**
         * The hourly series, first entry = the hour we are in.
         *
         * Timestamps are ignored and restamped by [HuaweiCommands.padHours], so a caller assembling
         * these from parallel arrays does not have to know the hour boundary.
         */
        hourlyPoints: List<HuaweiCommands.HourlyPoint> = emptyList(),
        /** Days, starting today. Empty falls back to the single high/low above. */
        dailyDays: List<HuaweiCommands.DailyPoint> = emptyList(),
        /** The first hourly entry's epoch, when the source knows its own alignment better than we do. */
        hourStartOverride: Long? = null,
        /** How many of [hourlyPoints] / [dailyDays] were real rather than carried across a gap. */
        realHourly: Int? = null,
        realDaily: Int? = null,
        rawPayload: ByteArray? = null,
        rawForecast: ByteArray? = null,
        enableFirst: Boolean = false,
        readBack: Boolean = false,
        preReads: Boolean = false,
    ): Result<String> = withSession(context, address) { session, _ ->
        val now = System.currentTimeMillis() / 1000
        if (latitude != null && longitude != null) {
            runCatching {
                session.send(
                    HuaweiCommands.SVC_LOCATION, HuaweiCommands.LOCATION_PUSH,
                    HuaweiCommands.location(now, latitude, longitude),
                )
            }
        }
        // The band's own answer decides this, not the fact that bytes left the phone.
        //
        // This used to send, poll for two seconds, throw away whatever came back and return a
        // summary built from the values it had just SENT — so "Praha 21°C · 39% · 12–23°C" was a
        // restatement of the input dressed as a confirmation, and 白い熊 read "Weather pushed" for
        // two days while the band's screen said "Refreshed: 2026-08-23" and showed no current
        // temperature at all. WEATHER_PUSH is not in FIRE_AND_FORGET: the band is expected to
        // answer, and the comment claiming its silence was normal was contradicted by that list.
        val sent = buildString {
            append("$place ${temperatureC}°C")
            humidity?.let { append(" · ${it}%") }
            if (highC != null && lowC != null) append(" · $lowC–$highC°C")
            if (latitude != null && longitude != null) append(" · position sent")
        }
        // The exact bytes Health sends, when we are replaying rather than composing.
        //
        // Our push differs from Health's captured one in five ways and the capture contains no
        // negative control, so it cannot say which of them costs the record. Composing a "corrected"
        // payload from five simultaneous guesses would repeat this morning's mistake. Replaying
        // Health's own 59 bytes verbatim tests the SHAPE alone; once that is known to land, the
        // fields can be substituted back one at a time.
        // Health's tag 12 is not "now": it is 07:00 local, and it equals daily[0]'s own timestamp
        // exactly, with the following days stepping 86400 s from it. Both are built from this one
        // value so they cannot drift apart.
        val zone = java.time.ZoneId.systemDefault()
        val dayMarker = java.time.LocalDate.now(zone).atStartOfDay(zone).plusHours(7).toEpochSecond()
        val payload = rawPayload ?: HuaweiCommands.weather(
            place, temperatureC, dayMarker, humidity, highC, lowC, uvIndex, windKmh,
        )
        // Health reads three things off the band BEFORE it pushes anything. We never have.
        //
        // Behind a flag, not on by default, because it is a hypothesis: on 2026-08-25 the forecast
        // was found never to have been applied — not once, not even when byte-identical to Health's
        // — while the push beside it always was. A flag lets one build test both orders from the
        // task instead of costing a second build to answer.
        val pre = StringBuilder()
        if (preReads) {
            for (cmd in HuaweiCommands.WEATHER_READS) {
                val r = runCatching {
                    val f = session.request(HuaweiCommands.SVC_WEATHER, cmd, HuaweiProtocol.tlv(1), timeoutMs = 4_000)
                    session.decrypt(f).joinToString(",") { "%d:%s".format(it.tag, HuaweiCrypto.upperHex(it.value)) }
                }.getOrElse { it::class.java.simpleName }
                pre.append(" 0x%02X=%s".format(cmd, r))
            }
        }
        if (enableFirst) {
            runCatching {
                session.send(HuaweiCommands.SVC_WEATHER, HuaweiCommands.WEATHER_DISABLE, HuaweiCommands.weatherEnable())
            }
        }
        val reply = runCatching {
            session.request(
                HuaweiCommands.SVC_WEATHER, HuaweiCommands.WEATHER_PUSH, payload,
            )
        }.getOrElse {
            throw IllegalStateException(
                "$sent — but the band never answered the push (${it.message ?: it::class.java.simpleName}). " +
                    "Nothing here can say it landed, so this is not reported as sent.",
            )
        }
        // 0x0F/0x02, 0x06 and 0x0A are READS. The earlier sweep called them all "refuses an empty
        // payload" because it sent an empty PAYLOAD; the read form is tag 1 with a zero-length
        // VALUE, which is a different thing on the wire. If any of them reflects what the band is
        // actually holding, it is the oracle that stops this needing 白い熊's eyes on the wrist.
        val reads = StringBuilder()
        if (readBack) {
            for (cmd in listOf(0x02, 0x06, 0x0A)) {
                val r = runCatching {
                    val f = session.request(HuaweiCommands.SVC_WEATHER, cmd, HuaweiProtocol.tlv(1), timeoutMs = 4_000)
                    session.decrypt(f).joinToString(",") { "%d:%s".format(it.tag, HuaweiCrypto.upperHex(it.value)) }
                }.getOrElse { it::class.java.simpleName }
                reads.append(" 0x%02X=%s".format(cmd, r))
            }
        }
        // The forecast, and the little frame Health sends after it.
        //
        // Health sends 0x0F/0x08 within 6 ms of EVERY push — 1290 bytes, two slices, 24 hourly and
        // 15 daily entries — and then 0x0F/0x0B. We have never sent either. With the current-weather
        // push now proven not to land on its own, even byte-for-byte, an incomplete record is the
        // remaining structural difference: the band may simply not show a reading with no forecast
        // behind it.
        // The forecast is not optional: the band draws the current hour out of THIS, not out of the
        // push above. Without it the reading is discarded and the screen keeps whatever it had.
        //
        // We have one reading, so we send one hour and one day. Health sends 24 and 15; padding to
        // match would mean inventing weather. The hour is rounded DOWN to the hour boundary because
        // every entry Health sends sits exactly on one, and the band picks the entry nearest now.
        var realHours = 0
        var realDays = 0
        val builtForecast = rawForecast ?: run {
            val hourStart = hourStartOverride ?: (now / 3600) * 3600
            // Sunrise and sunset are ASTRONOMY, not weather: computed here from the position we
            // were given, by the same calculator the sun contexts use. Whether the band reads them
            // or works them out itself from the position frame is still unknown — the only test of
            // that rode in a forecast the band refused, so it proved nothing either way.
            val today = java.time.LocalDate.now(zone)
            fun sunAt(event: String): Long? {
                if (latitude == null || longitude == null) return null
                val minute = com.opentasker.core.contexts.SunEventCalculator
                    .eventMinuteOfDay(today, latitude, longitude, event, zone) ?: return null
                return today.atStartOfDay(zone).plusMinutes(minute.toLong()).toEpochSecond()
            }
            // With no series we have exactly one reading, and padHours repeats it across the 24
            // the band demands. The band then shows a flat line, which is what a single reading
            // honestly looks like stretched over a day — the fix is a real series, not a curve
            // invented here.
            val known = hourlyPoints.ifEmpty {
                listOf(HuaweiCommands.HourlyPoint(hourStart, temperatureC, uvIndex = uvIndex ?: 0))
            }
            realHours = minOf(realHourly ?: known.size, HuaweiCommands.FORECAST_HOURS)
            val days = dailyDays.ifEmpty {
                if (highC == null || lowC == null) emptyList()
                else listOf(
                    HuaweiCommands.DailyPoint(
                        epochSeconds = dayMarker, highC = highC, lowC = lowC,
                        sunriseSeconds = sunAt("sunrise"), sunsetSeconds = sunAt("sunset"),
                    ),
                )
            }
            if (days.isEmpty()) {
                throw IllegalStateException(
                    "$sent — no high/low, so there is no day to put in the forecast, and the band " +
                        "refuses a forecast with no days (115001). Nothing was pushed.",
                )
            }
            realDays = realDaily ?: days.size
            HuaweiCommands.forecast(
                HuaweiCommands.padHours(known, hourStart),
                HuaweiCommands.padDays(days, dayMarker),
            )
        }
        // The forecast's own answer, which this used to throw on the floor.
        //
        // `runCatching { sendLarge(...) }` and nothing else is why a forecast that never applied
        // could look identical to one that did, for two days: the bytes left the phone, the call
        // returned, and no one asked the band what it thought. Whatever it says now travels back in
        // the result string, so the next failure is diagnosable from the task rather than from
        // 白い熊's wrist.
        val forecastCode = runCatching {
            session.sendLarge(HuaweiCommands.SVC_WEATHER, HuaweiCommands.WEATHER_FORECAST, builtForecast)
            session.awaitFrame(HuaweiCommands.SVC_WEATHER, HuaweiCommands.WEATHER_FORECAST, 6_000)
        }.getOrNull()?.let { f ->
            session.decrypt(f).firstOrNull { it.tag == HuaweiProtocol.TAG_RESULT }
                ?.let { HuaweiProtocol.bytesToInt(it.value) }
        }
        runCatching {
            session.send(
                HuaweiCommands.SVC_WEATHER, HuaweiCommands.WEATHER_PUSH_DONE,
                HuaweiCommands.weatherPushDone(),
            )
        }
        val code = session.decrypt(reply)
            .firstOrNull { it.tag == HuaweiProtocol.TAG_RESULT }
            ?.let { HuaweiProtocol.bytesToInt(it.value) }
        when (code) {
            null -> "$sent · the band answered without a result code"
            // NOT "accepted": 白い熊 confirmed 2026-08-25 that this same success code came back
            // while the band's weather screen stayed blank and dated 2026-08-23. The band
            // acknowledges the FRAME; whether it stored or rendered the reading is not
            // observable from here, and saying otherwise is the same lie in a smaller font.
            HuaweiProtocol.RESULT_SUCCESS ->
                "$sent · frame acknowledged (the band does not confirm display)" +
                    " · forecast=" + when (forecastCode) {
                        null -> "no answer"
                        HuaweiProtocol.RESULT_SUCCESS ->
                            if (rawForecast != null) "accepted"
                            // The band takes 24 hours or nothing, and we rarely know 24. Saying how
                            // many were real keeps a flat line from reading as a forecast.
                            else "accepted (${HuaweiCommands.FORECAST_HOURS}h/$realHours real, " +
                                "${maxOf(HuaweiCommands.FORECAST_DAYS_MIN, realDays)}d/$realDays real)"
                        else -> throw IllegalStateException(
                            "$place ${temperatureC}°C — the band REFUSED the forecast with $forecastCode, " +
                                "so it discarded the reading with it and the screen did not change.",
                        )
                    } +
                    (if (pre.isEmpty()) "" else " · pre$pre") +
                    (if (reads.isEmpty()) "" else " · reads$reads")
            else -> throw IllegalStateException("$sent — the band REFUSED the push with result $code")
        }
    }

    /** What one GNSS serving attempt did. */
    data class GnssOutcome(
        val asked: Boolean,
        val source: String?,
        val served: List<String>,
        val bytes: Int,
        val detail: String,
        /** When the band raised its request, and how long the watch had been waiting by then. */
        val caughtAtMs: Long = 0L,
        val waitedMs: Long = 0L,
    )

    /**
     * Serve the band its GNSS assistance data.
     *
     * **This inverts the usual roles.** Everywhere else in this file we ask and the band answers;
     * here the band asks (`0x1F/0x01`), tells us what it wants, and then drives a pull over `0x1C`
     * whose order we do not choose. So this is a small server, and it runs until the band says the
     * last file is done or goes quiet.
     *
     * We deliberately do **not** fetch what the band names. Its request string points at Huawei's
     * own cloud (`higeo/v1/gnssinfo?...`), and honouring an arbitrary URL a device hands us is the
     * `hw.wearable.httpProxy` hazard in a different costume — it would make this app the band's
     * general HTTP client. Instead the caller supplies files it already has, and the band gets
     * those. What it asked for is reported back so the caller can see it, and ignored.
     *
     * @param files name → contents. The name is what the band sees in the listing and asks for by,
     *   so it must be the band's own (`HW_AGNSS_RTCM_33` and friends).
     */
    suspend fun serveGnss(
        context: Context,
        address: String,
        files: Map<String, ByteArray>,
        waitForAskMs: Long = 20_000,
        announce: Boolean = true,
        /**
         * Called as the work happens, so a scene can show it live.
         *
         * The transfer is minutes of silence otherwise, and the band's own screen sits at 0 % the
         * whole time — 白い熊 needs to see which side is waiting for whom.
         */
        onProgress: (phase: String, line: String?) -> Unit = { _, _ -> },
        /**
         * Checked once a second while waiting, so a long watch can be called off.
         *
         * Cooperative rather than an interrupt: the wait is the only place this can stop safely.
         * Aborting mid-transfer would leave the band holding a half-written file it believes is
         * whole, and its CRC check happens at the END — so it would not notice.
         */
        shouldCancel: () -> Boolean = { false },
        /** Once a second while waiting, with how long the watch has been running. */
        onTick: (elapsedMs: Long) -> Unit = {},
    ): Result<GnssOutcome> = withSession(context, address) { session, _ ->
        require(files.isNotEmpty()) { "no files to serve" }
        val log = StringBuilder()
        var source: String? = null

        // The band asks on its own when its data is stale. If it does not ask, `announce` sends the
        // ready signal unprompted — the capture shows the band opening the transfer 24 ms after
        // that signal, so it may be the whole trigger. Untested; hence a flag rather than a claim.
        // Waited in one-second slices rather than one long block, so the countdown on screen is a
        // real clock and not a guess. Same total wait; the only difference is that it can be seen.
        var ask: HuaweiProtocol.Frame? = null
        val deadline = System.currentTimeMillis() + waitForAskMs
        var cancelled = false
        val watchStart = System.currentTimeMillis()
        while (ask == null && System.currentTimeMillis() < deadline) {
            if (shouldCancel()) {
                cancelled = true
                onProgress("Cancelled", "Called off before the band asked")
                break
            }
            // Elapsed, not remaining. A countdown implies a deadline matters; what 白い熊 needs to
            // see is how long this has been sitting there, and since when.
            onTick(System.currentTimeMillis() - watchStart)
            onProgress("Waiting for the band", null)
            ask = session.awaitFrame(HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY, 1_000)
        }
        if (cancelled) log.append("cancelled; ")
        val caughtAt = if (ask != null) System.currentTimeMillis() else 0L
        if (ask != null) {
            onProgress("The band asked", "The band asked for data")
            log.append("band asked; ")
            runCatching {
                session.send(
                    HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY,
                    HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4),
                )
            }
            // What it wants. Reported, never fetched — see the note above.
            runCatching {
                val what = session.request(
                    HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_WHAT, HuaweiProtocol.tlv(129),
                    timeoutMs = 6_000,
                )
                source = session.decrypt(what).firstOrNull { it.tag == 129 }?.let { outer ->
                    HuaweiProtocol.parseTlvs(outer.value).firstOrNull { it.tag == 6 }
                        ?.value?.toString(Charsets.UTF_8)
                }
                source?.let { onProgress("The band asked", "It wants: $it") }
            }
        } else if (!cancelled) {
            onProgress("The band never asked", "The band never asked — its data is still fresh")
            log.append("band did not ask; ")
        }

        if (!cancelled && (ask != null || announce)) {
            runCatching {
                session.send(
                    HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_READY,
                    HuaweiProtocol.tlv(1, byteArrayOf(3)),
                )
            }
            log.append("ready sent; ")
        }

        val served = LinkedHashSet<String>()
        var bytes = 0
        var unit = 862      // the band restates both in 0x1C/0x02; these are the captured defaults
        var current: String? = null

        // The band drives. We answer until it says done, or until it stops talking.
        //
        // **It asks more than once.** Pressing Update on the band raises TWO rounds, not one: the
        // broadcast round first — `type=0x0004/HW_AGNSS`, one 7 KB RTCM file — and then the
        // PREDICTED round, which is the one worth having. They arrive as separate `0x1F/0x01` asks
        // with a lull between them, and this loop only listens on `0x1C`. So it used to serve the
        // 7 KB, see twelve seconds of quiet, and return "done" after eight seconds — while the band
        // moved on to asking for the predicted set with nobody left listening. On the band that
        // looks like a transfer stuck at 0 % until it times out, which is exactly what 白い熊 saw
        // (2026-08-28). A round ending is not the session ending; only the caller's own deadline is.
        loop@ while (!cancelled && System.currentTimeMillis() < deadline) {
            val f = session.awaitService(HuaweiCommands.SVC_GNSS_FILES, 12_000) ?: run {
                if (cancelled || System.currentTimeMillis() >= deadline) return@run null
                val again = session.awaitFrame(
                    HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY, 3_000,
                ) ?: return@run null
                onProgress("The band asked again", "Another round — answering")
                log.append("band asked again; ")
                runCatching {
                    session.send(
                        HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_NOTIFY,
                        HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4),
                    )
                }
                runCatching {
                    val what = session.request(
                        HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_WHAT, HuaweiProtocol.tlv(129),
                        timeoutMs = 6_000,
                    )
                    session.decrypt(what).firstOrNull { it.tag == 129 }?.let { outer ->
                        HuaweiProtocol.parseTlvs(outer.value).firstOrNull { it.tag == 6 }
                            ?.value?.toString(Charsets.UTF_8)
                    }?.let {
                        source = it
                        onProgress("The band asked again", "It wants: $it")
                    }
                }
                runCatching {
                    session.send(
                        HuaweiCommands.SVC_GNSS_ASK, HuaweiCommands.GNSS_READY,
                        HuaweiProtocol.tlv(1, byteArrayOf(3)),
                    )
                }
                session.awaitService(HuaweiCommands.SVC_GNSS_FILES, 12_000)
            } ?: break@loop
            val tlvs = runCatching { session.decrypt(f) }.getOrElse { emptyList() }
            fun str(tag: Int) = tlvs.firstOrNull { it.tag == tag }?.value?.toString(Charsets.UTF_8)
            fun num(tag: Int) = tlvs.firstOrNull { it.tag == tag }?.let { HuaweiProtocol.bytesToInt(it.value) }

            when (f.commandId) {
                HuaweiCommands.GNSS_LIST -> {
                    onProgress("Transferring", "Offering: " + files.keys.joinToString(", "))
                    // NOTE: no result tag on this one — the capture's answer is tag 1 alone.
                    session.send(
                        HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_LIST,
                        HuaweiProtocol.tlv(1, files.keys.joinToString(";")),
                    )
                }
                HuaweiCommands.GNSS_PARAMS -> {
                    num(3)?.let { if (it in 1..4096) unit = it }
                    session.send(
                        HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_PARAMS,
                        HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4),
                    )
                }
                HuaweiCommands.GNSS_PICK -> {
                    current = str(1)
                    onProgress("Transferring", "It chose ${current ?: "?"}")
                    val data = files[current]
                    if (data == null) {
                        onProgress("Refused", "It asked for $current, which we do not have")
                        log.append("band picked unknown '$current'; ")
                        break@loop
                    }
                    // Size and CRC, and again no result tag.
                    session.send(
                        HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_PICK,
                        HuaweiProtocol.tlv(2, HuaweiProtocol.intBytes(data.size, 4)) +
                            HuaweiProtocol.tlv(3, HuaweiProtocol.intBytes(HuaweiProtocol.crc16(data), 2)),
                    )
                }
                HuaweiCommands.GNSS_BLOCK -> {
                    val name = str(1) ?: current
                    val data = files[name] ?: break@loop
                    val offset = num(2) ?: 0
                    val length = (num(3) ?: 0).coerceAtMost(data.size - offset)
                    session.send(
                        HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_BLOCK,
                        HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4) +
                            HuaweiProtocol.tlv(2, GNSS_TOKEN + (name ?: "")) +
                            HuaweiProtocol.tlv(3, HuaweiProtocol.intBytes(offset, 4)),
                    )
                    // 0x1C/0x05 is NOT TLV: one sequence byte, then raw file bytes. The sequence
                    // restarts at 0 for every block, which is why it never exceeds 7 in the capture.
                    var sent = 0
                    var seq = 0
                    while (sent < length) {
                        val n = minOf(unit, length - sent)
                        session.send(
                            HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_DATA,
                            byteArrayOf(seq.toByte()) + data.copyOfRange(offset + sent, offset + sent + n),
                        )
                        sent += n; seq++
                    }
                    bytes += sent
                    onProgress("Transferring", "  sent $sent B of ${current ?: name}  ($bytes B total)")
                }
                HuaweiCommands.GNSS_DONE -> {
                    session.send(
                        HuaweiCommands.SVC_GNSS_FILES, HuaweiCommands.GNSS_DONE,
                        HuaweiProtocol.tlv(HuaweiProtocol.TAG_RESULT, HuaweiProtocol.RESULT_SUCCESS, 4),
                    )
                    current?.let { served.add(it); onProgress("Transferring", "$it complete") }
                    current = null
                }
                else -> log.append("unhandled 0x1C/0x%02X; ".format(f.commandId))
            }
        }

        GnssOutcome(
            asked = ask != null, source = source, served = served.toList(), bytes = bytes,
            detail = log.toString().trim().trimEnd(';'),
            caughtAtMs = caughtAt, waitedMs = if (caughtAt == 0L) 0L else caughtAt - watchStart,
        )
    }

    /**
     * The 64 hex characters Health puts in every `0x1C/0x04` answer, ahead of the file name.
     *
     * Identical in all 121 responses across all seven files in the capture, appears nowhere else,
     * and is not the SHA-256 or MD5 of any file — checked. The band never echoes it back. What it
     * identifies cannot be told from one capture, so it is replayed verbatim rather than explained:
     * if the band validates it we need it, and if it ignores it nothing is lost.
     */
    private const val GNSS_TOKEN = "42E41FAF3CAABEF0E56DFD793DF99E6DF15EA2FC9B18A5D73ABF0DC1D0F06CCA"

    /** One setting the band was asked to change, and what it said. */
    data class SettingOutcome(val name: String, val ok: Boolean, val detail: String)

    /**
     * Apply recording switches to the band.
     *
     * Each is sent and answered independently: a band that refuses one setting should still take the
     * others, and a caller needs to know WHICH failed rather than that "settings failed". These
     * decide whether the band records anything at all — a fresh band has continuous heart rate and
     * automatic SpO₂ switched off, which looks exactly like a band that cannot measure them.
     */
    suspend fun applySettings(
        context: Context,
        address: String,
        toggles: List<Triple<String, Int, ByteArray>>,
    ): Result<List<SettingOutcome>> = withSession(context, address) { session, _ ->
        toggles.map { (name, command, payload) ->
            runCatching {
                session.requireOk(HuaweiCommands.SVC_FITNESS, command, payload)
                SettingOutcome(name, true, "set")
            }.getOrElse { e ->
                SettingOutcome(name, false, e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** One file pulled off the band, or the reason there was none. */
    data class FileOutcome(
        val name: String,
        val id: Int?,
        val bytes: Int,
        val path: String?,
        val note: String,
    )

    /**
     * Pull files off the band and write them down, without interpreting them.
     *
     * Deliberately a DUMP rather than a decoder. `sequence_data` is a container and we do not yet
     * know which of its stream ids holds sleep — Huawei Health was seen asking for three — and
     * `rrisqi_data.bin` is intermittent: it returned 312 B on 2026-08-22 and answered
     * `nothing (100004)` when asked for the same span on 2026-08-24. Guessing a layout from a file
     * that may not come back would produce a decoder that cannot be checked against anything.
     * Bytes on disk can be.
     *
     * Each id is tried in turn and the ones holding nothing are reported as such, which is the
     * actual question being asked here: which id is sleep?
     */
    suspend fun fetchFiles(
        context: Context,
        address: String,
        requests: List<Triple<String, Int, Int?>>,
        fromSeconds: Long,
        toSeconds: Long,
        outDir: String,
        stamp: String,
    ): Result<List<FileOutcome>> = withSession(context, address) { session, _ ->
        val files = HuaweiFileClient(session)
        requests.map { (name, type, id) ->
            val label = if (id == null) name else "$name-$id"
            runCatching {
                files.fetch(name, type, fromSeconds, toSeconds, id)
            }.fold(
                onSuccess = { r ->
                    when (r) {
                        is HuaweiFileClient.Result.Empty ->
                            FileOutcome(name, id, 0, null, "nothing (${r.resultCode})")
                        is HuaweiFileClient.Result.Data -> {
                            val f = java.io.File(outDir, "huawei-${label}_$stamp.bin")
                            runCatching { f.parentFile?.mkdirs(); f.writeBytes(r.bytes) }
                            FileOutcome(name, id, r.bytes.size, f.absolutePath, "ok")
                        }
                        // Written, and named so it can never be mistaken for a whole file. What
                        // arrived is real data and throwing it away was the worse error: for a
                        // container of dated records the part that came back is readable on its
                        // own, and it is the only evidence that says whether the declared size is
                        // honest in the first place.
                        is HuaweiFileClient.Result.Partial -> {
                            val f = java.io.File(outDir, "huawei-${label}_$stamp.partial.bin")
                            runCatching { f.parentFile?.mkdirs(); f.writeBytes(r.bytes) }
                            FileOutcome(name, id, r.bytes.size, f.absolutePath, "partial — ${r.summary}")
                        }
                    }
                },
                // One id failing must not cost the others: the whole point is to learn which ones
                // answer, and an exception is itself an answer about that id.
                onFailure = { FileOutcome(name, id, 0, null, it.message ?: it::class.java.simpleName) },
            )
        }
    }

    suspend fun status(db: AppDatabase): HuaweiStatus {
        val rows = db.huaweiSyncDao().recent(200).map {
            HuaweiStatus.Companion.Row(
                startedAt = it.startedAt,
                finishedAt = it.finishedAt,
                ok = it.ok,
                firmware = it.firmware,
                battery = it.battery,
                requestedTo = it.requestedTo,
                oldestReturnedSeconds = it.oldestReturnedSeconds,
                recordCount = it.recordCount,
                recordsFetched = it.recordsFetched,
            )
        }
        val dao = db.huaweiSampleDao()
        return HuaweiStatus.from(rows, dao.oldestAny(), dao.newestAny())
    }

    /**
     * Provision a band that has just been bonded — the out-of-box path, and ONLY that path.
     *
     * The full configuration set is what a factory-reset band will not leave its wizard without, and
     * it must follow the pairing with no human-speed gap: the band waits only seconds before
     * abandoning its own flow. Re-sending it on an ordinary sync would be wrong and slow.
     */
    /**
     * Pair with the band and provision it in one uninterrupted run.
     *
     * These are deliberately not two steps a human sits between. The band gives a new companion only
     * seconds before abandoning its own flow, so the bond completing has to lead straight into the
     * HiChain bind and the configuration set. Splitting them across two taps is what fails.
     *
     * @param timeoutSec covers the human confirmations too — one on the band, one on the phone —
     *   so it is generous by design, not by accident.
     */
    suspend fun pair(
        context: Context,
        db: AppDatabase,
        address: String,
        deviceName: String?,
        timeoutSec: Int = 180,
        serveSec: Int = 45,
        onPhase: (String) -> Unit = {},
    ): Outcome =
        run(
            context, db,
            Request(address, emptyList(), timeoutSec = timeoutSec, source = "pair"),
            configure = true, bond = true, serveSec = serveSec, onPhase = onPhase,
        ).also {
            if (it is Outcome.Ok && !deviceName.isNullOrBlank()) {
                HuaweiSettings.setDeviceName(context, deviceName)
            }
        }

    /** Provision a band that is already bonded — the configuration set without the pairing. */
    suspend fun provision(context: Context, db: AppDatabase, address: String, deviceName: String): Outcome =
        run(context, db, Request(address, emptyList(), timeoutSec = 120, source = "pair"), configure = true)
            .also { if (it is Outcome.Ok) HuaweiSettings.setDeviceName(context, deviceName) }

    suspend fun sync(context: Context, db: AppDatabase, request: Request): Outcome =
        run(context, db, request, configure = false)

    private suspend fun run(
        context: Context,
        db: AppDatabase,
        request: Request,
        configure: Boolean,
        bond: Boolean = false,
        serveSec: Int = 0,
        onPhase: (String) -> Unit = {},
    ): Outcome {
        fun phase(name: String, message: String = "") {
            HuaweiSyncState.phase(name, message)
            // Also into the caller's hands: HuaweiSyncState feeds the UI, but a Task needs the same
            // information in a variable or a failure is unlocatable after the fact.
            onPhase(name)
        }
        if (!running.tryLock()) {
            // Deliberately NOT touching HuaweiSyncState: the progress on screen belongs to the sync
            // that holds the lock, and resetting it here would blank a run that is still going.
            return Outcome.Skipped("a Huawei sync is already running")
        }
        val startedAt = System.currentTimeMillis()
        val window = request.windows.firstOrNull()
        // The row is written BEFORE anything can fail, so a crash still leaves a trace.
        val syncId = db.huaweiSyncDao().start(
            HuaweiSyncEntity(
                startedAt = startedAt, finishedAt = 0L, ok = false, address = request.address,
                firmware = null, battery = null,
                requestedFrom = request.windows.minOfOrNull { it.first } ?: 0L,
                requestedTo = window?.last ?: 0L,
                recordCount = 0, recordsFetched = 0, oldestReturnedSeconds = null,
                samplesWritten = 0, source = request.source, message = "",
            ),
        )

        var firmware: String? = null
        var battery: Int? = null
        var recordCount = 0
        var recordsFetched = 0
        var written = 0
        var oldestReturned: Long? = null
        var missing = 0
        var probe = ""
        var sleepNote = ""

        suspend fun close(ok: Boolean, message: String): Outcome {
            db.huaweiSyncDao().finish(
                id = syncId, finishedAt = System.currentTimeMillis(), ok = ok,
                firmware = firmware, battery = battery, recordCount = recordCount,
                recordsFetched = recordsFetched, oldestReturnedSeconds = oldestReturned,
                samplesWritten = written, message = message,
            )
            HuaweiSyncState.finish(message)
            return if (ok) {
                Outcome.Ok(
                    syncId, message,
                    if (missing > 0) "the band refused $missing record(s)" else null,
                )
            } else {
                Outcome.Failed(message)
            }
        }

        val client = HuaweiRfcommClient(context)
        try {
            HuaweiSyncState.begin(request.windows.size)
            // A pairing run waits on two human confirmations, so it gets its own, longer ceiling
            // than the sync timeout the settings task configures.
            val ceilingSec =
                if (bond || configure) request.timeoutSec + 120 + serveSec else request.timeoutSec
            val outcome = withTimeoutOrNull(ceilingSec.coerceIn(10, 1800) * 1000L) {
                if (bond) {
                    // An unbonded band cannot honour a token we stored earlier: the pairing it was
                    // issued under is gone. Clearing it here is what makes a re-pair a genuinely
                    // clean first run rather than a re-auth against a credential the band forgot.
                    if (!client.isBonded(request.address) && HuaweiSettings.isBound(context)) {
                        HuaweiSettings.clearBind(context)
                    }
                    phase("pairing", "accept on the band, then on the phone")
                    client.ensureBonded(request.address, request.timeoutSec * 1000L) { state ->
                        HuaweiSyncState.phase("pairing", state)
                    }?.let { return@withTimeoutOrNull close(false, it) }
                    // A fresh bond and an immediate RFCOMM connect race each other on this stack;
                    // the band needs a moment to start serving the channel it just agreed to.
                    kotlinx.coroutines.delay(1_500)
                }

                phase("connecting")
                client.open(request.address)?.let { return@withTimeoutOrNull close(false, it) }

                val session = HuaweiSession(client)
                val api = HuaweiClient(session)

                phase("handshake")
                val link = api.linkParams()
                api.deviceStatus()
                val authId = HuaweiSettings.authIdSelf(context) ?: mintAuthId()
                api.securityNegotiation(link.deviceSupportType, authId, android.os.Build.MODEL)

                val requestId = Random.nextLong(1L, Long.MAX_VALUE)
                val storedToken = HuaweiSettings.authToken(context)
                if (HuaweiSettings.isBound(context) && storedToken != null) {
                    // A stored token the band no longer honours means it was factory-reset or
                    // handed to another companion.
                    //
                    // The BOND can survive that while the BIND does not — which is exactly what a
                    // trip to another phone leaves behind: RFCOMM still opens, and only the HiChain
                    // identity is dead. So "not bonded" is the wrong test for a stale credential and
                    // the earlier check on it was insufficient.
                    //
                    // On a SYNC this is reported and nothing is touched: dropping a credential is a
                    // state change nobody asked for. On a PAIR run it is re-bound, because being
                    // asked to pair IS the authorisation to replace it — and refusing would leave
                    // the pair task unable to fix the one thing it exists to fix.
                    val reAuth = runCatching { api.authenticate(authId, storedToken, requestId) }
                    if (reAuth.isFailure) {
                        if (!bond) {
                            return@withTimeoutOrNull close(
                                false,
                                "the band no longer recognises this pairing — it was reset or " +
                                    "connected to something else. Run バンド接続（Huawei） to re-bind.",
                            )
                        }
                        phase("handshake", "re-binding — the band forgot this phone")
                        HuaweiSettings.clearBind(context)
                        val fresh = api.bind(authId, api.fetchPin(link.authVersion), requestId)
                        HuaweiSettings.saveBind(
                            context, authId, fresh, link.authVersion, link.deviceSupportType,
                            System.currentTimeMillis(),
                        )
                    }
                } else {
                    val token = api.bind(authId, api.fetchPin(link.authVersion), requestId)
                    HuaweiSettings.saveBind(
                        context, authId, token, link.authVersion, link.deviceSupportType,
                        System.currentTimeMillis(),
                    )
                }

                phase("device")
                firmware = runCatching { api.identity().firmware }.getOrNull()
                battery = api.battery()

                if (configure) {
                    phase("configuring")
                    // Offset-encoded exactly as the reference run sends it: whole hours, with a
                    // negative offset carried as 128 + |hours|, and the minutes byte always zero.
                    // Passing the raw quotient and remainder looks equivalent and is not — west of
                    // UTC it puts a negative number in an unsigned byte.
                    val offsetMin = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
                    val hours = kotlin.math.abs(offsetMin) / 60
                    val zoneByte = if (offsetMin < 0) 128 + hours else hours
                    api.configure(
                        HuaweiSettings.deviceName(context) ?: "HUAWEI Band",
                        System.currentTimeMillis() / 1000, zoneByte, 0,
                        // NO locale. Pairing used to re-assert the stored one, on the reasoning that
                        // any companion which touches the band can push its own language over it.
                        // That is true, and it is still not ours to undo: it made the band's language
                        // a thing that changed as a side effect of connecting. 白い熊 flips it by
                        // hand — 「バンド言語（Huawei） ⇨ 日本語 / ⇨ 英語」 — and nothing else sets it.
                        // The cost is stated rather than hidden: after the band has been to another
                        // phone, it stays in that phone's language until one of those tasks is run.
                        null,
                    )

                    // The band is NOT finished when the configuration set is. It carries on asking
                    // — PhoneInfo, permission checks, account commands — for well over a minute, and
                    // a companion that hangs up during that conversation is treated as no companion
                    // at all: the band drops back to its out-of-box wizard even though every command
                    // it received returned success.
                    //
                    // This cost a full pairing run to find. The Python reference logs it plainly:
                    // configuration finished at +10s, the band asked PhoneInfo at +14s, and the run
                    // that produced a working watch face stayed and answered for ninety seconds.
                    // Returning as soon as configure() succeeds looks correct and is not.
                    // Stay while the band is still talking, and leave once it goes quiet.
                    //
                    // Every number here comes from the reference run rather than from caution.
                    // Configuration finished at +10 s; the band's first follow-up — PhoneInfo, the
                    // one a hung-up companion loses — arrived at +14 s; the watch face appeared at
                    // about +20 s. Its later chatter runs on for minutes with gaps of up to 9 s,
                    // but that is ordinary companion traffic, not setup, so waiting it out would
                    // hold the radio for no reason: the band is single-connection, and a standing
                    // Bluetooth session on this phone once cost 1322 mAh in a day.
                    phase("serving", "the band is still talking — staying on the line")
                    var served = 0
                    val startedServing = System.currentTimeMillis()
                    val floor = startedServing + SERVE_MIN_MS
                    val cap = startedServing + serveSec.coerceAtLeast(1) * 1000L
                    var lastHeard = startedServing
                    while (System.currentTimeMillis() < cap) {
                        val round = runCatching { api.pump(2_000) }
                            .getOrDefault(HuaweiClient.ServeResult(0, 0))
                        served += round.answered
                        val now = System.currentTimeMillis()
                        if (round.received > 0) lastHeard = now
                        if (now >= floor && now - lastHeard >= SERVE_QUIET_MS) break
                    }
                    return@withTimeoutOrNull close(
                        true, "paired and provisioned — answered $served requests",
                    )
                }

                val samples = ArrayList<HuaweiSyncEngine.Sample>()
                request.windows.forEachIndexed { index, w ->
                    HuaweiSyncState.window(index + 1)
                    val fetch = HuaweiSyncEngine.fetchHistory(
                        session, w.first, w.last, request.maxRecords,
                    ) { done, total -> HuaweiSyncState.record(done, total) }
                    recordCount += fetch.recordCount
                    recordsFetched += fetch.recordsFetched
                    missing += fetch.missing.size
                    // Only when something was actually refused: silence is the expected case, and
                    // a diagnostic that prints on every success stops being read.
                    if (probe.isEmpty() && fetch.missing.isNotEmpty()) {
                        probe = "of ${fetch.recordCount}: refused [${fetch.missing.joinToString(",")}]" +
                            " · returned [${fetch.returnedIndices.joinToString(",")}]"
                    }
                    samples += fetch.samples
                    HuaweiSyncState.counted(samples.size, written)
                }

                phase("writing")
                val deduped = HuaweiSyncEngine.dedupe(samples)
                oldestReturned = deduped.minOfOrNull { it.epochSeconds }
                deduped.chunked(500).forEach { chunk ->
                    db.huaweiSampleDao().upsert(
                        chunk.map { HuaweiSampleEntity(it.metric, it.epochSeconds, it.value, syncId) },
                    )
                    written += chunk.size
                    HuaweiSyncState.counted(deduped.size, written)
                }
                // Sleep, while the link is still open. Its own phase because it is a different
                // mechanism entirely — a file fetched by name, not indexed records — and because a
                // reader watching progress should be able to tell which part is slow.
                //
                // Deliberately tolerant: a night that will not parse must not cost the samples that
                // were already fetched and written. It reports, and the sync still succeeds.
                phase("sleep")
                val beats = runCatching {
                    val to = request.windows.first().last
                    storeRri(db, session, syncId, to - SLEEP_LOOKBACK_SEC, to)
                }.getOrElse { 0 }
                val nights = runCatching {
                    // Its OWN window, not the sample window.
                    //
                    // A routine sync asks for the little that has happened since the last one — an
                    // hour, often less — and last night falls entirely outside that, so following
                    // the sample window means a sync that "succeeds" and never once brings a night.
                    // Sleep is one record per night, so asking for several days costs nothing.
                    val to = request.windows.first().last
                    storeSleep(db, session, syncId, to - SLEEP_LOOKBACK_SEC, to)
                }.getOrElse { sleepNote = it.message ?: it::class.java.simpleName; 0 }

                close(
                    true,
                    "$written samples from $recordsFetched/$recordCount records" +
                        // Always says something about sleep, including when there was none.
                        // Silence here is what hid the first attempt storing nothing at all: the
                        // sync reported success, the count was zero, and nothing said so.
                        (
                            if (sleepNote.isNotEmpty()) " · sleep: $sleepNote"
                            else if (nights > 0) " · $nights sleep segments"
                            else " · no night in the last ${SLEEP_LOOKBACK_SEC / 86_400} days"
                            ) +
                        (if (beats > 0) " · $beats RR windows" else "") +
                        if (probe.isEmpty()) "" else " | $probe",
                )
            }
            return outcome ?: close(false, "timed out after ${request.timeoutSec}s")
        } catch (e: Throwable) {
            return close(false, e.message ?: e::class.java.simpleName)
        } finally {
            // Always, on every path — including a cancelled one, which is what NonCancellable buys.
            // Holding the link costs battery and locks out everything else; see the note on
            // HuaweiRfcommClient.close for why a bare `runCatching { close() }` here silently did
            // nothing exactly when the caller had given up.
            withContext(NonCancellable) { runCatching { client.close() } }
            running.unlock()
        }
    }
}
