package com.opentasker.core.actions

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import kotlinx.coroutines.suspendCancellableCoroutine
// The fork keeps plain Action implementations alongside upstream's DeclaredAction ones in this
// file, so both symbols stay imported.
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
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
 *   - "stream": media (default) / notification / ring / alarm / system — the volume stream the
 *     sound rides. notification/ring/system follow the ringer mode, so the system-bar
 *     vibrate/silent tile mutes them (白い熊: 通知明滅 tones must respect quiet mode); media and
 *     alarm play regardless.
 *   - "wait": true (default) blocks the task until playback finishes; false starts playback and
 *     returns immediately so the next actions (vibration, overlays) run WHILE the sound plays —
 *     a notification tone must buzz and sound simultaneously, not tone-then-buzz (白い熊: 通知明滅).
 *   - "loop": true repeats the sound until it is stopped or "seconds" runs out (default false).
 *   - "seconds": stop playback after this many seconds, however far the sound has got — the
 *     ceiling on a looped ring (the doorbell rings for 120 s).
 *   - "name": register the player under this name so `sound.stop` with the same name can silence
 *     it from another task (the doorbell's floating stop button). Playing a name that is already
 *     sounding stops the old player first, so a second ring restarts the count rather than stacking.
 */
/** Schemes MediaPlayer may open directly: everything else must go through the HTTP action. */
private val LOCAL_SOUND_SCHEMES = setOf("file", "content", "android.resource")

class PlaySoundAction : DeclaredAction(ActionCatalog.require("sound.play")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val path = args["path"] ?: return ActionResult.Failure("missing path")
        AndroidAudioHardening.failureIfIneligible(ctx, "sound playback")?.let { return it }
        val volume = args["volume"]?.toFloatOrNull()?.let { (it / 100f).coerceIn(0f, 1f) }
        val attributes = when (val stream = args["stream"]?.trim()?.lowercase().orEmpty()) {
            "", "media", "music" -> null // MediaPlayer default: USAGE_MEDIA
            "notification" -> soundAttributes(AudioAttributes.USAGE_NOTIFICATION)
            "ring" -> soundAttributes(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            "alarm" -> soundAttributes(AudioAttributes.USAGE_ALARM)
            "system" -> soundAttributes(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            else -> return ActionResult.Failure("unknown stream: $stream (use media, notification, ring, alarm or system)")
        }

        val player = try {
            val uri = if (path.contains("://")) Uri.parse(path) else Uri.parse("file://$path")
            // Local sources only. MediaPlayer will happily stream a remote URL, and that traffic
            // never passes the HTTP action's policy checks - which is the sole justification for
            // the manifest's cleartext allowance - so a public http:// sound would have punched
            // straight through the private-network rule this app documents as enforced in code.
            val scheme = uri.scheme?.lowercase()
            if (scheme != null && scheme !in LOCAL_SOUND_SCHEMES) {
                return ActionResult.Failure(
                    "sound.play accepts a file path or content URI; $scheme:// sources are not supported",
                )
            }
            val created =
                if (attributes == null) MediaPlayer.create(ctx.app, uri)
                else MediaPlayer.create(ctx.app, uri, null, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE)
            created ?: return ActionResult.Failure("could not create player for: $path")
        } catch (ex: Exception) {
            return ActionResult.Failure("failed to open: ${ex.message}", ex)
        }

        if (volume != null) {
            player.setVolume(volume, volume)
        }

        val loop = args["loop"]?.trim()?.lowercase() == "true"
        val seconds = args["seconds"]?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
        val name = args["name"]?.trim().orEmpty()
        player.isLooping = loop
        // A looped or timed sound can outlive the task by minutes; hold the CPU so the screen going
        // off does not starve the decoder mid-ring. MediaPlayer drops the lock itself on release.
        if (loop || seconds != null) player.setWakeMode(ctx.app, PowerManager.PARTIAL_WAKE_LOCK)

        ctx.logger("Play: $path" + (if (loop) " (loop)" else "") + (seconds?.let { " for ${it}s" } ?: ""))
        val wait = args["wait"]?.trim()?.lowercase() != "false"
        val handle = NamedSoundPlayers.register(name, player, seconds)
        if (!wait) {
            // Fire-and-forget: the player outlives the action and releases itself when done.
            player.setOnCompletionListener { handle.finish() }
            player.setOnErrorListener { _, _, _ -> handle.finish(); true }
            player.start()
            return ActionResult.Success
        }
        return suspendCancellableCoroutine { cont ->
            handle.onFinished = { cont.resumeWith(Result.success(ActionResult.Success)) }
            player.setOnCompletionListener { handle.finish() }
            player.setOnErrorListener { _, what, extra ->
                handle.onFinished = {
                    cont.resumeWith(Result.success(ActionResult.Failure("playback error: what=$what extra=$extra")))
                }
                handle.finish()
                true
            }
            cont.invokeOnCancellation { handle.finish() }
            player.start()
        }
    }
}

/**
 * The players `sound.play` started, so one can be stopped from outside the task that started it —
 * `sound.stop` otherwise only sends a media key, which our own MediaPlayer never hears.
 *
 * Every player gets a [Handle] whether named or not; the handle owns the release, the optional
 * "seconds" deadline and the name slot, so the three ways a sound ends (it finishes, the deadline
 * passes, someone stops it) all go through one idempotent [Handle.finish].
 */
object NamedSoundPlayers {
    private val main = Handler(Looper.getMainLooper())
    private val byName = mutableMapOf<String, Handle>()

    class Handle internal constructor(val name: String, private val player: MediaPlayer) {
        @Volatile var onFinished: (() -> Unit)? = null
        internal val deadline = Runnable { finish() }
        private var done = false

        fun finish() {
            synchronized(this) {
                if (done) return
                done = true
            }
            main.removeCallbacks(deadline)
            synchronized(byName) { if (byName[name] === this) byName.remove(name) }
            runCatching { player.stop() }
            player.release()
            onFinished?.invoke()
        }
    }

    fun register(name: String, player: MediaPlayer, seconds: Double?): Handle {
        val handle = Handle(name, player)
        if (name.isNotEmpty()) {
            val previous = synchronized(byName) { byName.put(name, handle) }
            previous?.finish()
        }
        seconds?.let { main.postDelayed(handle.deadline, (it * 1000).toLong()) }
        return handle
    }

    /** Stop the player registered as [name], or every named one for "*". True when one was sounding. */
    fun stop(name: String): Boolean {
        val handles = synchronized(byName) {
            if (name == "*") byName.values.toList() else listOfNotNull(byName[name])
        }
        handles.forEach { it.finish() }
        return handles.isNotEmpty()
    }
}

/**
 * Stop audio playback.
 *
 * Args:
 *   - "name": stop the sound a `sound.play` registered under this name ("*" = every named one).
 *     Empty keeps the old behaviour: a media STOP key for whatever player app is in charge.
 */
class StopSoundAction : DeclaredAction(ActionCatalog.require("sound.stop")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isNotEmpty()) {
            val stopped = NamedSoundPlayers.stop(name)
            ctx.logger(if (stopped) "Stopped sound: $name" else "No sound playing as: $name")
            return ActionResult.Success
        }
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
 * Toggle play/pause (single media key that resumes if paused, pauses if playing).
 */
class TogglePlayPauseAction : Action {
    override val id = "media.playpause"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.logger("Toggle play/pause")
        return dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
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

/** Sonification attributes for a non-media usage, so the sound rides that usage's volume stream
 *  (and, for notification/ring/system, is muted by the ringer's vibrate/silent modes). */
private fun soundAttributes(usage: Int): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

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
