package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.onse.OnseRender
import com.opentasker.core.onse.OnseWarning
import java.io.File

/**
 * Have 白い熊 音声 voice some Japanese sentences into OGG files — one per line of `texts`, written
 * into `dir` as `<prefix>001.ogg`, `<prefix>002.ogg`, ….
 *
 * The first use is the 言語島 spike: can this app wake 音声 from the background, hand it a batch
 * and get every reply back? The 言語島 generation runner calls [OnseRender] directly with its own
 * paths; this action is the same call made reachable from a task, and it reports the timings the
 * spike is about (`<store>_wake_ms`, `<store>_first_ms`, `<store>_elapsed_ms`).
 *
 * Args: `texts` (required, one sentence per line), `dir` (required, absolute), `prefix`, `speaker`,
 * `speed`, `pitch`, `intonation`, `gap_pre`, `gap_post`, `bitrate_kbps`, `token`, `stall` (seconds
 * of silence tolerated between replies, default 90), `store` (variable prefix, default `onse`).
 *
 * Writes `<store>_result` (the terminal reply, or our own `ERROR:` line), `<store>_ok` (`x` when
 * every sentence rendered), `<store>_files` and `<store>_durations` (comma-separated, in order),
 * `<store>_errors`, `<store>_describe` (what 音声's data door said on waking), and the three timings.
 */
class OnseRenderAction : Action {
    override val id = "onse.render"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        fun arg(name: String) = ctx.variables.expand(args[name].orEmpty()).trim()
        fun opt(name: String) = arg(name).ifEmpty { null }

        val store = arg("store").removePrefix("%").ifEmpty { "onse" }
        val texts = ctx.variables.expand(args["texts"].orEmpty())
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return ActionResult.Failure("texts is required — one sentence per line")
        val dirArg = arg("dir")
        if (!dirArg.startsWith("/")) return ActionResult.Failure("dir must be an absolute path")
        val dir = File(dirArg)
        val prefix = arg("prefix")
        val stallSec = arg("stall").toLongOrNull()?.coerceIn(10L, 1_200L) ?: 90L

        val items = texts.mapIndexed { i, text ->
            OnseRender.Item(
                id = (i + 1).toString(),
                text = text,
                outPath = File(dir, "$prefix%03d.ogg".format(i + 1)).absolutePath,
            )
        }
        // Long enough for the whole batch at the measured few seconds a sentence, with the model
        // load on top. It expires on its own, so nothing lasting is left behind.
        grantTempAllowance(OnseRender.PACKAGE, (60L + texts.size * 30L) * 1000L)?.let(ctx.logger)

        val result = OnseRender.render(
            context = ctx.app,
            items = items,
            batchDir = dir,
            voice = OnseRender.Voice(
                speaker = opt("speaker"),
                speed = opt("speed"),
                pitch = opt("pitch"),
                intonation = opt("intonation"),
                gapPre = opt("gap_pre"),
                gapPost = opt("gap_post"),
                bitrateKbps = opt("bitrate_kbps"),
            ),
            token = opt("token"),
            stallMs = stallSec * 1000L,
        ) { reply ->
            ctx.logger(
                "音声 ${reply.index}/${reply.total}: " +
                    if (reply.ok) "${File(reply.outPath).name} (${reply.durationMs} ms)"
                    else "FAILED — ${reply.error}",
            )
        }

        ctx.variables.set("${store}_result", result.result)
        ctx.variables.set("${store}_ok", if (result.ok && result.items.all { it.ok }) "x" else "")
        ctx.variables.set("${store}_files", result.items.filter { it.ok }.joinToString(",") { it.outPath })
        ctx.variables.set("${store}_durations", result.items.joinToString(",") { it.durationMs.toString() })
        ctx.variables.set(
            "${store}_errors",
            result.items.filterNot { it.ok }.joinToString("; ") { "${it.id}: ${it.error}" },
        )
        ctx.variables.set("${store}_describe", result.describe.orEmpty())
        ctx.variables.set("${store}_wake_ms", result.wakeMs.toString())
        ctx.variables.set("${store}_first_ms", result.firstItemMs?.toString().orEmpty())
        ctx.variables.set("${store}_elapsed_ms", result.elapsedMs.toString())
        ctx.logger(
            "音声 render: ${result.result} — woke in ${result.wakeMs} ms, first file at " +
                "${result.firstItemMs ?: "—"} ms, all in ${result.elapsedMs} ms",
        )
        // Not a run-log line nobody reads: when 音声 could not be started at all, say what fixes it
        // and put the button that opens 音声 on the notification (白い熊, 2026-09-30).
        if (!result.ok && OnseWarning.needsAttention(result.result)) OnseWarning.post(ctx.app, result.result)
        return if (result.ok) ActionResult.Success else ActionResult.Failure(result.result)
    }
}
