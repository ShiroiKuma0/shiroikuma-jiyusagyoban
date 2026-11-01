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

/**
 * `SIM Status` — how one SIM slot is doing right now: whether a SIM is in it, the operator, whether
 * the network has it (service state), the signal and roaming. The SMS bridge's `/status` reply and
 * its “no service for 5 minutes” alarm are built from this, so a service phone at home can say which
 * of its SIMs has fallen off the network.
 *
 * Addressed by SLOT (0 = SIM1, 1 = SIM2) like the rest of this file. Writes, under `<prefix>` (default
 * `sim`): `_present` (true/false), `_state` (the SIM card state), `_operator`, `_service`
 * (in_service / out_of_service / emergency_only / power_off / unknown), `_dbm`, `_level` (0–4),
 * `_roaming` (true/false), `_sub` (the live subscription id) and `_number` (the SIM's own number, empty
 * when the card does not carry it), `_name` (the SIM's display name, e.g. "T-Mobile CZ") and `_enabled`
 * (true only when the card is READY — false for a card switched off in Android's SIM settings).
 */
class SimStatusAction : Action {
    override val id = "sim.status"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val slotArg = args["slot"]?.trim().orEmpty()
        val slot = slotArg.toIntOrNull()
            ?: return ActionResult.Failure("slot must be 0 (SIM1) or 1 (SIM2), got '$slotArg'")
        val prefix = args["prefix"]?.trim()?.ifBlank { null } ?: "sim"
        val base = ctx.app.getSystemService(android.telephony.TelephonyManager::class.java)
            ?: return ActionResult.Failure("this phone has no telephony service")

        val simState = runCatching { base.getSimState(slot) }.getOrDefault(android.telephony.TelephonyManager.SIM_STATE_UNKNOWN)
        val sim = DataSimSwitch.slots(ctx.app).firstOrNull { it.slotIndex == slot }
        ctx.variables.set("${prefix}_present", (sim != null).toString())
        ctx.variables.set("${prefix}_state", simStateName(simState))
        ctx.variables.set("${prefix}_sub", sim?.subId?.toString().orEmpty())
        ctx.variables.set("${prefix}_number", sim?.let { phoneNumber(ctx.app, it.subId) }.orEmpty())
        val info = sim?.let { s -> subscriptionInfo(ctx.app, s.subId) }
        ctx.variables.set("${prefix}_name", info?.displayName?.toString().orEmpty())
        // A card can sit in the slot switched off in Android's SIM settings: it is "present" with no
        // service, and must not pass for a working SIM (the Fold6's T-Mobile card, 2026-11-01). The
        // switch itself is not public API; such a card's state reads UNKNOWN, a working one READY.
        ctx.variables.set("${prefix}_enabled", (sim != null && simState == android.telephony.TelephonyManager.SIM_STATE_READY).toString())
        if (sim == null) {
            listOf("operator", "service", "dbm", "level", "roaming").forEach { ctx.variables.set("${prefix}_$it", "") }
            ctx.logger("SIM slot $slot: no active SIM (${simStateName(simState)})")
            return ActionResult.Success
        }

        val tm = base.createForSubscriptionId(sim.subId)
        return try {
            @Suppress("MissingPermission")
            val service = runCatching { tm.serviceState }.getOrNull()
            val signal = runCatching { tm.signalStrength }.getOrNull()
            val dbm = signal?.cellSignalStrengths?.firstOrNull()?.dbm
                ?.takeIf { it != Int.MAX_VALUE && it != android.telephony.CellInfo.UNAVAILABLE }
            ctx.variables.set("${prefix}_operator", tm.networkOperatorName.orEmpty().ifEmpty { sim.carrier })
            ctx.variables.set("${prefix}_service", serviceName(service?.state))
            ctx.variables.set("${prefix}_dbm", dbm?.toString().orEmpty())
            ctx.variables.set("${prefix}_level", signal?.level?.toString().orEmpty())
            ctx.variables.set("${prefix}_roaming", tm.isNetworkRoaming.toString())
            ctx.logger("SIM slot $slot: ${serviceName(service?.state)}, ${dbm ?: "?"} dBm, ${tm.networkOperatorName}")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("Phone permission needed to read SIM status: ${ex.message}", ex)
        }
    }

    /**
     * The SIM's own number, so a settings form can say which card sits in which slot. Empty when the
     * card does not carry its number (many don't) or READ_PHONE_NUMBERS is not granted — never a failure.
     * Asks for the newer API by trying it rather than by SDK_INT, which EMUI under-reports.
     */
    private fun subscriptionInfo(context: android.content.Context, subId: Int): android.telephony.SubscriptionInfo? {
        val sm = context.getSystemService(android.telephony.SubscriptionManager::class.java) ?: return null
        @Suppress("MissingPermission")
        return runCatching { sm.getActiveSubscriptionInfo(subId) }.getOrNull()
    }

    private fun phoneNumber(context: android.content.Context, subId: Int): String {
        val sm = context.getSystemService(android.telephony.SubscriptionManager::class.java) ?: return ""
        @Suppress("MissingPermission", "NewApi")
        val modern = runCatching { sm.getPhoneNumber(subId) }.getOrNull()
        if (!modern.isNullOrBlank()) return modern
        @Suppress("MissingPermission", "DEPRECATION")
        return runCatching { sm.getActiveSubscriptionInfo(subId)?.number }.getOrNull().orEmpty()
    }

    private fun serviceName(state: Int?): String = when (state) {
        android.telephony.ServiceState.STATE_IN_SERVICE -> "in_service"
        android.telephony.ServiceState.STATE_OUT_OF_SERVICE -> "out_of_service"
        android.telephony.ServiceState.STATE_EMERGENCY_ONLY -> "emergency_only"
        android.telephony.ServiceState.STATE_POWER_OFF -> "power_off"
        else -> "unknown"
    }

    private fun simStateName(state: Int): String = when (state) {
        android.telephony.TelephonyManager.SIM_STATE_ABSENT -> "absent"
        android.telephony.TelephonyManager.SIM_STATE_READY -> "ready"
        android.telephony.TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
        android.telephony.TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
        android.telephony.TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
        android.telephony.TelephonyManager.SIM_STATE_NOT_READY -> "not_ready"
        android.telephony.TelephonyManager.SIM_STATE_PERM_DISABLED -> "disabled"
        android.telephony.TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "card_error"
        android.telephony.TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "restricted"
        else -> "unknown"
    }
}

/**
 * `Run USSD` — send a USSD code (`*#21#`, a balance code…) from one SIM slot and wait for the
 * network's answer, so the SMS bridge can report call forwarding and credit without anyone touching
 * the phone. The answer text lands in the result variable; a network refusal fails the action with
 * its code, so a Try block can tell “the network said no” from “no answer”.
 *
 * Args:
 *   - "slot":       0 (SIM1) or 1 (SIM2)
 *   - "code":       the USSD code, e.g. `*#21#`
 *   - "result_var": where the answer goes (default `ussd`)
 *   - "timeout":    seconds to wait (default 30, max 55)
 */
class UssdAction : Action {
    override val id = "ussd.run"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val slotArg = args["slot"]?.trim().orEmpty()
        val slot = slotArg.toIntOrNull()
            ?: return ActionResult.Failure("slot must be 0 (SIM1) or 1 (SIM2), got '$slotArg'")
        val code = args["code"]?.trim()?.ifBlank { null } ?: return ActionResult.Failure("missing code")
        val resultVar = args["result_var"]?.trim()?.ifBlank { null } ?: "ussd"
        val timeoutSec = args["timeout"]?.trim()?.toIntOrNull()?.coerceIn(1, 55) ?: 30
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx.app, android.Manifest.permission.CALL_PHONE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.Failure("USSD needs the Phone (CALL_PHONE) permission")
        }
        val sim = DataSimSwitch.slots(ctx.app).firstOrNull { it.slotIndex == slot }
            ?: return ActionResult.Failure("no active SIM in slot $slot")
        val tm = ctx.app.getSystemService(android.telephony.TelephonyManager::class.java)
            ?.createForSubscriptionId(sim.subId)
            ?: return ActionResult.Failure("this phone has no telephony service")

        // first = the network's answer, second = its failure code.
        val outcome: Pair<String?, Int?>? = kotlinx.coroutines.withTimeoutOrNull(timeoutSec * 1000L) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val callback = object : android.telephony.TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: android.telephony.TelephonyManager, request: String, response: CharSequence) {
                        if (cont.isActive) cont.resumeWith(Result.success(response.toString() to null))
                    }

                    override fun onReceiveUssdResponseFailed(t: android.telephony.TelephonyManager, request: String, failureCode: Int) {
                        if (cont.isActive) cont.resumeWith(Result.success(null to failureCode))
                    }
                }
                try {
                    @Suppress("MissingPermission")
                    tm.sendUssdRequest(code, callback, android.os.Handler(android.os.Looper.getMainLooper()))
                } catch (ex: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.failure(ex))
                }
            }
        }
        return when {
            outcome == null -> {
                ctx.variables.set(resultVar, "")
                ActionResult.Failure("USSD $code on slot $slot: no answer within ${timeoutSec}s")
            }
            outcome.second != null -> {
                ctx.variables.set(resultVar, "")
                ActionResult.Failure("USSD $code on slot $slot refused by the network (code ${outcome.second})")
            }
            else -> {
                ctx.variables.set(resultVar, outcome.first.orEmpty())
                ctx.logger("USSD $code on slot $slot → ${outcome.first.orEmpty().take(80)}")
                ActionResult.Success
            }
        }
    }
}
