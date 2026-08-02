@file:Suppress("DEPRECATION")

package com.opentasker.core.contexts

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
        val state = when (event.event) {
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
        val associationId = event.associationId.takeIf { it >= 0 }?.toString().orEmpty()
        if (state == CompanionContextEvents.STATE_PRESENT) {
            CompanionContextEvents.publishPresent(associationId)
        } else {
            CompanionContextEvents.publishAbsent(associationId)
        }
    }
}
