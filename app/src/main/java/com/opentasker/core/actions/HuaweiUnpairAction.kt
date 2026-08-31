package com.opentasker.core.actions

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.huawei.HuaweiSettings

/**
 * `Unpair Huawei Band` — forget the band on THIS phone, so the next pairing is a clean first bind.
 *
 * ## Order matters, and getting it wrong is expensive
 *
 * **Release the band on the BAND first** (its Settings → Disconnect), and only then run this.
 *
 * Removing our side while the band still considers this phone its companion is what deadlocks it:
 * it believes it is paired, stops advertising, and refuses the connection that would fix it. The way
 * out of that state is a factory reset — and a factory reset wipes **the band's own stored history**,
 * which is the only copy of anything not yet synced. Data already in `huawei_samples` is safe either
 * way; it is the band's unsynced buffer that is at risk.
 *
 * The task that runs this asks for confirmation first for exactly that reason.
 *
 * ## What it clears
 *
 * The HiChain bind — our own credential, invented locally, worth nothing to anyone else and costing
 * only a re-pair to replace — and the Android Bluetooth bond. The band's ADDRESS is deliberately
 * kept: it is a public address that survives even a factory reset, so there is nothing to rediscover.
 */
class HuaweiUnpairAction : Action {
    override val id = "huawei.unpair"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val prefix = args["prefix"]?.trim()?.ifEmpty { null } ?: "HUAWEI_"
        val store = args["store"]?.trim()?.ifEmpty { null }
        val address = args["address"]?.trim()?.ifEmpty { null } ?: HuaweiSettings.address(ctx.app)

        val hadBind = HuaweiSettings.isBound(ctx.app)
        HuaweiSettings.clearBind(ctx.app)

        val bond = removeBond(ctx.app, address)
        val text = buildString {
            append(if (hadBind) "bind cleared" else "no bind was stored")
            append(" · ")
            append(bond)
        }
        ctx.variables.set("${prefix}Bound", "false")
        ctx.variables.set("${prefix}Summary", text)
        store?.let { ctx.variables.set(it, text) }
        ctx.logger("Huawei unpair: $text")
        return ActionResult.Success
    }

    /**
     * Drop the Bluetooth bond.
     *
     * `removeBond()` is a hidden API. Reflection reaches it on some builds and is blocked by the
     * non-SDK interface restrictions on others, and there is no supported alternative — so this
     * reports honestly which happened rather than claiming success either way. When it cannot, the
     * remaining step is one tap in the system Bluetooth settings, and saying so beats a silent
     * half-unpair that makes the next pairing fail for an invisible reason.
     */
    @SuppressLint("MissingPermission")
    private fun removeBond(context: Context, address: String): String {
        val adapter: BluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as? BluetoothManager)?.adapter ?: return "no Bluetooth adapter — forget it by hand"
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return "bad address $address"
        if (device.bondState == android.bluetooth.BluetoothDevice.BOND_NONE) {
            return "already not paired on this phone"
        }
        return runCatching {
            val ok = device.javaClass.getMethod("removeBond").invoke(device) as? Boolean ?: false
            if (ok) "Bluetooth pairing removed" else "the phone refused to remove the pairing — forget it by hand in Bluetooth settings"
        }.getOrElse {
            "cannot remove the pairing from here (hidden API blocked) — forget it by hand in Bluetooth settings"
        }
    }
}
