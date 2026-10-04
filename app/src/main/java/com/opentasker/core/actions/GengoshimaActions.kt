package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.gengoshima.GenerationRunner
import com.opentasker.core.gengoshima.GengoshimaSettings
import com.opentasker.ui.gengoshima.GengoshimaEntryActivity
import com.opentasker.ui.gengoshima.GengoshimaProgressActivity
import kotlinx.coroutines.flow.first

/**
 * Open 言語島's sentence entry.
 *
 * Also takes a copy of the `%Gengoshima_*` settings as this task sees them: the generation its
 * 「訳して音声を作る」 button starts runs after this task has ended, where the 日本語 project's
 * variables can no longer be read (see [GengoshimaSettings]).
 */
class GengoshimaEntryAction : Action {
    override val id = "gengoshima.entry"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        GengoshimaSettings.remember(ctx.app, GengoshimaSettings.from(ctx.variables))
        ctx.app.startActivity(GengoshimaEntryActivity.intent(ctx.app))
        return ActionResult.Success
    }
}

/**
 * Open 言語島's player: pick islands, a mode and an order, then listen on one giant screen.
 * `mode` (listen / shadow / recall, optional) is the mode the picker opens on.
 */
class GengoshimaListenAction : Action {
    override val id = "gengoshima.listen"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        GengoshimaSettings.remember(ctx.app, GengoshimaSettings.from(ctx.variables))
        val raw = ctx.variables.expand(args["mode"].orEmpty()).trim()
        val mode = com.opentasker.ui.gengoshima.ListenMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        if (raw.isNotEmpty() && mode == null) return ActionResult.Failure("言語島: mode must be listen, shadow or recall, not '$raw'")
        ctx.app.startActivity(com.opentasker.ui.gengoshima.GengoshimaPlayerActivity.intent(ctx.app, mode))
        return ActionResult.Success
    }
}

/**
 * Open 言語島's board: every 言語島 task as a tile with its own picture, in the order 白い熊 dragged
 * them into — capture review, the inbox, entry, the big play tile, practice, editing, sync, settings.
 */
class GengoshimaBoardAction : Action {
    override val id = "gengoshima.board"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        GengoshimaSettings.remember(ctx.app, GengoshimaSettings.from(ctx.variables))
        com.opentasker.ui.gengoshima.GengoshimaBoardActivity.open(ctx.app)
        return ActionResult.Success
    }
}

/** Open 言語島's statistics: the listening calendar, time of day, per-island progress, what is due. */
class GengoshimaStatsAction : Action {
    override val id = "gengoshima.stats"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        ctx.app.startActivity(com.opentasker.ui.gengoshima.GengoshimaStatsActivity.intent(ctx.app))
        return ActionResult.Success
    }
}

/** Open 言語島's island editor: reorder, move, delete, edit the English or Japanese, set readings. */
class GengoshimaIslandsAction : Action {
    override val id = "gengoshima.islands"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        GengoshimaSettings.remember(ctx.app, GengoshimaSettings.from(ctx.variables))
        ctx.app.startActivity(com.opentasker.ui.gengoshima.GengoshimaIslandsActivity.intent(ctx.app))
        return ActionResult.Success
    }
}

/**
 * Translate, voice and tidy whatever is waiting — the same run the entry screen's button starts, with
 * the same progress window (`window`, on by default).
 *
 * `wait` (default on) holds the task until the run is done and writes `<store>` (a one-line summary),
 * `<store>_translated`, `<store>_voiced`, `<store>_failed`; off, the run is started in the background
 * and the action returns at once. The run's own notification reports either way.
 */
open class GengoshimaGenerateAction(
    private val mode: GenerationRunner.Mode = GenerationRunner.Mode.GENERATE,
) : Action {
    override val id = when (mode) {
        GenerationRunner.Mode.GENERATE, GenerationRunner.Mode.EDITOR -> "gengoshima.generate"
        GenerationRunner.Mode.FULL_SYNC -> "gengoshima.anki_sync"
        GenerationRunner.Mode.ADOPT -> "gengoshima.anki_adopt"
    }
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        fun arg(name: String) = ctx.variables.expand(args[name].orEmpty()).trim()
        val settings = GengoshimaSettings.from(ctx.variables)
        GengoshimaSettings.remember(ctx.app, settings)
        val store = arg("store").removePrefix("%").ifEmpty { "gengoshima" }
        val wait = arg("wait").lowercase() !in setOf("0", "false", "no", "off")
        // The progress window, as the entry screen's button opens it (on by default).
        val window = arg("window").lowercase() !in setOf("0", "false", "no", "off")
        val started = GenerationRunner.start(ctx.app, settings, mode)
        if (window) ctx.app.startActivity(GengoshimaProgressActivity.intent(ctx.app))
        if (!wait) {
            ctx.logger(if (started) "言語島: generation started" else "言語島: a run is already going")
            return ActionResult.Success
        }
        // Wait for THE run — this one, or the one already going — to finish, and report it.
        val done = GenerationRunner.progress.first { it == null || !it.running }
        val summary = GenerationRunner.lastSummary ?: GenerationRunner.Summary(0, 0, 0, null, done?.result ?: "no result")
        ctx.variables.set(store, summary.line())
        ctx.variables.set("${store}_translated", summary.translated.toString())
        ctx.variables.set("${store}_voiced", summary.voiced.toString())
        ctx.variables.set("${store}_failed", summary.failed.toString())
        ctx.logger("言語島: ${summary.line()}")
        return if (summary.error == null && summary.failed == 0) ActionResult.Success
        else ActionResult.Failure("言語島: ${summary.line()}")
    }
}

/**
 * 「暗記と同期」: the usual run, ending in a FULL sync with 白い熊 暗記 — every sentence is sent, and any
 * 言語島 note 暗記 holds that no sentence here owns any more is deleted. The automatic sync after each
 * run only sends what changed; this is the one to run when the two may have drifted apart.
 */
class GengoshimaAnkiSyncAction : GengoshimaGenerateAction(GenerationRunner.Mode.FULL_SYNC)

/**
 * 「暗記から取り込む」: once, take the hand-made `Language Islands` deck in 白い熊 暗記 into 言語島 —
 * islands from its decks, sentences from its notes, both recordings made fresh — and hand the notes
 * back adopted, so their cards keep their review history. Safe to run again; it adds nothing twice.
 */
class GengoshimaAnkiAdoptAction : GengoshimaGenerateAction(GenerationRunner.Mode.ADOPT)

/**
 * 言語島 walk capture, driven by 物理鍵 with the screen off (docs/sister-app-contract-kxkb-gengoshima.md).
 *
 * `op=mode` (vol-down triple) toggles capture mode — entering it starts sentence 1 at once; leaving it
 * saves what is recording and offers every clip to 白い熊 kxkb. `op=sentence` (vol-down single while
 * `gengoshima_capture=true`) starts a sentence or saves the one recording. All feedback is vibration,
 * done by [com.opentasker.core.gengoshima.SentenceCapture]; `store` gets a one-line outcome.
 */
class GengoshimaCaptureAction : Action {
    override val id = "gengoshima.capture"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val op = ctx.variables.expand(args["op"].orEmpty()).trim().lowercase()
        val capture = com.opentasker.core.gengoshima.SentenceCapture
        val line = when (op) {
            "mode" -> capture.toggleMode(ctx.app)
            "sentence" -> capture.sentence(ctx.app)
            else -> return ActionResult.Failure("言語島 capture: op must be mode or sentence, not '$op'")
        }
        ctx.variables.expand(args["store"].orEmpty()).trim().removePrefix("%").takeIf { it.isNotEmpty() }
            ?.let { ctx.variables.set(it, line) }
        ctx.logger("言語島 capture: $line")
        return ActionResult.Success
    }
}

/** Open 言語島's 未分類 page: kxkb's reviewed sentences, each with Claude's proposed island. */
class GengoshimaInboxAction : Action {
    override val id = "gengoshima.inbox"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        GengoshimaSettings.remember(ctx.app, GengoshimaSettings.from(ctx.variables))
        ctx.app.startActivity(com.opentasker.ui.gengoshima.GengoshimaInboxActivity.intent(ctx.app))
        return ActionResult.Success
    }
}
