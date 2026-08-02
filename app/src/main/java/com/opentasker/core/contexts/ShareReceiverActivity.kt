package com.opentasker.core.contexts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.AutomationService

/** Short-lived Sharesheet entry point; it queues the sanitized event and returns to the sender. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && ShareContextEvents.publishFromIntent(intent)) {
            ContextCompat.startForegroundService(this, Intent(this, AutomationService::class.java))
        }
        finish()
    }
}
