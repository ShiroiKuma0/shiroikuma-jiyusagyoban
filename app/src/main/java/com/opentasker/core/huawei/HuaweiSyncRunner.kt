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
        api.setLocale(locale, imperial).also { if (it) HuaweiSettings.setBandLocale(context, locale) }
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
        val night = HuaweiSleep.parse(file.bytes) ?: return 0
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
        return night.segments.size
    }

    /**
     * Install a watch face.
     *
     * The name is what the band files it under and Health always uses `<assetId>_<version>`, so the
     * two identifiers are derived from the filename rather than asked for separately — a face called
     * `7185695173_2.1.1.bin` carries everything the band needs.
     */
    suspend fun uploadWatchFace(
        context: Context,
        address: String,
        file: java.io.File,
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
        HuaweiUploadClient(session).installWatchFace(
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
        session.send(
            HuaweiCommands.SVC_WEATHER, HuaweiCommands.WEATHER_PUSH,
            HuaweiCommands.weather(place, temperatureC, now, humidity, highC, lowC),
        )
        // Give the band a moment to answer if it means to; its silence is normal here.
        session.poll(2_000)
        buildString {
            append("$place ${temperatureC}°C")
            humidity?.let { append(" · ${it}%") }
            if (highC != null && lowC != null) append(" · $lowC–$highC°C")
            if (latitude != null && longitude != null) append(" · position sent")
        }
    }

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
     * `rrisqi_data.bin` has never once returned data to us, because the band had only just been
     * told to record RR intervals. Guessing a layout from that would produce a decoder that cannot
     * be checked against anything. Bytes on disk can be.
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
