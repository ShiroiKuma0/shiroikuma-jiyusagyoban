package com.sysadmindoc.ruledeck.core.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.sysadmindoc.ruledeck.core.logging.AppLogger

/**
 * Bound by Android only while RuleDeck is the digital assistant app. It does nothing on its own:
 * a task asks it for a session, and the session asks Settings to change airplane mode.
 */
class RuleDeckVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        current = this
        AppLogger.debug(TAG, "Assistant service ready")
    }

    override fun onShutdown() {
        if (current === this) current = null
        super.onShutdown()
    }

    /** False when Android wouldn't open the session, for example when another assistant took over. */
    internal fun requestAirplane(enabled: Boolean): Boolean = runCatching {
        // No assist flags: the session needs no screenshot or screen contents.
        showSession(Bundle().apply { putBoolean(AssistantAirplane.ARG_AIRPLANE_ENABLED, enabled) }, 0)
    }.onFailure { AppLogger.debug(TAG, "showSession failed: ${it.message}") }.isSuccess

    companion object {
        private const val TAG = "AssistantService"

        @Volatile
        internal var current: RuleDeckVoiceInteractionService? = null
            private set
    }
}
