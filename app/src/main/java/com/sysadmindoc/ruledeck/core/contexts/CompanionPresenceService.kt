@file:Suppress("DEPRECATION")

package com.sysadmindoc.ruledeck.core.contexts

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi

/** Receives OS-managed companion presence callbacks without a scanning loop. */
@RequiresApi(31)
class CompanionPresenceService : CompanionDeviceService() {
    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        CompanionContextEvents.publishPresent(associationInfo.id.toString())
    }

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        CompanionContextEvents.publishAbsent(associationInfo.id.toString())
    }

    @Suppress("DEPRECATION")
    override fun onDeviceAppeared(address: String) {
        CompanionContextEvents.publishPresent(address)
    }

    @Suppress("DEPRECATION")
    override fun onDeviceDisappeared(address: String) {
        CompanionContextEvents.publishAbsent(address)
    }

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        publishCompanionPresenceEvent(event.event, event.associationId)
    }
}

/**
 * Android 16's presence callback as present or absent; events that are neither are ignored. Split
 * from the service because DevicePresenceEvent can't be built in a JVM test.
 */
@RequiresApi(36)
internal fun publishCompanionPresenceEvent(eventCode: Int, associationId: Int) {
    val state = when (eventCode) {
        DevicePresenceEvent.EVENT_BLE_APPEARED,
        DevicePresenceEvent.EVENT_BT_CONNECTED,
        DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED,
        -> CompanionContextEvents.STATE_PRESENT

        DevicePresenceEvent.EVENT_BLE_DISAPPEARED,
        DevicePresenceEvent.EVENT_BT_DISCONNECTED,
        DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED,
        -> CompanionContextEvents.STATE_ABSENT

        else -> return
    }
    val id = associationId.takeIf { it >= 0 }?.toString().orEmpty()
    if (state == CompanionContextEvents.STATE_PRESENT) {
        CompanionContextEvents.publishPresent(id)
    } else {
        CompanionContextEvents.publishAbsent(id)
    }
}
