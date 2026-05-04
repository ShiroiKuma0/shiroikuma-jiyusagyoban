package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log

/**
 * Base class for automation broadcast receivers.
 * Provides common logging and error handling.
 */
abstract class AutomationBroadcastReceiver : BroadcastReceiver() {
    protected fun log(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    companion object {
        const val TAG = "AutomationReceiver"
    }
}
