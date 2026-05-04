package com.opentasker.automation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import com.opentasker.automation.core.AutomationEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Base class for automation broadcast receivers.
 * Provides common logging and engine access.
 */
@AndroidEntryPoint
abstract class AutomationBroadcastReceiver : BroadcastReceiver() {
    @Inject
    lateinit var automationEngine: AutomationEngine

    protected fun log(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    companion object {
        const val TAG = "AutomationReceiver"
    }
}
