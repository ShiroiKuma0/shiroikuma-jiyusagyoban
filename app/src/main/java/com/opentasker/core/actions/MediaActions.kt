package com.opentasker.core.actions

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Play a sound or music file.
 *
 * Args:
 *   - "path": file path or URI (e.g., content://media/external/audio/media/123)
 *   - "volume": 0-100 (optional)
 */
class PlaySoundAction : Action {
    override val id = "sound.play"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        ctx.logger("Play: $path")
        return ActionResult.Failure("Direct media playback is not implemented yet")
    }
}

/**
 * Stop/pause audio playback.
 */
class StopSoundAction : Action {
    override val id = "sound.stop"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Stop playback")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_STOP)
    }
}

/**
 * Pause audio playback.
 */
class PauseSoundAction : Action {
    override val id = "sound.pause"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Pause playback")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
    }
}

/**
 * Next track.
 */
class NextTrackAction : Action {
    override val id = "track.next"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Next track")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}

/**
 * Previous track.
 */
class PreviousTrackAction : Action {
    override val id = "track.previous"
    override val category = ActionCategory.MEDIA

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
class MuteAction : Action {
    override val id = "media.mute"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val stream = args["stream"] ?: "music"
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")
        val streamType = streamType(stream) ?: return ActionResult.Failure("invalid stream: $stream")
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
