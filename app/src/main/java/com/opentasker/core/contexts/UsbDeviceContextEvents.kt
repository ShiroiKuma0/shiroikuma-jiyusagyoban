package com.opentasker.core.contexts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.opentasker.core.logging.AppLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bridges USB host attach/detach broadcasts into filtered `event=usb` pulses. */
object UsbDeviceContextEvents {
    const val STATE_ATTACHED = "attached"
    const val STATE_DETACHED = "detached"
    const val UNKNOWN_DEVICE = "Unknown"

    private val events_ = MutableSharedFlow<ContextEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ContextEvent> = events_.asSharedFlow()

    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> STATE_ATTACHED
                UsbManager.ACTION_USB_DEVICE_DETACHED -> STATE_DETACHED
                else -> return
            }
            val device = intent.usbDevice()
            val name = device?.productName.orEmpty().ifBlank { device?.deviceName.orEmpty() }
            AppLogger.debug(TAG, "USB event: state=$state, device=${name.ifBlank { UNKNOWN_DEVICE }}")
            events_.tryEmitPulse("usb", 
                buildEvent(
                    state = state,
                    deviceName = name,
                    vendorId = device?.vendorId ?: 0,
                    productId = device?.productId ?: 0,
                    deviceClass = device?.deviceClass ?: 0,
                ),
            )
        }
    }

    fun buildEvent(
        state: String,
        deviceName: String,
        vendorId: Int,
        productId: Int,
        deviceClass: Int,
    ): ContextEvent = ContextEvent(
        type = "event",
        matched = true,
        metadata = mapOf(
            "event" to "usb",
            "state" to state,
            "device" to deviceName.ifBlank { UNKNOWN_DEVICE },
            "deviceName" to deviceName.ifBlank { UNKNOWN_DEVICE },
            "vendorId" to vendorId.toString(),
            "productId" to productId.toString(),
            "class" to deviceClass.toString(),
        ),
    )

    fun intentFilter(): IntentFilter = IntentFilter().apply {
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private const val TAG = "UsbDeviceContextEvents"
}
