package com.opentasker.core.contexts

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Bridges Bluetooth ACL connect/disconnect broadcasts into `event=bluetooth` context events.
 *
 * Emits metadata:
 *   - "event": "bluetooth"
 *   - "state": "connected" | "disconnected"
 *   - "device": human-readable device name (or "Unknown" when name is unavailable)
 *   - "address": device MAC address (used for precise filtering; never written to run logs)
 *
 * Profiles match via the shared event matcher: set `event=bluetooth`, optionally `state=connected`
 * (or `disconnected`), and use the `filter` field to match a device name or address.
 *
 * Reading the device name requires BLUETOOTH_CONNECT on Android 12+; failures fall back to a
 * generic name so a connect/disconnect can still trigger by state or address.
 */
object BluetoothContextEvents {
    private val events_ = MutableSharedFlow<ContextEvent>(
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    const val STATE_CONNECTED = "connected"
    const val STATE_DISCONNECTED = "disconnected"
    const val UNKNOWN_DEVICE = "Unknown"

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> STATE_CONNECTED
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> STATE_DISCONNECTED
                else -> return
            }
            val device = intent.bluetoothDevice()
            val name = device.safeName()
            val address = device?.address.orEmpty()
            // Log device name + state only; the address is intentionally omitted from logs.
            Log.d(TAG, "Bluetooth event: state=$state, device=$name")
            events_.tryEmit(buildEvent(state, name, address))
        }
    }

    /** Pure event builder so matching/metadata can be unit-tested without Android broadcasts. */
    fun buildEvent(
        state: String,
        deviceName: String,
        deviceAddress: String = "",
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = buildMap {
            put("event", "bluetooth")
            put("state", state)
            put("device", deviceName.ifBlank { UNKNOWN_DEVICE })
            if (deviceAddress.isNotBlank()) {
                put("address", deviceAddress)
            }
        },
    )

    fun intentFilter(): IntentFilter = IntentFilter().apply {
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun BluetoothDevice?.safeName(): String =
        try {
            this?.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_DEVICE
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT not granted on Android 12+; name is unavailable.
            UNKNOWN_DEVICE
        }

    private const val TAG = "BluetoothContextEvents"
}
