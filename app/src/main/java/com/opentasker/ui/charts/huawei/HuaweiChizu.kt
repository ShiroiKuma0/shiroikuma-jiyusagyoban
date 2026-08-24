package com.opentasker.ui.charts.huawei

import android.content.Context
import android.content.Intent
import com.opentasker.core.actions.IntentReplyBridge
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiWalkLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * Handing a walk to 白い熊 地図, and keeping what it draws.
 *
 * ## The channel, and why it is this one
 *
 * The same binder-free round trip every sister app here uses: an ORDERED broadcast out with
 * `FLAG_INCLUDE_STOPPED_PACKAGES` — 白い熊 freezes apps, and a plain broadcast never reaches a
 * stopped one — and a fresh broadcast back to our own exported receiver carrying only string extras,
 * keyed by a random id the target echoes. This EMUI will not reliably carry a `ResultReceiver` or a
 * `PendingIntent` into another app's manifest receiver: one gets the whole broadcast dropped, the
 * other is delivered and never fires. Learned on shiroikuma.jami, confirmed again with 天気, and
 * 地図 reports arriving at the same conclusion independently.
 *
 * ## Conforming to a receiver that already exists
 *
 * These actions go to `ChizuStateExportReceiver`, which 地図 already ships for 保存復元 — same
 * exported receiver, same automation switch, same 24-byte token. That is why the token setting is
 * shared with nothing else and why an unset token is a plain refusal here rather than an attempt:
 * a request without one is answered `ERROR:bad token`, and it is cheaper to say so before sending.
 *
 * ## Why the answer is read twice
 *
 * 地図 replies with named extras AND a pipe-packed `result` summary carrying the same three values.
 * That redundancy is deliberate on their side — an older bridge of ours dropped every extra but
 * `result` — so we read the named extras first and fall back to splitting the summary. Belt and
 * braces costs one function; a silently empty picture path costs an afternoon.
 *
 * ## Why images travel as file paths
 *
 * A rendered map is hundreds of kilobytes and a broadcast is not the place for it. Both apps can
 * reach ordinary shared storage — and neither can reach the other's `Android/data/`, which no
 * permission opens — so the walks live under 白い熊's backup tree and both sides use plain paths.
 * We still copy the PNGs into the walk's own folder: a path into another app's storage breaks when
 * it reorganises, and a frozen app cannot be read at all.
 */
object HuaweiChizu {

    const val PACKAGE = "shiroikuma.chizu"
    const val ACTION_IMPORT_TRACK = "shiroikuma.chizu.action.IMPORT_TRACK"
    const val ACTION_SHOW_TRACK = "shiroikuma.chizu.action.SHOW_TRACK"

    /**
     * The button's path since 地図 `5.4.0+024`: an Activity, started directly.
     *
     * **Carries no token, deliberately.** It does nothing a launcher icon cannot — open the app and
     * choose which track is on screen — so a gate would buy no safety and would break the button
     * silently every time the token drifted. The token still guards the receiver, which is where
     * headless work happens and where it means something.
     */
    const val ACTION_OPEN_TRACK = "shiroikuma.chizu.action.OPEN_TRACK"

    /**
     * Long, because the worst case is real: 地図 allows up to 180 s for a cold app to finish
     * initializing and only then up to 120 s for the render. Both are far under a second in
     * practice, but a reply that lands outside this window arrives as an unmatched id — silently
     * discarded, and indistinguishable from a failure. Waiting is the cheaper mistake.
     */
    private const val TIMEOUT_MS = 300_000L

    /**
     * Above this, the track is handed over as a path rather than inline.
     *
     * 白い熊 specified inline delivery and estimated the tracks at about 30 kB. Measured, they are
     * roughly **89 bytes per point**: the first real walk is 1763 points and 153 kB, and an hour on
     * foot would be nearer 370 kB. A broadcast is carried by a Binder transaction with about a
     * megabyte for everything in flight, so inlining every track would work for months and then
     * throw `TransactionTooLargeException` on a long walk — the failure would arrive as a lost walk,
     * not as a warning. Under the ceiling the track travels in the broadcast as asked; over it, by
     * path, which both apps can read because the walks live in ordinary shared storage.
     */
    private const val INLINE_MAX_BYTES = 128 * 1024

    /**
     * Short, because [show]'s broadcast only selects a track and the app is opened either way. A
     * long wait here would freeze the grid behind a button whose real work is a screen change.
     */
    private const val SHOW_TIMEOUT_MS = 12_000L

    /**
     * 4:3, because the cells are. 地図 renders each size separately rather than downscaling one
     * image — vector map labels turn to mush when shrunk — so asking for the shape we draw is free.
     */
    private const val THUMB_W = 480
    private const val THUMB_H = 360
    private const val MAP_W = 1440
    private const val MAP_H = 1080

    data class Outcome(val ok: Boolean, val message: String)

    /**
     * Send one walk. Returns what happened, in words fit to show 白い熊.
     *
     * Never throws: a sister app that is missing, frozen or refusing is an ordinary state here, and
     * the caller's job is to say so on the card rather than to crash the window.
     */
    suspend fun share(context: Context, walk: HuaweiWalkLibrary.Walk): Outcome {
        if (!walk.gpx.isFile) return Outcome(false, "no track file for this walk")
        val token = HuaweiSettings.chizuToken(context)
        if (token.isNullOrBlank()) return Outcome(false, "no 地図 token — set %Huawei_ChizuToken")

        val intent = request(ACTION_IMPORT_TRACK, token).apply {
            // Both, always: 地図 prefers the inline copy when it is there, and the path stays as the
            // thing that still works when it is not.
            putExtra("gpx_path", walk.gpx.absolutePath)
            if (walk.gpx.length() <= INLINE_MAX_BYTES) {
                runCatching { walk.gpx.readText() }.getOrNull()?.let { putExtra("gpx_data", it) }
            }
            putExtra("name", "${walk.kind} ${walkWhen(walk)}")
            putExtra("out_dir", walk.dir.absolutePath)
            // Sending back the id we were given makes a re-share overwrite in place. Without it a
            // walk shared twice becomes two tracks in 地図, and the second one is not a new walk.
            walk.trackId?.let { putExtra("track_id", it) }
            putExtra("thumb_w", THUMB_W.toString())
            putExtra("thumb_h", THUMB_H.toString())
            putExtra("map_w", MAP_W.toString())
            putExtra("map_h", MAP_H.toString())
            // Pinned rather than left on `auto`. A grid whose pictures were drawn under whichever
            // theme happened to be current reads as broken, and these images are kept for years.
            putExtra("night", "day")
        }

        val reply = roundTrip(context, intent) ?: return Outcome(false, "地図 did not answer")
        if (!reply.result.startsWith("OK:")) return Outcome(false, reply.result)

        // The packed summary's field order, which is 地図's to define and has changed once already.
        // Named extras are read first and this is only the fallback, but a fallback that silently
        // returns the wrong field is worse than no fallback, so the order is written down here.
        val packed = reply.result.removePrefix("OK:").split('|')
        val slots = listOf("track_id", "gpx_path", "thumb_path", "map_path", "distance_m", "duration_s")
        fun field(name: String): String? =
            reply.extras[name]?.takeIf { it.isNotBlank() }
                ?: packed.getOrNull(slots.indexOf(name))?.trim()?.takeIf { it.isNotBlank() }

        val thumb = field("thumb_path")?.let { copyInto(it, File(walk.dir, "map-thumb.png")) }
        val map = field("map_path")?.let { copyInto(it, File(walk.dir, "map.png")) }
        HuaweiWalkLibrary.recordMap(
            walk = walk,
            trackId = field("track_id"),
            thumbPath = thumb,
            mapPath = map,
            // 地図's own arithmetic over the GPX, kept beside the band's own figures and never merged
            // with them. Two independent measurements of one route is exactly the instrument that
            // catches a decoder reading the format slightly wrongly — which has happened once here
            // already, and was only caught because a number looked wrong.
            chizu = HuaweiWalkLibrary.ChizuReading(
                distanceMetres = field("distance_m")?.toDoubleOrNull(),
                durationSeconds = field("duration_s")?.toLongOrNull(),
                movingSeconds = reply.extras["moving_time_s"]?.toLongOrNull(),
                climbMetres = reply.extras["elevation_up"]?.toDoubleOrNull(),
                descentMetres = reply.extras["elevation_down"]?.toDoubleOrNull(),
                // `map` = real streets, `basemap` = only the bundled world map was underneath,
                // `none` = the plain fallback. Recorded because it is the difference between a
                // picture worth keeping and one worth drawing again later, and nothing else on
                // this side can tell them apart — both are a route on a pale ground.
                detail = reply.extras["map_detail"],
            ),
        )
        return Outcome(true, when (reply.extras["map_detail"]) {
            "map" -> "地図 drew the map"
            "basemap", "none" -> "saved — but no map covers this walk yet"
            else -> if (map != null) "地図 drew the map" else "saved to 地図"
        })
    }

    /**
     * Open the walk in 地図 itself — the button for looking at a route properly, with the zoom and
     * the layers a picture cannot have.
     *
     * ## Why a button must not be a broadcast
     *
     * This was first built as `SHOW_TRACK`, a broadcast asking 地図 to bring itself to the front.
     * **A receiver cannot do that.** Since Android 10 an app with no visible window may not start an
     * activity, and 地図 has none at the moment a broadcast arrives — we are the app in front. Its
     * `startActivity` was refused silently: no exception on its side, no error in the reply, nothing
     * on screen. The button appeared to do nothing at all, in both places it appears.
     *
     * The fix is not to time anything. It is to notice that **we** have the foreground and 地図 does
     * not, so the app that can legitimately start an activity is this one. Since `5.4.0+024` 地図
     * exposes an Activity for exactly that: we start it, it holds the foreground we hand it while it
     * finds the track and waits out a cold start, then raises the map itself. One intent, nothing
     * ordered against anything, nothing that can be refused.
     *
     * [showViaBroadcast] remains for a 地図 older than that, and only for the window between this
     * build being installed and that one.
     */
    suspend fun show(context: Context, walk: HuaweiWalkLibrary.Walk): Outcome {
        val trackId = walk.trackId ?: return Outcome(false, "not sent to 地図 yet")

        // The good path: one intent, from our own foreground. Nothing in it can be refused, nothing
        // is ordered against anything else, and the Activity holds the foreground we hand it while
        // it waits for a cold 地図 to finish starting.
        val open = Intent(ACTION_OPEN_TRACK)
            .setPackage(PACKAGE)
            .putExtra("track_id", trackId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Resolution here is subject to package visibility. This app is covered by an explicit
        // `<package android:name="shiroikuma.chizu" />` in `<queries>`, checked rather than inferred
        // from the MAIN/LAUNCHER entry beside it — because if 地図 were invisible, this would return
        // null with the Activity installed, the fallback would be taken permanently, and nothing
        // would look wrong: the old path works too. A silent downgrade to the thing the Activity
        // exists to escape. The two branches therefore say different words, so which one ran is
        // visible on the card without any logging at all.
        if (context.packageManager.resolveActivity(open, 0) != null) {
            return try {
                context.startActivity(open)
                Outcome(true, "opened 地図")
            } catch (e: Throwable) {
                Outcome(false, e.message ?: "could not open 地図")
            }
        }

        return showViaBroadcast(context, trackId)
    }

    /**
     * The path for a 地図 older than `5.4.0+024`, kept because the button must not break in the
     * window between this build being installed and that one.
     *
     * `SHOW_TRACK` cannot bring 地図 forward by itself — a receiver with no window may not start an
     * activity, and the system refuses it silently. So the broadcast does the half a receiver can do
     * and we start the app, which we may, having a visible window of our own. Since `+024` the
     * broadcast also records a pending map target, so the map lands on the walk whichever of us
     * raises it; before that it selects the track without centring on it.
     */
    private suspend fun showViaBroadcast(context: Context, trackId: String): Outcome {
        val token = HuaweiSettings.chizuToken(context)
        if (token.isNullOrBlank()) return Outcome(false, "no 地図 token — set %Huawei_ChizuToken")

        val intent = request(ACTION_SHOW_TRACK, token).putExtra("track_id", trackId)
        val reply = roundTrip(context, intent, SHOW_TIMEOUT_MS)
        if (reply != null && !reply.result.startsWith("OK:")) return Outcome(false, reply.result)

        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return Outcome(false, "白い熊 地図 is not installed")
        return try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome(true, "opened 地図 — old path, install 地図 5.4.0+024")
        } catch (e: Throwable) {
            Outcome(false, e.message ?: "could not open 地図")
        }
    }

    private fun request(action: String, token: String) = Intent(action).apply {
        setPackage(PACKAGE)
        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        putExtra("token", token)
        putExtra(IntentReplyBridge.EXTRA_REPLY_ACTION, IntentReplyBridge.ACTION_INTENT_REPLY)
    }

    private suspend fun roundTrip(
        context: Context,
        intent: Intent,
        timeoutMs: Long = TIMEOUT_MS,
    ): IntentReplyBridge.Reply? {
        val replyId = UUID.randomUUID().toString()
        val answer = CompletableDeferred<IntentReplyBridge.Reply?>()
        IntentReplyBridge.register(replyId) { answer.complete(it) }
        intent.putExtra(IntentReplyBridge.EXTRA_REPLY_PACKAGE, context.packageName)
        intent.putExtra(IntentReplyBridge.EXTRA_REPLY_ID, replyId)
        return try {
            context.sendOrderedBroadcast(intent, null)
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } catch (e: Throwable) {
            // Not installed, or refusing the broadcast outright. Silence and refusal read the same
            // from here, and neither is a reason to lose the walk.
            null
        } finally {
            IntentReplyBridge.cancel(replyId)
        }
    }

    private fun copyInto(from: String, to: File): String? = runCatching {
        val src = File(from)
        if (!src.isFile) return null
        if (src.canonicalPath != to.canonicalPath) src.copyTo(to, overwrite = true)
        to.absolutePath
    }.getOrNull()
}
