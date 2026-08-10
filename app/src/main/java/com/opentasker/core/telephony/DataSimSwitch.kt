package com.opentasker.core.telephony

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.opentasker.app.BuildConfig
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** One SIM as the speed test cares about it: which slot it sits in and what it is called. */
data class SimSlot(
    val slotIndex: Int,
    val subId: Int,
    val carrier: String,
)

/**
 * Reads the SIM inventory and switches which SIM carries mobile data.
 *
 * **Addressing is by SLOT, never by subscription id.** A subId is minted per (SIM, insertion) and is
 * not stable: this device's own AllSubInfoList carries five ids for two physical SIMs, the stale ones
 * left over from earlier insertions. Slot 0 / slot 1 is what a person means by "SIM1 / SIM2", so the
 * workspace addresses slots and this resolves the live subId at call time.
 */
object DataSimSwitch {

    private const val TAG = "DataSimSwitch"
    private const val BIND_TIMEOUT_MS = 8_000L

    /** Active SIMs, ordered by slot. Requires READ_PHONE_STATE; returns empty when unreadable. */
    fun slots(context: Context): List<SimSlot> {
        val sm = ContextCompat.getSystemService(context, SubscriptionManager::class.java) ?: return emptyList()
        val active = try {
            @Suppress("MissingPermission")
            sm.activeSubscriptionInfoList
        } catch (t: SecurityException) {
            AppLogger.warn(TAG, "Cannot read subscriptions: ${t.message}")
            null
        } ?: return emptyList()
        return active
            .map { SimSlot(it.simSlotIndex, it.subscriptionId, it.carrierName?.toString().orEmpty()) }
            .sortedBy { it.slotIndex }
    }

    /** The subId currently carrying data, or -1. Readable without Shizuku. */
    fun currentDataSubId(): Int =
        SubscriptionManager.getDefaultDataSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID } ?: -1

    /** The slot currently carrying data, or -1. */
    fun currentDataSlot(context: Context): Int {
        val sub = currentDataSubId()
        return slots(context).firstOrNull { it.subId == sub }?.slotIndex ?: -1
    }

    /**
     * Point mobile data at [slotIndex]. Returns null on success, else a reason fit to show 白い熊.
     *
     * A no-op when that slot already carries data, so a task that restores the original SIM at the end
     * costs nothing when it never switched away.
     */
    suspend fun switchToSlot(context: Context, slotIndex: Int): String? {
        val target = slots(context).firstOrNull { it.slotIndex == slotIndex }
            ?: return "no active SIM in slot $slotIndex"
        if (target.subId == currentDataSubId()) return null

        if (!ShizukuShell.isRunning()) return "Shizuku is not running — start it, then run again."
        if (!ShizukuShell.hasPermission()) {
            ShizukuShell.requestPermission()
            return "Requested Shizuku access — grant it in the dialog, then run again."
        }

        val bridge = bind(context) ?: return "could not start the privileged telephony bridge"
        val failure = try {
            bridge.setDefaultDataSubId(target.subId)
        } catch (t: Throwable) {
            t.message ?: t.toString()
        }
        return if (failure.isNullOrEmpty()) {
            AppLogger.info(TAG, "Mobile data → slot $slotIndex (subId ${target.subId}, ${target.carrier})")
            null
        } else {
            "could not switch to ${target.carrier}: $failure"
        }
    }

    // --- Shizuku UserService plumbing (mirrors ShizukuKeyEventListener's) -----------------------

    @Volatile private var service: ITelephonyBridge? = null

    private fun userServiceArgs(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, TelephonyBridgeService::class.java.name))
            .daemon(false)
            .processNameSuffix("telbridge")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private suspend fun bind(context: Context): ITelephonyBridge? {
        service?.let { return it }
        val ready = CompletableDeferred<ITelephonyBridge?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val bound = binder?.takeIf { it.pingBinder() }?.let(ITelephonyBridge.Stub::asInterface)
                service = bound
                if (!ready.isCompleted) ready.complete(bound)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val started = runCatching { Shizuku.bindUserService(userServiceArgs(context.applicationContext), connection) }
            .onFailure { AppLogger.warn(TAG, "bindUserService failed: ${it.message}") }
            .isSuccess
        if (!started) return null
        // The bridge is cheap and stateless, so it is bound per switch rather than kept alive — a
        // standing privileged process for something used twice a run is not worth the surface.
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
    }
}
