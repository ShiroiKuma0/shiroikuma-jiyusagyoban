package com.opentasker.ui.charts.huawei

import com.opentasker.ui.charts.BandLanguage
import android.content.Context
import android.content.Intent
import com.opentasker.core.actions.IntentReplyBridge
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.maps.MapCutouts
import com.opentasker.core.huawei.maps.Mercator
import com.opentasker.core.huawei.HuaweiWorkoutStore
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
    /**
     * How long a 地図 round trip may take, and therefore how long our URI grant lives.
     *
     * 300 s was arithmetic done in our own favour: it subtracted 地図's 180 s initialization
     * ceiling and left ~120 s, but there is a second ceiling behind it — the rasterizer's own
     * `RENDER_TIMEOUT_MS` of 120 s. Stacked, the worst case is exactly 300 s and the margin is
     * zero (地図 chat, 2026-09-04). Neither ceiling is typical, and a warm app renders a block in
     * seconds; but a cold start over an area never rendered before is precisely where both run
     * long, and that is the first thing this contract will be asked to do.
     *
     * The cost of being too generous is a longer wait when 地図 is genuinely dead. The cost of
     * being too tight is a failure that looks like the URI plumbing and is not.
     *
     * **The signature of this being too short is specific, and it does not look like a timeout.**
     * 地図 keeps working after our window closes, so what is seen is SILENCE — no `ERROR:`, no
     * reply — and then, seconds later, the walk quietly appears in 地図's tracks folder after all.
     * A file that lands with no reply means the request succeeded and we stopped listening; this
     * constant is the dial, and nothing in the contract is at fault (地図 chat, 2026-09-04).
     */
    private const val TIMEOUT_MS = 420_000L

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
     * The two refusals a 地図 that predates the URI contract gives, word for word.
     *
     * Worth naming rather than passing through. Both are thrown before 地図 writes anything, so
     * nothing is created and nothing is half-written — but shown raw they read as a fault in THIS
     * app, and "no gpx: pass gpx_data or gpx_path" is a particularly bad thing to put in front of
     * 白い熊 when the truth is that the other app has not been rebuilt yet. Supplied by the 地図
     * chat on 2026-09-04; both strings disappear the moment its build lands, which is exactly what
     * makes them safe to match on.
     */
    private val OLD_BUILD = setOf(
        "ERROR:no gpx: pass gpx_data or gpx_path",
        "ERROR:no out_path",
    )

    private fun refusal(result: String, lang: BandLanguage): String =
        if (result.trim() in OLD_BUILD) HuaweiText.chizuNeedsRebuild[lang] else result


    /**
     * Send one walk. Returns what happened, in words fit to show 白い熊.
     *
     * Never throws: a sister app that is missing, frozen or refusing is an ordinary state here, and
     * the caller's job is to say so on the card rather than to crash the window.
     */
    suspend fun share(
        context: Context,
        walk: HuaweiWorkoutStore.Workout,
        gpx: String,
        dao: com.opentasker.core.storage.HuaweiWorkoutDao,
        lang: BandLanguage = BandLanguage.EN,
    ): Outcome {
        if (gpx.isBlank()) return Outcome(false, "no track to send for this workout")
        val token = HuaweiSettings.chizuToken(context)
        if (token.isNullOrBlank()) return Outcome(false, "no 地図 token — set %Huawei_ChizuToken")

        // The GPX exists for one round trip and nowhere else. It is regenerated from the band's own
        // track file each time, so there is no copy to go stale and none to back up.
        val handover = Handover.stage(
            context, "walk-${walk.number}-${walk.startSeconds}.gpx", gpx.toByteArray(),
        ) ?: return Outcome(false, "could not stage the track for 地図")
        try {
            val uri = Handover.uriFor(context, handover)
            Handover.grant(context, uri, write = false)
            val intent = request(ACTION_IMPORT_TRACK, token).apply {
                putExtra("gpx_uri", uri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("name", "${walk.kind} ${walkWhen(walk)}")
                // No `thumb_out_uri` and no `map_out_uri`, so 地図 renders no pictures at all. A
                // per-walk PNG was a megabyte of a street the shared cutout already covers, and
                // asking for one made 地図 spend a second drawing something nothing reads.
                //
                // `out_dir` is a transitional GUARD, not a request. A 地図 that does not yet know
                // `gpx_uri` falls through to its own default out_dir — which is the archive
                // directory this whole change exists to retire — and would recreate it on the spot.
                // Pointing it somewhere harmless costs nothing, and a new 地図 ignores it entirely
                // in URI mode. Remove once the URI build is confirmed on the phone.
                putExtra("out_dir", "/sdcard/tmp/chizu-out")
                // Sending back the id we were given makes a re-share overwrite in place. Without it
                // a walk shared twice becomes two tracks in 地図, and the second is not a new walk.
                walk.trackId?.let { putExtra("track_id", it) }
                putExtra("night", "day")
            }

            val reply = roundTrip(context, intent) ?: return Outcome(false, "地図 did not answer")
            if (!reply.result.startsWith("OK:")) return Outcome(false, refusal(reply.result, lang))

            // The packed summary's field order, which is 地図's to define and has changed once
            // already. Named extras are read first and this is only the fallback, but a fallback
            // that silently returns the wrong field is worse than none, so it is written down here.
            val packed = reply.result.removePrefix("OK:").split('|')
            val slots = listOf("track_id", "gpx", "thumb", "map", "distance_m", "duration_s")
            // The positional fallback is used ONLY when the reply has exactly the fields this list
            // names. In URI mode with no pictures asked for, three of the six come back empty —
            // `OK:<id>||||<distance>|<duration>` — and any reply whose count disagrees with the
            // slots means the shape has moved. Reading it anyway would not fail; it would put the
            // duration where the distance goes and show two plausible wrong numbers beside the
            // band's own figures, on the card that exists precisely to catch a disagreement.
            // Named extras carry all of this and are read first regardless.
            val positional = packed.takeIf { it.size == slots.size }
            fun field(name: String): String? =
                reply.extras[name]?.takeIf { it.isNotBlank() }
                    ?: positional?.getOrNull(slots.indexOf(name))?.trim()?.takeIf { it.isNotBlank() }

            HuaweiWorkoutStore.recordMap(
                dao = dao,
                workout = walk,
                trackId = field("track_id"),
                // 地図's own arithmetic over the same route, kept beside the band's and never merged
                // with it. Two independent measurements of one route is exactly the instrument that
                // catches a decoder reading a format slightly wrongly — which has happened here
                // once already, and was only caught because a number looked wrong.
                chizu = HuaweiWorkoutStore.ChizuReading(
                    distanceMetres = field("distance_m")?.toDoubleOrNull(),
                    durationSeconds = field("duration_s")?.toLongOrNull(),
                    movingSeconds = field("moving_time_s")?.toLongOrNull(),
                    activeSeconds = field("active_time_s")?.toLongOrNull(),
                    climbMetres = field("ascent_m")?.toDoubleOrNull(),
                    descentMetres = field("descent_m")?.toDoubleOrNull(),
                    // Empty in URI mode when no picture is asked for — there is no render to derive
                    // it from — and empty is the honest answer rather than a stale one.
                    detail = field("map_detail"),
                ),
                cutoutKey = null,
            )
            return Outcome(true, "sent to 地図")
        } finally {
            // Held until the reply lands. 地図 waits up to three minutes for its own initialization
            // before it opens the stream, so a grant revoked on a short timer is the one thing that
            // would make this fail intermittently and look like a 地図 bug.
            Handover.release(context, handover)
        }
    }


    /** 地図 5.4.x+: render a BASE map for a tile block, with no track and no library entry. */
    const val ACTION_EXPORT_BASEMAP = "shiroikuma.chizu.action.EXPORT_BASEMAP"

    /**
     * Fetch one shared map cutout.
     *
     * ## Why the request is in TILES
     *
     * Asking for a bounding box would make 地図 choose a framing, and a framing we did not choose is
     * one we cannot project onto: every walk drawn over it would need its transform stored and kept
     * in step with the pixels. A Web Mercator tile block has no such ambiguity — `z/x/y` and a size
     * fully determine the geography, both sides compute it from the same published definition, and
     * the file name carries it. Nothing has to be remembered.
     *
     * ## Why this is worth a new action
     *
     * The existing IMPORT_TRACK draws one route onto its own view and files it in 地図's library.
     * That is right for "show me this walk in 地図" and wrong for what walks actually are here:
     * dozens of routes over the same few streets. One base image per neighbourhood serves all of
     * them, costs 地図 nothing to keep, and leaves its library to the tracks 白い熊 actually put
     * there on purpose.
     */
    suspend fun basemap(
        context: Context,
        cutout: MapCutouts.Cutout,
        dao: com.opentasker.core.storage.HuaweiWorkoutDao,
        lang: BandLanguage = BandLanguage.EN,
    ): Outcome {
        val token = HuaweiSettings.chizuToken(context)
        if (token.isNullOrBlank()) return Outcome(false, "no 地図 token — set %Huawei_ChizuToken")

        // 地図 renders straight into the stream, so what is at this URI is whole only once the reply
        // says OK. That is why it is a scratch file of ours and not the row itself: the bytes reach
        // the database in one move, after the answer, or not at all. A half-written PNG here would
        // not merely blank the area — it would be exported and restored months later.
        val handover = Handover.stage(context, "cutout-${cutout.id}.png", ByteArray(0))
            ?: return Outcome(false, "could not stage the map for 地図")
        try {
            val uri = Handover.uriFor(context, handover)
            Handover.grant(context, uri, write = true)
            val intent = request(ACTION_EXPORT_BASEMAP, token).apply {
                putExtra("zoom", cutout.zoom.toString())
                putExtra("tile_x", cutout.tileX.toString())
                putExtra("tile_y", cutout.tileY.toString())
                putExtra("tiles_w", cutout.tilesW.toString())
                putExtra("tiles_h", cutout.tilesH.toString())
                putExtra("tile_px", Mercator.TILE_PX.toString())
                putExtra("out_uri", uri.toString())
                addFlags(
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                putExtra("night", "day")
            }
            val reply = roundTrip(context, intent)
                ?: return Outcome(false, "地図 did not answer")
            if (!reply.result.startsWith("OK:")) return Outcome(false, refusal(reply.result, lang))

            // `map_detail` is the ONLY thing that says whether there is a map in the picture.
            //
            // Every failure inside 地図's renderer — its render timeout, an interrupted draw, no
            // map data installed for the area — falls back to painting the block plain black and
            // still replies `OK:`. That fallback is right for a walk picture, where a route on
            // black still shows the route; for a bare cutout it is a megabyte of nothing that
            // looks exactly like a success (地図 chat, 2026-09-04, correcting its own earlier
            // "map_detail is ignorable" — which was true only for a track import drawing no
            // picture).
            //
            // Caching one would be worse here than anywhere else: a cutout is a row in an export
            // category 白い熊 can restore from months later, so a black neighbourhood would be
            // backed up and come back. Same hazard as a half-written file, arriving through a
            // reply that says OK.
            val packed = reply.result.removePrefix("OK:").split('|')
            val detail = (reply.extras["map_detail"]?.takeIf { it.isNotBlank() }
                ?: packed.getOrNull(3)?.trim())?.lowercase()
            if (detail != "map") {
                return Outcome(
                    false,
                    when (detail) {
                        // The mini world basemap. At the zoom a walk is drawn at, that is an empty
                        // picture with a coastline somewhere off the edge of it.
                        "basemap" -> HuaweiText.chizuNoDetail[lang]
                        "none", null, "" -> HuaweiText.chizuNothingDrawn[lang]
                        else -> "地図: $detail"
                    },
                )
            }
            val png = runCatching { handover.readBytes() }.getOrNull()
            if (png == null || png.size < 1024) return Outcome(false, "地図 answered but wrote no map")
            HuaweiWorkoutStore.putCutout(
                dao = dao, key = cutout.id, zoom = cutout.zoom,
                tileX = cutout.tileX, tileY = cutout.tileY,
                tilesW = cutout.tilesW, tilesH = cutout.tilesH,
                tilePx = Mercator.TILE_PX, png = png,
            )
            return Outcome(true, "map cached for this area")
        } finally {
            Handover.release(context, handover)
        }
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
    suspend fun show(context: Context, walk: HuaweiWorkoutStore.Workout): Outcome {
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
