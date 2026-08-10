package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.telephony.DataSimSwitch

/**
 * `Set data SIM` — point mobile data at a SIM slot, so a task can measure each SIM in turn.
 *
 * Addressed by SLOT (0 = SIM1, 1 = SIM2), never by subscription id: a subId is minted per insertion
 * and this device already carries five of them for two physical SIMs. The live subId is resolved here.
 *
 * Always publishes the slot that was carrying data BEFORE the switch, so the caller can restore it —
 * a task that dies mid-run must not leave 白い熊 on the wrong SIM.
 */
class SetDataSimAction : Action {
    override val id = "sim.data.set"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val slotArg = args["slot"]?.trim().orEmpty()
        val slot = slotArg.toIntOrNull()
            ?: return ActionResult.Failure("slot must be 0 (SIM1) or 1 (SIM2), got '$slotArg'")

        val previous = DataSimSwitch.currentDataSlot(ctx.app)
        ctx.variables.set(args["store_previous"]?.trim()?.takeIf { it.isNotEmpty() } ?: "SIM_Previous", previous.toString())

        val failure = DataSimSwitch.switchToSlot(ctx.app, slot)
        if (failure != null) return ActionResult.Failure(failure)

        // The modem needs a moment to attach before a throughput test means anything; without this the
        // first samples of the following test measure the handover, not the SIM.
        val settle = args["settle_ms"]?.trim()?.toLongOrNull()?.coerceIn(0L, 30_000L) ?: 3_000L
        if (settle > 0) kotlinx.coroutines.delay(settle)

        ctx.logger("Data SIM → slot $slot (was $previous)")
        return ActionResult.Success
    }
}

/**
 * `Read SIMs` — publish the SIM inventory into variables, so the workspace never hardcodes a subId
 * and the report can name the carrier the system actually reports.
 *
 * Writes `<prefix>Count`, and per slot `<prefix>0Name` / `<prefix>0Sub`, `<prefix>1Name` / `<prefix>1Sub`,
 * plus `<prefix>DataSlot` for the SIM currently carrying data.
 */
class ReadSimsAction : Action {
    override val id = "sim.list"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim().orEmpty().ifEmpty { "SIM_" }
        val slots = DataSimSwitch.slots(ctx.app)
        if (slots.isEmpty()) {
            return ActionResult.Failure("no active SIMs readable — is the Phone permission granted?")
        }
        ctx.variables.set("${prefix}Count", slots.size.toString())
        slots.forEach { sim ->
            ctx.variables.set("$prefix${sim.slotIndex}Name", sim.carrier)
            ctx.variables.set("$prefix${sim.slotIndex}Sub", sim.subId.toString())
        }
        ctx.variables.set("${prefix}DataSlot", DataSimSwitch.currentDataSlot(ctx.app).toString())
        ctx.logger("SIMs: ${slots.joinToString { "slot ${it.slotIndex}=${it.carrier}" }}")
        return ActionResult.Success
    }
}
