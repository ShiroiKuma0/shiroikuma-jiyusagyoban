package com.opentasker.core.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android won't accept an assistant app that names no speech recognizer, and OpenTasker doesn't
 * listen to anyone. Every request is answered with an error at once, so nothing records audio.
 */
class OpenTaskerRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
