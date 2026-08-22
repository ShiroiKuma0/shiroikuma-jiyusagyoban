package com.opentasker.core.huawei

import android.content.Context
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.HuaweiSampleEntity
import com.opentasker.core.storage.HuaweiSyncEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
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
                    // A stored token the band no longer honours means it was factory-reset or handed
                    // to another companion. The bind is NOT cleared here: dropping a credential is a
                    // state change 白い熊 has not asked for, and re-binding silently would mint a new
                    // token behind their back. Say what happened instead, and let the pair card offer
                    // the re-bind — otherwise this reads as a generic handshake failure forever.
                    runCatching { api.authenticate(authId, storedToken, requestId) }
                        .onFailure {
                            return@withTimeoutOrNull close(
                                false,
                                "the band no longer recognises this pairing — it was reset or " +
                                    "connected to something else. Pair it again to re-bind.",
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
                close(
                    true,
                    "$written samples from $recordsFetched/$recordCount records" +
                        if (probe.isEmpty()) "" else " | $probe",
                )
            }
            return outcome ?: close(false, "timed out after ${request.timeoutSec}s")
        } catch (e: Throwable) {
            return close(false, e.message ?: e::class.java.simpleName)
        } finally {
            // Always, on every path. Holding the link costs battery and locks out everything else.
            runCatching { client.close() }
            running.unlock()
        }
    }
}
