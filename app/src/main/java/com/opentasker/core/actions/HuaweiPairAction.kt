package com.opentasker.core.actions

import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner

/**
 * `Pair Huawei Band` — claim the band for this phone, with no Huawei account and no Huawei software.
 *
 * One action rather than a sequence, because the band does not allow a sequence: it gives a new
 * companion only seconds after the bond before abandoning its own flow. So the Bluetooth pairing,
 * the HiChain bind and the full configuration set all happen inside this one run.
 *
 * **Two taps are needed and neither can be automated away.** The band shows a plain yes/no — never a
 * six-digit code — and Android raises its own confirmation on the phone. Answering the phone's
 * dialog programmatically needs BLUETOOTH_PRIVILEGED, which only a system app holds.
 *
 * Running it again on an already-paired band is safe: it skips the bonding and re-sends the
 * configuration, which is how a band that has drifted out of its settings is put back.
 */
class HuaweiPairAction : Action {
    override val id = "huawei.pair"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)
        val deviceName = args["device_name"]?.trim()?.ifEmpty { null }
        val timeoutSec = args["timeout_sec"]?.trim()?.toIntOrNull() ?: 180
        val serveSec = args["serve_sec"]?.trim()?.toIntOrNull() ?: 45

        ctx.variables.set("${prefix}Phase", "starting")
        ctx.variables.set("${prefix}Address", address)

        val outcome = HuaweiSyncRunner.pair(
            context = ctx.app,
            db = OpenTaskerApp_NoHilt.db,
            address = address,
            deviceName = deviceName,
            timeoutSec = timeoutSec,
            serveSec = serveSec,
            // Published as it happens. A run that dies leaves the phase it died in behind, which is
            // the difference between "it failed" and knowing where — the first attempt left only
            // the phase this action had written before it started, and said nothing at all.
            onPhase = { ctx.variables.set("${prefix}Phase", it) },
        )

        val bound = HuaweiSettings.isBound(ctx.app)
        ctx.variables.set("${prefix}Bound", bound.toString())

        return when (outcome) {
            is HuaweiSyncRunner.Outcome.Ok -> {
                val text = "paired and provisioned"
                store?.let { ctx.variables.set(it, text) }
                ctx.variables.set("${prefix}Ok", "true")
                ctx.variables.set("${prefix}Summary", text)
                ctx.logger("Huawei pair: $text")
                ActionResult.Success
            }
            is HuaweiSyncRunner.Outcome.Skipped -> {
                store?.let { ctx.variables.set(it, outcome.reason) }
                ctx.variables.set("${prefix}Ok", "false")
                ctx.variables.set("${prefix}Summary", outcome.reason)
                ActionResult.Skip
            }
            is HuaweiSyncRunner.Outcome.Failed -> {
                store?.let { ctx.variables.set(it, outcome.reason) }
                ctx.variables.set("${prefix}Ok", "false")
                ctx.variables.set("${prefix}Summary", outcome.reason)
                ActionResult.Failure(outcome.reason)
            }
        }
    }
}
