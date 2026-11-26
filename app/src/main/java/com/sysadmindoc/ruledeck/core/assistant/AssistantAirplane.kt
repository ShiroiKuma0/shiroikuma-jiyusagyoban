package com.sysadmindoc.ruledeck.core.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import kotlinx.coroutines.delay

/**
 * Airplane mode for a phone that can't run Shizuku. Android lets the digital assistant app ask
 * Settings to switch it, through a voice activity started from an assistant session, so a person
 * who keeps developer options off (some banking apps refuse to run otherwise) still has a route.
 * Settings makes the change through the same call its own toggle uses, so the radios follow.
 */
object AssistantAirplane {
    internal const val ARG_AIRPLANE_ENABLED = "com.sysadmindoc.ruledeck.assistant.AIRPLANE_ENABLED"

    /** Long enough for Settings to start and answer, short enough that a stuck session can't hold a task. */
    internal const val ANSWER_TIMEOUT_MS = 8_000L
    private const val POLL_MS = 150L

    /**
     * Android binds the service again after a crash or a low-memory kill, but not after a force
     * stop; opening the app or the assist gesture doesn't either. Picking the role again does
     * (measured on an API 35 emulator, 2026-11-21).
     */
    internal const val NOT_STARTED =
        "RuleDeck is the assistant app, but Android hasn't connected its assistant service. That happens after " +
            "RuleDeck is force stopped. Pick another assistant in Default apps, then RuleDeck again."
    internal const val SESSION_REFUSED = "Android refused to open an assistant session."

    private class Answer {
        @Volatile var reason: String? = null

        @Volatile var given = false
    }

    @Volatile
    private var pending: Answer? = null

    /** Whether Android has RuleDeck selected as the digital assistant app right now. */
    fun isActive(context: Context): Boolean = runCatching {
        VoiceInteractionService.isActiveService(context, ComponentName(context, RuleDeckVoiceInteractionService::class.java))
    }.getOrDefault(false)

    /** Whether Android has the assistant service running in this process, which a request needs. */
    fun isConnected(): Boolean = RuleDeckVoiceInteractionService.current != null

    /** The voice request Settings answers. Settings' handler only accepts it from a voice session. */
    internal fun intentFor(enabled: Boolean): Intent = Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
        .addCategory(Intent.CATEGORY_VOICE)
        .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, enabled)

    /**
     * Null once the setting reads [enabled], or why it didn't get there.
     *
     * @param openSession asks the running assistant service for a session: null when there is no
     *   service, false when Android refused the session
     */
    suspend fun set(
        enabled: Boolean,
        readEnabled: () -> Boolean,
        openSession: (Boolean) -> Boolean? = { RuleDeckVoiceInteractionService.current?.requestAirplane(it) },
        timeoutMs: Long = ANSWER_TIMEOUT_MS,
    ): String? {
        val answer = Answer()
        pending = answer
        try {
            when (openSession(enabled)) {
                null -> return NOT_STARTED
                false -> return SESSION_REFUSED
                true -> Unit
            }
            var waited = 0L
            while (waited < timeoutMs) {
                if (readEnabled() == enabled) return null
                if (answer.given) answer.reason?.let { return it }
                delay(POLL_MS)
                waited += POLL_MS
            }
            return if (readEnabled() == enabled) null else "Settings didn't switch airplane mode within ${timeoutMs / 1000} seconds."
        } finally {
            if (pending === answer) pending = null
        }
    }

    /** Called by the session with null when Settings finished, or a reason when it refused. */
    internal fun answer(reason: String?) {
        pending?.let { request ->
            request.reason = reason
            request.given = true
        }
    }
}
