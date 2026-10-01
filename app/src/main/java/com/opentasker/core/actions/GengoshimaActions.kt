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

/** Open 言語島's player: pick islands, a mode and an order, then listen on one giant screen. */
class GengoshimaListenAction : Action {
    override val id = "gengoshima.listen"
    override val category = ActionCategory.MEDIA

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        GengoshimaSettings.remember(ctx.app, GengoshimaSettings.from(ctx.variables))
        ctx.app.startActivity(com.opentasker.ui.gengoshima.GengoshimaPlayerActivity.intent(ctx.app))
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
class GengoshimaGenerateAction : Action {
    override val id = "gengoshima.generate"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        fun arg(name: String) = ctx.variables.expand(args[name].orEmpty()).trim()
        val settings = GengoshimaSettings.from(ctx.variables)
        GengoshimaSettings.remember(ctx.app, settings)
        val store = arg("store").removePrefix("%").ifEmpty { "gengoshima" }
        val wait = arg("wait").lowercase() !in setOf("0", "false", "no", "off")
        // The progress window, as the entry screen's button opens it (on by default).
        val window = arg("window").lowercase() !in setOf("0", "false", "no", "off")
        val started = GenerationRunner.start(ctx.app, settings)
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
