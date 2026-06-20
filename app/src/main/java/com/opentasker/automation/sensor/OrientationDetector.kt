package com.opentasker.automation.sensor

import android.content.Context
import android.util.Log
import android.view.OrientationEventListener
import com.opentasker.core.contexts.OrientationContextEvents

/**
 * Watches device orientation and fires an "orientation" event when it settles into a new quadrant
 * (portrait / landscape / reverse-portrait / reverse-landscape). Emits only on a quadrant change.
 * Registered for the lifetime of the automation service, like the shake detector.
 */
class OrientationDetector(context: Context) {

    private var last: String? = null

    private val listener = object : OrientationEventListener(context.applicationContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val name = when (((orientation + 45) / 90) % 4) {
                0 -> "portrait"
                1 -> "landscape"
                2 -> "reverse-portrait"
                else -> "reverse-landscape"
            }
            if (name == last) return
            last = name
            Log.i(TAG, "Orientation -> $name")
            OrientationContextEvents.publish(name)
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) {
            listener.enable()
            Log.i(TAG, "Orientation detector started")
        } else {
            Log.w(TAG, "Orientation cannot be detected on this device")
        }
    }

    fun stop() {
        listener.disable()
        Log.i(TAG, "Orientation detector stopped")
    }

    companion object {
        private const val TAG = "OpenTasker"
    }
}
