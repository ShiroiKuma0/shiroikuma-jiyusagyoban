package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.progress.ProgressPanel
import com.opentasker.core.progress.ProgressPanelState
import com.opentasker.core.progress.ProgressRow
import com.opentasker.progress.ProgressPanelManager

/**
 * The variable suffix a package uses in 保存復元's settings — `shiroikuma.anki` → `Anki`, so
 * `%BR_Token_Anki` / `%BR_Items_Anki` / `%BR_Cat_Anki`. Mirrors the rule 「保存対象選択」 uses when it
 * adds those lines, so the two never drift apart.
 */
internal fun backupVarSuffix(pkg: String): String {
    val bare = pkg.removePrefix("shiroikuma.").map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    return bare.replaceFirstChar { it.uppercase() }
}

/**
 * `Backup Plan` — the pre-flight for a backup run. Opens the panel as a **plan** rather than starting
 * anything: every app in the roster is a ticked row, each unfolding to its own items (from the
 * catalogue 「保存項目選択」 stored, so they carry the app's own labels and indentation), ticked as that
 * app's saved selection has them.
 *
 * Everything is deselectable — a whole app, or single items within one — and there is a
 * select/deselect-all at the top and one inside each app. So a run can be narrowed on the spot,
 * including to items the saved selection leaves out, without disturbing what is saved.
 *
 * Pressing the action button writes the choice into per-run variables — `%BR_RunApps` (the ticked
 * packages) and `%BR_Run_<Suffix>` (that app's ticked item ids) — and runs "confirm_task". Those are
 * separate from `%BR_Items_<Suffix>`, so the saved defaults survive an ad-hoc run untouched.
 *
 * Args: "apps" (roster), "separator" (default a space), "title", "confirm" (button label),
 * "confirm_task" (the task the button runs), "lines", "keep_scale".
 */
class BackupPlanAction : Action {
    override val id = "backup.plan"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val separator = args["separator"]?.takeUnless { it.isEmpty() } ?: " "
        val packages = args["apps"].orEmpty().split(separator, "\n")
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (packages.isEmpty()) return ActionResult.Failure("no apps given")
        val pm = ctx.app.packageManager

        val rows = packages.map { pkg ->
            val suffix = backupVarSuffix(pkg)
            val catalogue = ctx.variables.get("BR_Cat_$suffix").orEmpty()
            val chosen = ctx.variables.get("BR_Items_$suffix").orEmpty()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            ProgressRow(
                key = pkg,
                label = appLabel(pm, pkg),
                marked = true,                       // every app is in the run until it is taken out
                children = itemsOf(catalogue, chosen),
            )
        }
        if (!ProgressPanelManager.canOverlay(ctx.app)) {
            return ActionResult.Failure("the panel needs \"Display over other apps\"")
        }
        ProgressPanel.show(
            ProgressPanelState(
                title = args["title"]?.trim()?.ifBlank { null } ?: "保存",
                outer = rows,
                outerIndex = -1,
                outerLines = 10,
                innerLines = 8,
                icons = true,
                selecting = true,
                rowsSelectable = true,
                confirmLabel = args["confirm"]?.trim()?.ifBlank { null } ?: "保存開始",
                confirmTask = args["confirm_task"]?.trim().orEmpty(),
                cancelLabel = "キャンセル",
                textScale = 1.5f,
                projectId = ctx.variables.projectId,
            ),
        )
        ProgressPanelManager.show(ctx.app)
        ctx.logger("Backup plan: ${rows.size} apps")
        return ActionResult.Success
    }

    /**
     * One child per catalogue line (`id⇥label⇥parent⇥on|off`), sub-options indented under their
     * parent. An app whose catalogue has not been captured yet falls back to its saved ids, so it is
     * still selectable — just without labels until 「保存項目選択」 is run for it once.
     */
    private fun itemsOf(catalogue: String, chosen: Set<String>): List<ProgressRow> {
        val lines = catalogue.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) {
            return chosen.map { ProgressRow(key = it, label = it, marked = true) }
        }
        // Nothing saved yet = take the app at its word: whatever it marks on by default.
        val fresh = chosen.isEmpty()
        return lines.mapNotNull { line ->
            val f = line.split("\t")
            val id = f.getOrNull(0)?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val defaultOn = !f.getOrNull(3)?.trim().equals("off", ignoreCase = true)
            ProgressRow(
                key = id,
                label = f.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: id,
                depth = if (f.getOrNull(2)?.trim().isNullOrEmpty()) 0 else 1,
                marked = if (fresh) defaultOn else id in chosen,
            )
        }
    }
}

/**
 * `Backup Items For Run` — what this app should actually export **now**: the plan's per-run choice
 * (`%BR_Run_<Suffix>`) when there is one, otherwise the saved selection (`%BR_Items_<Suffix>`), and an
 * empty string when neither exists, which the contract reads as "the app's own default set".
 *
 * Resolved natively because the variable name is derived from the package, and a task cannot read a
 * variable whose name it has just computed.
 *
 * Args: "package" (required), "store" (variable to receive the id list, default "items_eff").
 */
class BackupRunItemsAction : Action {
    override val id = "backup.runitems"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val pkg = args["package"]?.trim().orEmpty()
        if (pkg.isEmpty()) return ActionResult.Failure("missing package")
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "items_eff"
        val suffix = backupVarSuffix(pkg)
        val perRun = ctx.variables.get("BR_Run_$suffix")?.trim().orEmpty()
        val saved = ctx.variables.get("BR_Items_$suffix")?.trim().orEmpty()
        val effective = if (perRun.isNotEmpty()) perRun else saved
        ctx.variables.set(store, effective)
        ctx.logger("Items for $pkg: ${if (perRun.isNotEmpty()) "run" else "saved"} → %$store")
        return ActionResult.Success
    }
}
