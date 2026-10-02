package com.opentasker.core.onse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The caller's half of 白い熊 音声's render contract (`shiroikuma-onse`,
 * `docs/sister-app-contract-onse-render.md`): hand it Japanese sentences and the absolute paths to
 * write them at, and collect one reply per sentence and then one terminal reply.
 *
 * ## Waking it first, and knowing whether it is there
 *
 * The contract's own request is a broadcast, and a broadcast to a process that is not running was
 * measured to be DROPPED on the Mate XT: EMUI's アプリ起動管理 refused the cold start and 音声 never
 * heard it (2026-09-30). Nothing replies to a request nobody received, so the caller would wait out
 * its whole timeout and learn nothing. So [render] first calls the `describe` method of 音声's data
 * door (`content://shiroikuma.onse.automation`, backup contract v2 §2a). A provider call brings the
 * process up, and its answer is the ping: an answer means 音声 is alive and about to receive the
 * broadcast; no answer means it is not installed, frozen, or blocked from starting, and [Result.error]
 * says so instead of a silent timeout.
 *
 * The render itself runs on a foreground service that 音声 starts from our broadcast, which Android
 * 12+ refuses from the background. The caller grants the temporary allowance before calling — see
 * `grantTempAllowance`.
 *
 * ## Waiting
 *
 * Synthesis is a few seconds per sentence and the first one also loads the voice model, so there is
 * no sensible total deadline. [stallMs] is the limit instead: the longest silence tolerated between
 * replies. On a stall the request is cancelled, so a still-running render does not go on writing
 * files after we have given up on it.
 */
object OnseRender {

    const val PACKAGE = "shiroikuma.onse"
    const val ACTION_RENDER = "$PACKAGE.action.RENDER"
    const val ACTION_CANCEL = "$PACKAGE.action.CANCEL_RENDER"
    private const val DOOR = "content://$PACKAGE.automation"
    const val REPLY_ACTION = "shiroikuma.jiyusagyoban.action.ONSE_REPLY"

    /** One sentence to render: [text] is what VOICEVOX reads, [outPath] is absolute. */
    data class Item(val id: String, val text: String, val outPath: String)

    /** Voice parameters, all optional; null leaves 音声's default. */
    data class Voice(
        val speaker: String? = null,
        val speed: String? = null,
        val pitch: String? = null,
        val intonation: String? = null,
        val volume: String? = null,
        val gapPre: String? = null,
        val gapPost: String? = null,
        val bitrateKbps: String? = null,
        /** `ja` (VOICEVOX, the default) or `en` (Kokoro). With `en`, [speed] is Kokoro's speed. */
        val lang: String? = null,
        /** With `lang = en`: the Kokoro voice, e.g. am_michael. */
        val enVoice: String? = null,
    )

    /** One `event=item` reply. */
    data class ItemReply(
        val id: String,
        val ok: Boolean,
        val outPath: String,
        val durationMs: Long,
        val error: String?,
        val index: Int,
        val total: Int,
    )

    /**
     * What happened. [result] is the terminal reply verbatim (`OK:<ok>|<failed>|<total>` or
     * `ERROR:…`) or our own `ERROR:` line when 音声 never got that far. The timings are the point of
     * the first spike: how long the wake took, and how long until the first file existed.
     */
    data class Result(
        val result: String,
        val items: List<ItemReply>,
        val wakeMs: Long,
        val firstItemMs: Long?,
        val elapsedMs: Long,
        val describe: String?,
    ) {
        val ok: Boolean get() = result.startsWith("OK:")
    }

    /** Ask the data door to describe itself. Starts 音声's process; null if it cannot be reached. */
    fun wake(context: Context): String? = runCatching {
        context.contentResolver.call(Uri.parse(DOOR), "describe", null, null)
            ?.let { b -> b.getString("result") ?: b.keySet().joinToString(",") { "$it=${b.get(it)}" } }
    }.getOrNull()

    /**
     * Render [items] and wait for the terminal reply. [onItem] is called on every item reply, in
     * arrival order, so a caller can mark a sentence ready the moment its file is in place.
     *
     * [batchDir] must be readable by 音声 (which has All-Files access) — the batch file is written
     * there and deleted again at the end.
     */
    suspend fun render(
        context: Context,
        items: List<Item>,
        batchDir: File,
        voice: Voice = Voice(),
        token: String? = null,
        stallMs: Long = 90_000L,
        /** 音声 answered the wake-up and the batch is on its way: the voice is loading now. */
        onSent: suspend () -> Unit = {},
        onItem: suspend (ItemReply) -> Unit = {},
    ): Result {
        val started = SystemClock.elapsedRealtime()
        val app = context.applicationContext
        val describe = withContext(Dispatchers.IO) { wake(app) }
        val wakeMs = SystemClock.elapsedRealtime() - started
        if (describe == null) {
            return Result(
                "ERROR:音声 did not answer — not installed, frozen, or its start was blocked " +
                    "(set 白い熊 音声 to 手動管理 with every switch on in アプリ起動管理)",
                emptyList(), wakeMs, null, wakeMs, null,
            )
        }

        val requestId = UUID.randomUUID().toString()
        val batch = File(batchDir, ".onse-batch-$requestId.json")
        withContext(Dispatchers.IO) {
            batchDir.mkdirs()
            batch.writeText(batchJson(items))
        }

        // Unbounded: replies can arrive faster than they are taken, and none may be dropped.
        val inbox = Channel<Intent>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getStringExtra("request_id") == requestId) inbox.trySend(intent)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(REPLY_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        val replies = ArrayList<ItemReply>()
        var firstItemMs: Long? = null
        try {
            app.sendBroadcast(
                Intent(ACTION_RENDER).apply {
                    setPackage(PACKAGE)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("request_id", requestId)
                    putExtra("reply_action", REPLY_ACTION)
                    putExtra("reply_package", app.packageName)
                    putExtra("batch_path", batch.absolutePath)
                    voice.speaker?.let { putExtra("speaker", it) }
                    voice.speed?.let { putExtra("speed", it) }
                    voice.pitch?.let { putExtra("pitch", it) }
                    voice.intonation?.let { putExtra("intonation", it) }
                    voice.volume?.let { putExtra("volume", it) }
                    voice.gapPre?.let { putExtra("gap_pre", it) }
                    voice.gapPost?.let { putExtra("gap_post", it) }
                    voice.bitrateKbps?.let { putExtra("bitrate_kbps", it) }
                    voice.lang?.let { putExtra("lang", it) }
                    voice.enVoice?.let { putExtra("en_voice", it) }
                    token?.takeIf { it.isNotEmpty() }?.let { putExtra("token", it) }
                },
            )
            onSent()
            while (true) {
                val reply = withTimeoutOrNull(stallMs) { inbox.receive() }
                if (reply == null) {
                    cancel(app, requestId, token)
                    val what = if (replies.isEmpty()) {
                        "音声 answered the wake-up but nothing came back for the render in " +
                            "${stallMs / 1000}s — its render service may have been refused a start"
                    } else {
                        "音声 went quiet for ${stallMs / 1000}s after ${replies.size} of " +
                            "${replies.first().total} — cancelled"
                    }
                    return Result(
                        "ERROR:$what", replies, wakeMs, firstItemMs,
                        SystemClock.elapsedRealtime() - started, describe,
                    )
                }
                when (reply.getStringExtra("event")) {
                    "item" -> {
                        if (firstItemMs == null) firstItemMs = SystemClock.elapsedRealtime() - started
                        val r = ItemReply(
                            id = reply.getStringExtra("id").orEmpty(),
                            ok = reply.getStringExtra("status") == "OK",
                            outPath = reply.getStringExtra("out_path").orEmpty(),
                            durationMs = reply.getStringExtra("duration_ms")?.toLongOrNull() ?: 0L,
                            error = reply.getStringExtra("error"),
                            index = reply.getStringExtra("index")?.toIntOrNull() ?: (replies.size + 1),
                            total = reply.getStringExtra("total")?.toIntOrNull() ?: items.size,
                        )
                        replies += r
                        onItem(r)
                    }
                    "done" -> return Result(
                        reply.getStringExtra("result") ?: "ERROR:done without a result",
                        replies, wakeMs, firstItemMs, SystemClock.elapsedRealtime() - started, describe,
                    )
                }
            }
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
            inbox.close()
            withContext(Dispatchers.IO) { runCatching { batch.delete() } }
        }
    }

    /** What 音声 says about itself (the contract's PING). */
    data class Pong(
        val ok: Boolean,
        val result: String,
        val version: String,
        val enInstalled: Boolean,
        val enVoices: List<String>,
        val enDefault: String,
        val batteryExempt: Boolean,
    )

    /**
     * Wake 音声 and ask it what it can do — above all whether the English model is installed, so a
     * run without it skips English cleanly instead of failing every sentence. Null when 音声 does not
     * answer within [timeoutMs] (not installed, frozen, or refused a start).
     */
    suspend fun ping(context: Context, timeoutMs: Long = 6_000L): Pong? {
        val app = context.applicationContext
        if (withContext(Dispatchers.IO) { wake(app) } == null) return null
        val requestId = UUID.randomUUID().toString()
        val inbox = Channel<Intent>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.getStringExtra("request_id") == requestId && intent.getStringExtra("event") == "pong") {
                    inbox.trySend(intent)
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(REPLY_ACTION), ContextCompat.RECEIVER_EXPORTED)
        try {
            app.sendBroadcast(
                Intent("$PACKAGE.action.PING").apply {
                    setPackage(PACKAGE)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("request_id", requestId)
                    putExtra("reply_action", REPLY_ACTION)
                    putExtra("reply_package", app.packageName)
                },
            )
            val pong = withTimeoutOrNull(timeoutMs) { inbox.receive() } ?: return null
            fun x(k: String) = pong.getStringExtra(k).orEmpty()
            return Pong(
                ok = x("result") == "OK",
                result = x("result"),
                version = x("version"),
                enInstalled = x("en_installed") == "true",
                enVoices = x("en_voices").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                enDefault = x("en_default"),
                batteryExempt = x("battery_exempt") == "true",
            )
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
            inbox.close()
        }
    }

    /** Stop a request at its next item boundary. Answers nothing; unknown ids are a no-op. */
    fun cancel(context: Context, requestId: String, token: String? = null) {
        context.sendBroadcast(
            Intent(ACTION_CANCEL).apply {
                setPackage(PACKAGE)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("request_id", requestId)
                token?.takeIf { it.isNotEmpty() }?.let { putExtra("token", it) }
            },
        )
    }

    /** `{"items":[{"id":…,"text":…,"out_path":…}, …]}` — the contract's batch file. */
    internal fun batchJson(items: List<Item>): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("text", item.text)
                                put("out_path", item.outPath)
                            },
                        )
                    }
                },
            )
        },
    )
}
