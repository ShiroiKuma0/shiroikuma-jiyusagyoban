package com.sysadmindoc.ruledeck.core.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.sysadmindoc.ruledeck.MainActivity
import com.sysadmindoc.ruledeck.core.logging.AppLogger

class RuleDeckVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = RuleDeckVoiceSession(this)
}

/**
 * Has no window. A task's request starts Settings' airplane mode handler as a voice activity and
 * waits for its answer; anything else (someone holding the home or power button) opens RuleDeck,
 * so it's clear which app answered.
 */
internal class RuleDeckVoiceSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val giveUp = Runnable {
        AssistantAirplane.answer("Settings never answered the assistant's request.")
        hide()
    }

    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (args == null || !args.containsKey(AssistantAirplane.ARG_AIRPLANE_ENABLED)) {
            openApp()
            hide()
            return
        }
        val enabled = args.getBoolean(AssistantAirplane.ARG_AIRPLANE_ENABLED)
        runCatching { startVoiceActivity(AssistantAirplane.intentFor(enabled)) }
            .onSuccess { handler.postDelayed(giveUp, AssistantAirplane.ANSWER_TIMEOUT_MS) }
            .onFailure { error ->
                AppLogger.debug(TAG, "Settings' airplane mode handler didn't start: ${error.message}")
                AssistantAirplane.answer("This phone's Settings app doesn't take airplane mode requests from the assistant (${error.javaClass.simpleName}).")
                hide()
            }
    }

    override fun onRequestCompleteVoice(request: CompleteVoiceRequest) {
        runCatching { request.sendCompleteResult(null) }
        finish(null)
    }

    override fun onRequestAbortVoice(request: AbortVoiceRequest) {
        runCatching { request.sendAbortResult(null) }
        finish("Settings refused to change airplane mode: ${request.voicePrompt?.visualPrompt ?: "no reason given"}.")
    }

    override fun onHide() {
        handler.removeCallbacks(giveUp)
        super.onHide()
    }

    private fun finish(reason: String?) {
        handler.removeCallbacks(giveUp)
        AssistantAirplane.answer(reason)
        hide()
    }

    private fun openApp() {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(intent) else context.startActivity(intent)
        }.onFailure { AppLogger.debug(TAG, "Could not open RuleDeck from the assistant: ${it.message}") }
    }

    private companion object {
        const val TAG = "AssistantSession"
    }
}
