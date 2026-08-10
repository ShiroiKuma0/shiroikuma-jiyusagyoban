package com.opentasker.core.actions

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.KeyEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.platform.AndroidAudioHardening
import com.opentasker.core.platform.AudioUsageEligibility

/**
 * Play a sound or music file.
 *
 * Args:
 *   - "path": file path or URI (e.g., content://media/external/audio/media/123)
 *   - "volume": 0-100 (optional)
 */
class PlaySoundAction : DeclaredAction(ActionCatalog.require("sound.play")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        AndroidAudioHardening.failureIfIneligible(ctx, "sound playback")?.let { return it }
        val volume = args["volume"]?.toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }

        val player = try {
            val uri = if (path.contains("://")) Uri.parse(path) else Uri.parse("file://$path")
            MediaPlayer.create(ctx.app, uri)
                ?: return ActionResult.Failure("could not create player for: $path")
        } catch (ex: Exception) {
            return ActionResult.Failure("failed to open: ${ex.message}", ex)
        }

        if (volume != null) {
            player.setVolume(volume, volume)
        }

        ctx.logger("Play: $path")
        return suspendCancellableCoroutine { cont ->
            player.setOnCompletionListener {
                player.release()
                cont.resumeWith(Result.success(ActionResult.Success))
            }
            player.setOnErrorListener { _, what, extra ->
                player.release()
                cont.resumeWith(Result.success(ActionResult.Failure("playback error: what=$what extra=$extra")))
                true
            }
            cont.invokeOnCancellation { player.release() }
            player.start()
        }
    }
}

/**
 * Stop/pause audio playback.
 */
class StopSoundAction : DeclaredAction(ActionCatalog.require("sound.stop")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Stop playback")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_STOP)
    }
}

/**
 * Pause audio playback.
 */
class PauseSoundAction : DeclaredAction(ActionCatalog.require("sound.pause")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Pause playback")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }
}

/**
 * Next track.
 */
class NextTrackAction : DeclaredAction(ActionCatalog.require("track.next")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Next track")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

/**
 * Previous track.
 */
class PreviousTrackAction : DeclaredAction(ActionCatalog.require("track.previous")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Previous track")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
}

/**
 * Mute audio.
 *
 * Args:
 *   - "stream": stream type (music, ring, notification, etc.)
 */
class MuteAction : DeclaredAction(ActionCatalog.require("media.mute")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val stream = args["stream"] ?: "music"
        val streamType = streamType(stream) ?: return ActionResult.Failure("invalid stream: $stream")
        AndroidAudioHardening.failureIfIneligible(
            ctx = ctx,
            operation = "mute",
            usage = audioUsageForStreamType(streamType),
        )?.let { return it }
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")
        return try {
            am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0)
            ctx.logger("Mute $stream")
            ActionResult.Success
        } catch (ex: SecurityException) {
            ActionResult.Failure("mute blocked by DND policy: ${ex.message}", ex)
        }
    }
}

private fun dispatchMediaKey(ctx: ActionContext, keyCode: Int): ActionResult {
    AndroidAudioHardening.failureIfIneligible(ctx, "media-key dispatch")?.let { return it }
    val audioManager = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ?: return ActionResult.Failure("audio service not available")

    return try {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        ActionResult.Success
    } catch (ex: RuntimeException) {
        ActionResult.Failure("media key dispatch failed: ${ex.message}", ex)
    }
}

private fun streamType(name: String): Int? = when (name.lowercase()) {
    "music", "media" -> AudioManager.STREAM_MUSIC
    "alarm" -> AudioManager.STREAM_ALARM
    "ring", "ringer" -> AudioManager.STREAM_RING
    "notification" -> AudioManager.STREAM_NOTIFICATION
    "system" -> AudioManager.STREAM_SYSTEM
    "voice", "call" -> AudioManager.STREAM_VOICE_CALL
    else -> null
}

private fun audioUsageForStreamType(streamType: Int): AudioUsageEligibility =
    if (streamType == AudioManager.STREAM_ALARM) {
        AudioUsageEligibility.ALARM
    } else {
        AudioUsageEligibility.GENERAL
    }
