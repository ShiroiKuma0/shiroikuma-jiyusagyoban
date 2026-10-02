package com.opentasker.core.gengoshima

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.opentasker.ui.gengoshima.GengoshimaPlayerActivity

/**
 * 言語島's player — ExoPlayer behind a MediaSession, in a process of its own (`:gengoshima`).
 *
 * **Its own process** so that a player fault cannot take the automation engine down with it: the
 * engine is what runs 白い熊's whole phone. The price is that this process touches no database —
 * the player window, in the main process, logs what was played — and the application class skips
 * all of its start-up here (see `OpenTaskerApp_NoHilt.isSideProcess`).
 *
 * **A MediaSession** so the steering wheel, a Bluetooth headset and the lock screen all drive it:
 * their next/previous move between SENTENCES ([SentencePlayer]), not between the repeats Shadow
 * mode queues.
 *
 * **Pauses between items are the service's**, not baked into the audio, because they depend on the
 * mode: Shadow leaves room to say the sentence back after each repeat, Recall leaves room to say it
 * BEFORE hearing it. Each queued item carries its own `pauseAfterMs`; the player pauses at the end of
 * every item and this service resumes it when that pause is over. While it waits, the session's
 * extras say so ([EXTRA_GAP_UNTIL]), so the window can show "your turn".
 */
class GengoshimaPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resume: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.pauseAtEndOfMediaItems = true
        val player = SentencePlayer(exo)
        exo.addListener(
            object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                        gapThenContinue(exo)
                    } else {
                        // Any other change — the user paused, played, or skipped — ends a gap.
                        endGap()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) endGap()
                }
            },
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, GengoshimaPlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    /** The item that just ended asked for a pause after it: wait, then go on to the next one. */
    private fun gapThenContinue(exo: ExoPlayer) {
        val item = exo.currentMediaItem ?: return
        val pause = item.mediaMetadata.extras?.getLong(EXTRA_PAUSE_AFTER_MS) ?: 0L
        val last = exo.currentMediaItemIndex >= exo.mediaItemCount - 1
        if (last) {
            endGap()
            return
        }
        val until = System.currentTimeMillis() + pause
        session?.setSessionExtras(Bundle().apply { putLong(EXTRA_GAP_UNTIL, until) })
        resume?.let(handler::removeCallbacks)
        val r = Runnable {
            session?.setSessionExtras(Bundle.EMPTY)
            exo.seekToNextMediaItem()
            exo.play()
        }
        resume = r
        handler.postDelayed(r, pause.coerceAtLeast(0L))
    }

    private fun endGap() {
        resume?.let(handler::removeCallbacks)
        resume = null
        session?.setSessionExtras(Bundle.EMPTY)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        endGap()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Next and previous move by SENTENCE. Shadow mode queues each sentence several times; a steering
     * wheel's "next" meaning "the next repeat of the same line" would make the buttons useless in the
     * mode that needs them most. Previous restarts the current sentence unless it has only just
     * begun, as every player does.
     */
    private class SentencePlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        private fun sentenceAt(i: Int): Long =
            exo.getMediaItemAt(i).mediaMetadata.extras?.getLong(EXTRA_SENTENCE_ID) ?: i.toLong()

        private fun groupStart(i: Int): Int {
            var k = i
            val id = sentenceAt(i)
            while (k > 0 && sentenceAt(k - 1) == id) k--
            return k
        }

        override fun seekToNext() {
            val n = exo.mediaItemCount
            if (n == 0) return
            val id = sentenceAt(exo.currentMediaItemIndex)
            var k = exo.currentMediaItemIndex
            while (k < n && sentenceAt(k) == id) k++
            if (k < n) exo.seekTo(k, 0L)
        }

        override fun seekToNextMediaItem() = seekToNext()

        override fun seekToPrevious() {
            if (exo.mediaItemCount == 0) return
            val start = groupStart(exo.currentMediaItemIndex)
            if (exo.currentMediaItemIndex != start || exo.currentPosition > 2_000L || start == 0) {
                exo.seekTo(start, 0L)
            } else {
                exo.seekTo(groupStart(start - 1), 0L)
            }
        }

        override fun seekToPreviousMediaItem() = seekToPrevious()

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
    }

    companion object {
        const val EXTRA_SENTENCE_ID = "sentenceId"
        const val EXTRA_ISLAND_ID = "islandId"
        const val EXTRA_PAUSE_AFTER_MS = "pauseAfterMs"
        const val EXTRA_EN = "en"
        const val EXTRA_TOKENS = "tokens"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_REPEATS = "repeats"
        const val EXTRA_DURATION_MS = "durationMs"
        /** `ja` or `en`: which reading this queued item is. */
        const val EXTRA_LANG = "lang"

        /** Session extra: wall-clock millis until which the player is deliberately silent. */
        const val EXTRA_GAP_UNTIL = "gapUntil"
    }
}
