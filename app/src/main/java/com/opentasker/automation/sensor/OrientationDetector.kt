package com.opentasker.automation.sensor

import android.content.Context
import android.graphics.Point
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import com.opentasker.core.contexts.OrientationContextEvents

/**
 * Watches device orientation and fires an "orientation" event when it settles into a new quadrant
 * (portrait / landscape / reverse-portrait / reverse-landscape). Emits only on a quadrant change.
 * Registered for the lifetime of the automation service, like the shake detector.
 *
 * The raw [OrientationEventListener] angle is relative to the device's NATURAL orientation, so on a
 * landscape-natural device (a tablet, or an unfolded foldable) angle 0 is landscape — naming it
 * "portrait" would be backwards. We detect the natural orientation from the real display size (so it
 * also tracks a foldable folding/unfolding) and shift the quadrant accordingly. Driven by the physical
 * sensor, so it fires even when auto-rotate is locked.
 */
class OrientationDetector(context: Context) {

    private val appContext = context.applicationContext
    private var last: String? = null

    private val listener = object : OrientationEventListener(appContext) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val quadrant = ((orientation + 45) / 90) % 4          // 0 = device's natural posture
            val name = if (naturalIsLandscape()) {
                when (quadrant) {                                  // natural = landscape (tablet / unfolded foldable)
                    0 -> "landscape"
                    1 -> "portrait"
                    2 -> "reverse-landscape"
                    else -> "reverse-portrait"
                }
            } else {
                when (quadrant) {                                  // natural = portrait (phone / folded)
                    0 -> "portrait"
                    1 -> "landscape"
                    2 -> "reverse-portrait"
                    else -> "reverse-landscape"
                }
            }
            if (name == last) return
            last = name
            Log.i(TAG, "Orientation -> $name")
            OrientationContextEvents.publish(name)
        }
    }

    /** True when the device's natural orientation (rotation 0) is landscape — tablets, unfolded foldables. */
    @Suppress("DEPRECATION")
    private fun naturalIsLandscape(): Boolean {
        val display = (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val size = Point().also { display.getRealSize(it) }
        val rotated = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        val naturalWidth = if (rotated) size.y else size.x   // un-rotate to the rotation-0 dimensions
        val naturalHeight = if (rotated) size.x else size.y
        return naturalWidth > naturalHeight
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
