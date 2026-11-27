package com.sysadmindoc.ruledeck.core.telephony

import android.os.IBinder

/**
 * The privileged half of the data-SIM switch, running in a Shizuku-spawned process as `shell`.
 *
 * Why a UserService and not a shell command: there is no `cmd phone` subcommand for the default data
 * subscription on this device (checked — `cmd phone` exposes ims/uce/cc/gba/src and nothing else), and
 * `settings put global multi_sim_data_call` only mirrors the value the telephony stack already chose,
 * it does not drive the switch. The authoritative entry point is ISub.setDefaultDataSubId, reachable
 * here because shell holds MODIFY_PHONE_STATE.
 *
 * Everything is reflective on purpose: ISub is an internal interface, and the accessor has moved
 * between releases (SubscriptionController on this build, SubscriptionManagerService on newer AOSP).
 * A miss returns a readable reason rather than throwing, so the action can report it to 白い熊.
 */
class TelephonyBridgeService : ITelephonyBridge.Stub {

    @Suppress("unused")
    constructor() : super()

    override fun destroy() {
        System.exit(0)
    }

    override fun setDefaultDataSubId(subId: Int): String {
        if (subId < 0) return "invalid subscription id $subId"
        val iSub = iSub() ?: return "telephony service 'isub' is not available"
        val method = iSub.javaClass.methods.firstOrNull {
            it.name == "setDefaultDataSubId" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return "ISub.setDefaultDataSubId(int) is missing on this build"
        return try {
            method.invoke(iSub, subId)
            ""
        } catch (t: Throwable) {
            // The cause carries the real SecurityException / IllegalArgumentException from the
            // telephony process; the reflection wrapper's own message is useless on its own.
            val cause = t.cause ?: t
            "${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}"
        }
    }

    override fun getDefaultDataSubId(): Int {
        val iSub = iSub() ?: return -1
        val method = iSub.javaClass.methods.firstOrNull {
            it.name == "getDefaultDataSubId" && it.parameterTypes.isEmpty()
        } ?: return -1
        return try {
            (method.invoke(iSub) as? Int) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    /**
     * Huawei's own switch, `IHwTelephony.setSubscription(slot, on, null)` — the one the SIM management
     * page uses. The standard `ISub.setUiccApplicationsEnabled` is accepted on the Mate XT and only
     * flips a flag: the radio stays off (measured 2026-11-27). Called by NAME, never by transaction
     * number.
     */
    override fun setSlotEnabled(slot: Int, enabled: Boolean): String {
        if (slot !in 0..1) return "invalid SIM slot $slot"
        val hw = hwTelephony() ?: return "this phone has no Huawei telephony service (phone_huawei)"
        val method = hw.javaClass.methods.firstOrNull {
            it.name == "setSubscription" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return "IHwTelephony.setSubscription(int, boolean, Message) is missing on this build"
        return try {
            method.invoke(hw, slot, enabled, null)
            ""
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            "${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}"
        }
    }

    override fun getSlotEnabled(slot: Int): Int {
        val hw = hwTelephony() ?: return -1
        val method = hw.javaClass.methods.firstOrNull {
            it.name == "getSubState" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return -1
        return try {
            when (val state = method.invoke(hw, slot)) {
                is Int -> if (state == 1) 1 else 0
                is Boolean -> if (state) 1 else 0
                else -> -1
            }
        } catch (t: Throwable) {
            -1
        }
    }

    private fun hwTelephony(): Any? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "phone_huawei") as? IBinder
        if (binder == null) {
            null
        } else {
            Class.forName("com.android.internal.telephony.IHwTelephony\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }
    } catch (t: Throwable) {
        null
    }

    /** `ISub.Stub.asInterface(ServiceManager.getService("isub"))`, all by reflection. */
    private fun iSub(): Any? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "isub") as? IBinder
        if (binder == null) {
            null
        } else {
            Class.forName("com.android.internal.telephony.ISub\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }
    } catch (t: Throwable) {
        null
    }
}
