package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.util.Log
import com.opentasker.app.OpenTaskerApp_NoHilt

/**
 * Base class for automation broadcast receivers.
 * Provides common logging and engine access.
 */
abstract class AutomationBroadcastReceiver : BroadcastReceiver() {
    protected val automationEngine
        get() = OpenTaskerApp_NoHilt.automationEngine

    protected fun log(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    companion object {
        const val TAG = "AutomationReceiver"
    }
}
