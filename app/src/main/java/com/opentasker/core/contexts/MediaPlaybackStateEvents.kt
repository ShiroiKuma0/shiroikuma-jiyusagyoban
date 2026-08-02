package com.opentasker.core.contexts

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.util.Locale

/** Polls the platform playback signal only while the state source is subscribed. */
object MediaPlaybackStateEvents {
    private const val POLL_INTERVAL_MS = 2_000L

    fun events(app: Context): Flow<Map<String, String>> = flow {
        var previous: Map<String, String>? = null
        while (currentCoroutineContext().isActive) {
            val current = snapshot(app)
            if (current != previous) emit(current)
            previous = current
            delay(POLL_INTERVAL_MS)
        }
    }

    internal fun snapshot(app: Context): Map<String, String> {
        val audioActive = app.getSystemService(AudioManager::class.java)?.let {
            @Suppress("DEPRECATION")
            it.isMusicActive
        } == true
        val sessionPackages = runCatching {
            app.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(null)
                .orEmpty()
                .filter { controller -> controller.playbackState?.state in ACTIVE_PLAYBACK_STATES }
                .map { it.packageName }
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_PACKAGES)
        }.getOrDefault(emptyList())
        return mapOf(
            "media_active" to (audioActive || sessionPackages.isNotEmpty()).toString(),
            "media_package" to sessionPackages.joinToString(","),
        )
    }

    internal fun packageMatches(actual: String, expected: String): Boolean =
        actual.split(',')
            .map { it.trim().lowercase(Locale.US) }
            .filter(String::isNotBlank)
            .contains(expected.trim().lowercase(Locale.US))

    private val ACTIVE_PLAYBACK_STATES = setOf(
        android.media.session.PlaybackState.STATE_PLAYING,
        android.media.session.PlaybackState.STATE_BUFFERING,
        android.media.session.PlaybackState.STATE_CONNECTING,
    )
    private const val MAX_PACKAGES = 8
}
