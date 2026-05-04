package com.opentasker.core.actions

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.provider.MediaStore
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
    override val id = "media.play"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        ctx.logger("Play: $path")
        // TODO: Implement with MediaPlayer
        return ActionResult.Success
    }
}

/**
 * Stop/pause audio playback.
 */
class StopSoundAction : Action {
    override val id = "media.stop"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Stop playback")
        // TODO: Implement via AudioManager or MediaSession
        return ActionResult.Success
    }
}

/**
 * Pause audio playback.
 */
class PauseSoundAction : Action {
    override val id = "media.pause"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Pause playback")
        // TODO: Implement via AudioManager or MediaSession
        return ActionResult.Success
    }
}

/**
 * Next track.
 */
class NextTrackAction : Action {
    override val id = "media.next"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Next track")
        // TODO: Implement via media button intent or MediaSession
        return ActionResult.Success
    }
}

/**
 * Previous track.
 */
class PreviousTrackAction : Action {
    override val id = "media.prev"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Previous track")
        return ActionResult.Success
    }
}

/**
 * Mute audio.
 *
 * Args:
 *   - "stream": stream type (music, ring, notification, etc.)
 */
class MuteAction : Action {
    override val id = "audio.mute"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val stream = args["stream"] ?: "music"
        val am = ctx.app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ActionResult.Failure("audio service not available")
        ctx.logger("Mute $stream")
        // TODO: Implement with setStreamMute or deprecated setStreamVolume(0)
        return ActionResult.Success
    }
}
