package com.opentasker.core.actions

import com.opentasker.core.band.BandConnectResult
import com.opentasker.core.band.BandGattClient
import com.opentasker.core.band.BandScanCandidate
import com.opentasker.core.band.BandScanOutcome
import com.opentasker.core.band.BandScanReport
import com.opentasker.core.band.BandScanner
import com.opentasker.core.band.BandSettings
import com.opentasker.core.band.BandVerdict
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.progress.ProgressPanel
import com.opentasker.core.progress.ProgressRowState
import kotlinx.coroutines.withTimeoutOrNull

/**
 * `Find Band` — listen for nearby BLE devices and work out which one is a Hume band.
 *
 * **Why this exists.** A sync is addressed by MAC and needs no scan, so for a year the app had no
 * way to *discover* a band at all: `健康の設定 -- [727][01]` simply held the address, typed in once.
 * That is fine until the band is replaced or factory-reset — the address changes, every sync starts
 * failing, and there is nothing on the phone that can tell you the new one.
 *
 * **What it will and will not claim.** The band's advertised name is not documented anywhere in this
 * repo, because nothing ever needed it. So an advertisement alone can only ever be evidence, and the
 * report says which evidence it had. The one conclusive test is to connect and see whether
 * `fff0`/`fff6`/`fff7` are there — that is precisely what [BandGattClient.open] already checks
 * before a sync, so `verify` reuses it rather than re-implementing the signature.
 *
 * Nothing here writes a setting. The address is handed back in a variable and 白い熊 decides whether
 * it goes into the `01` task — an automation that silently re-points the health archive at whatever
 * wristband walked past would be a genuinely bad idea.
 */
class BandScanAction : Action {
    override val id = "band.scan"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val seconds = args["seconds"]?.trim()?.toIntOrNull()
            ?.coerceIn(BandScanner.MIN_SCAN_SEC, BandScanner.MAX_SCAN_SEC)
            ?: BandScanner.DEFAULT_SCAN_SEC
        val verify = args["verify"]?.trim()?.lowercase() != "false"
        val maxVerify = args["max_verify"]?.trim()?.toIntOrNull()?.coerceIn(0, 5) ?: DEFAULT_MAX_VERIFY
        val showAll = args["show_all"]?.trim()?.lowercase() != "false"
        val lang = args["lang"]?.trim()?.ifEmpty { null } ?: BandSettings.language(ctx.app)
        val configured = args["known_address"]?.trim()?.ifEmpty { null } ?: BandSettings.address(ctx.app)

        val prefix = args["prefix"]?.trim().orEmpty().ifEmpty { "BANDSCAN_" }
        val storeReport = args["store"]?.trim().orEmpty().ifEmpty { "Band_ScanReport" }
        val storeAddress = args["store_address"]?.trim().orEmpty().ifEmpty { "Band_ScanAddress" }
        val storeCount = args["store_count"]?.trim().orEmpty().ifEmpty { "Band_ScanCount" }
        val storeVerdict = args["store_verdict"]?.trim().orEmpty().ifEmpty { "Band_ScanVerdict" }

        // Cleared before the radio is touched, so a task that reads them after a failure sees an
        // honest blank rather than the previous run's answer.
        ctx.variables.set(storeAddress, "")
        ctx.variables.set(storeCount, "0")
        ctx.variables.set(storeVerdict, "none")

        val ja = BandScanReport.isJapanese(lang)
        val cancelVar = args["cancel_var"]?.trim()?.removePrefix("%").orEmpty()
        var tick = 0

        // Everything the live window shows is published here, and the window is a Scene bound to
        // these names — the band.sync precedent, which the scene layer re-expands on every variable
        // change, so it redraws itself while this action is still blocking the task. A progress panel,
        // if one happens to be open, gets the same line; neither is required.
        fun publish(phase: String, pct: Int, note: String, list: String? = null) {
            ctx.variables.set("${prefix}Phase", phase)
            ctx.variables.set("${prefix}Pct", pct.toString())
            ctx.variables.set("${prefix}Note", note)
            ctx.variables.set("${prefix}Spin", BandScanReport.spinnerFrame(tick))
            if (list != null) ctx.variables.set("${prefix}List", list)
            reportToPanel(note)
        }

        // Cancellation has two sources: a progress panel's own 中止, and a plain variable that any
        // scene button can set. The scene is the one 健康 actually uses.
        fun cancelled(): Boolean =
            ProgressPanel.isCancelled() || (cancelVar.isNotEmpty() && ctx.variables.get(cancelVar)?.trim() == "1")

        if (cancelVar.isNotEmpty()) ctx.variables.set(cancelVar, "")
        publish("listening", 0, if (ja) "聴取中…" else "listening…", BandScanReport.liveList(emptyList(), ja))

        val outcome = BandScanner(ctx.app).scan(seconds) { elapsedMs, devices ->
            tick++
            val whole = elapsedMs / 1000
            val tenth = (elapsedMs % 1000) / 100
            val pct = ((elapsedMs * SCAN_SHARE_OF_TOTAL) / (seconds * 1000L)).toInt()
            // Ranked every tick rather than appended in arrival order: a device's evidence changes as
            // more of its advertisements arrive (the name usually turns up in the scan RESPONSE, a
            // packet later than the one that first announced it), so a line written once at first
            // sight would be wrong for most devices.
            val ranked = BandScanReport.rank(devices, configured, lang)
            publish(
                "listening",
                pct,
                if (ja) {
                    "聴取中 $whole.$tenth 秒 · ${devices.size} 台"
                } else {
                    "listening $whole.${tenth}s · ${devices.size} device(s)"
                },
                BandScanReport.liveList(ranked, ja),
            )
            !cancelled()
        }
        val heard = when (outcome) {
            is BandScanOutcome.Failed -> {
                // The live window is the only surface this has, so the reason goes into it too —
                // otherwise a refused scan leaves "listening…" on screen for ever.
                ctx.variables.set(storeReport, outcome.reason)
                ctx.variables.set("${prefix}Spin", "✗")
                ctx.variables.set("${prefix}Phase", "failed")
                ctx.variables.set("${prefix}Note", if (ja) "失敗" else "failed")
                ctx.variables.set("${prefix}List", outcome.reason)
                ctx.logger("Band scan failed: ${outcome.reason}")
                return ActionResult.Failure(outcome.reason)
            }
            is BandScanOutcome.Heard -> outcome.devices
        }

        var candidates = BandScanReport.rank(heard, configured, lang)
        var probed = 0
        if (verify && maxVerify > 0) {
            val order = BandScanReport.probeOrder(candidates, maxVerify)
            for (target in order) {
                // Stop the moment one answers: a second confirmed band would be someone else's, and
                // connecting to it serves nothing.
                if (candidates.any { it.verdict == BandVerdict.CONFIRMED }) break
                if (cancelled()) break
                val address = target.device.address
                ctx.logger("Band scan: probing $address")
                tick++
                publish(
                    "probing",
                    SCAN_SHARE_OF_TOTAL.toInt() + (probed * PROBE_SHARE_EACH),
                    if (ja) "確認中 $address" else "probing $address",
                    BandScanReport.liveList(candidates, ja),
                )
                val (confirmed, note) = probe(ctx, address)
                probed++
                candidates = candidates.map { if (it.device.address == address) BandScanReport.applyProbe(it, confirmed, note) else it }
            }
            candidates = candidates.sortedWith(
                compareByDescending<BandScanCandidate> { it.score }
                    .thenByDescending { it.device.rssi },
            )
        }

        val report = BandScanReport.describe(candidates, seconds, lang, showAll, probed)
        val best = BandScanReport.bestAddress(candidates)
        val verdict = candidates.firstOrNull { it.device.address == best }?.verdict?.name?.lowercase() ?: "none"

        ctx.variables.set(storeReport, report)
        ctx.variables.set(storeAddress, best)
        ctx.variables.set(storeCount, candidates.size.toString())
        ctx.variables.set(storeVerdict, verdict)

        // The window that showed the search becomes the window that shows the answer. It used to hand
        // over to a separate dialog, which meant the list you had been watching fill up vanished at
        // the exact moment it became worth reading (白い熊, 2026-08-11). <prefix>List is replaced by
        // the full report, <prefix>Note by the verdict, and the spinner is parked on a still frame.
        ctx.variables.set("${prefix}Spin", if (best.isNotEmpty()) "◆" else "·")
        ctx.variables.set("${prefix}Phase", "done")
        ctx.variables.set("${prefix}Pct", "100")
        ctx.variables.set("${prefix}List", report)
        ctx.variables.set(
            "${prefix}Note",
            when {
                best.isEmpty() -> if (ja) "見つかりません · ${candidates.size} 台" else "not found · ${candidates.size} device(s)"
                verdict == "confirmed" -> if (ja) "確認済み — $best" else "confirmed — $best"
                else -> if (ja) "候補 — $best" else "likely — $best"
            },
        )
        reportToPanel(ctx.variables.get("${prefix}Note").orEmpty())
        ctx.logger("Band scan: ${candidates.size} device(s), best=${best.ifEmpty { "none" }} ($verdict)")

        // Finding nothing is Success on purpose. The band being on its charger, or held by Hume's own
        // app, is an ordinary state of the world — a task that goes on to show the report has done
        // its job, and a red failure would only make the flow harder to write.
        return ActionResult.Success
    }

    /**
     * Connect just far enough to recognise a band, then let go immediately.
     *
     * [BandGattClient.open] returns `Ready` only once `fff0`, `fff6` and `fff7` are all present, so
     * this needs no signature check of its own — it is the same gate a sync passes through. The
     * whole thing is wrapped in a timeout because a stranger's device may accept the connection and
     * then never finish discovery, and this must not hold up a dialog for the full GATT budget.
     */
    private suspend fun probe(ctx: ActionContext, address: String): Pair<Boolean, String> {
        val client = BandGattClient(ctx.app)
        return try {
            when (val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) { client.open(address) }) {
                is BandConnectResult.Ready -> true to "answered on fff0/fff6/fff7 (MTU ${result.mtu})"
                is BandConnectResult.Failed -> false to result.reason
                null -> false to "no answer within ${PROBE_TIMEOUT_MS / 1000}s"
            }
        } finally {
            // Unconditional: a leaked BluetoothGatt is the classic cause of status 133 on the next
            // connect, and the very next thing this phone does is a sync.
            client.close()
        }
    }

    /**
     * Write a live line onto the progress panel's running row, if one is open.
     *
     * Deliberately best-effort and silent when no panel is showing: the action is perfectly usable
     * without one, and a scan run from a Profile at three in the morning must not care about UI.
     * Writing to the ACTIVE row's `detail` rather than adding rows keeps the shape of whatever panel
     * the calling task declared — this action is a guest in it, not its owner.
     */
    private fun reportToPanel(note: String) {
        if (ProgressPanel.state.value == null) return
        ProgressPanel.update { panel ->
            val index = panel.outer.indexOfFirst { it.state == ProgressRowState.ACTIVE }
            if (index < 0) {
                panel
            } else {
                panel.copy(outer = panel.outer.toMutableList().also { it[index] = it[index].copy(detail = note) })
            }
        }
    }

    companion object {
        /** Three is enough to cover "the band plus two neighbours" without turning into a sweep. */
        const val DEFAULT_MAX_VERIFY = 3

        /** Shorter than the GATT connect budget on purpose — see [probe]. */
        const val PROBE_TIMEOUT_MS = 9_000L

        /**
         * How the percentage is split between the two phases.
         *
         * The listening window is a known length and the probes are not, so the bar is honest about
         * the part it can measure and simply steps forward once per probe for the part it cannot.
         */
        const val SCAN_SHARE_OF_TOTAL = 70L
        const val PROBE_SHARE_EACH = 10
    }
}
