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
 * "confirm_task" (the task the button runs), "preselect" (`saved` = every app ticked, the default;
 * `none` = no app ticked, for picking one or two out of the roster), "lines", "keep_scale".
 */
class BackupPlanAction : Action {
    override val id = "backup.plan"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val packages = backupRoster(args)
        if (packages.isEmpty()) return ActionResult.Failure("no apps given")
        // "none" opens the same window with nothing ticked, so a one-app run is two taps instead of
        // deselecting the whole roster first. The ITEMS inside each app are unaffected: they still
        // carry that app's saved selection, ready the moment the app itself is ticked.
        val appsMarked = !args["preselect"].orEmpty().trim().equals("none", ignoreCase = true)
        val pm = ctx.app.packageManager

        val rows = packages.map { pkg ->
            ProgressRow(
                key = pkg,
                label = appLabel(pm, pkg),
                marked = appsMarked,
                children = savedItemRows(ctx, pkg),
            )
        }
        if (!ProgressPanelManager.canOverlay(ctx.app)) {
            return ActionResult.Failure("the panel needs \"Display over other apps\"")
        }
        // A destination chosen in a previous run must never leak into this one: the override is
        // cleared on every open, and only the pill puts it back.
        val dirVar = args["dir_var"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "BR_RunDir"
        ctx.variables.set(dirVar, "")
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
                dirPath = args["dir"]?.trim().orEmpty(),
                dirVar = dirVar,
                projectId = ctx.variables.projectId,
            ),
        )
        ProgressPanelManager.show(ctx.app)
        ctx.logger("Backup plan: ${rows.size} apps, ${if (appsMarked) "all" else "none"} ticked")
        return ActionResult.Success
    }
}

/** The roster as packages: "apps" split on "separator" (default a space) and on newlines. */
internal fun backupRoster(args: Map<String, String>): List<String> {
    val separator = args["separator"]?.takeUnless { it.isEmpty() } ?: " "
    return args["apps"].orEmpty().split(separator, "\n").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * One app's items as panel rows: a child per catalogue line (`id⇥label⇥parent⇥on|off`), sub-options
 * indented under their parent, ticked as `%BR_Items_<Suffix>` has them.
 *
 * An app with nothing saved falls back to the app's OWN defaults — the fourth field — so a roster
 * newcomer starts from what it recommends rather than from everything. An app whose catalogue has
 * never been captured falls back to its saved ids, so it stays selectable, just without labels.
 */
internal fun savedItemRows(ctx: ActionContext, pkg: String): List<ProgressRow> {
    val suffix = backupVarSuffix(pkg)
    val catalogue = ctx.variables.get("BR_Cat_$suffix").orEmpty()
    val chosen = ctx.variables.get("BR_Items_$suffix").orEmpty()
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val lines = catalogue.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) {
        return chosen.map { ProgressRow(key = it, label = it, marked = true) }
    }
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

/**
 * `Backup Items — Edit All` — the roster-wide item editor. The same window as the backup plan, but it
 * **saves** instead of running: every app unfolds to the items it last reported, ticked as
 * `%BR_Items_<Suffix>` has them, and the button writes the whole lot back — into the variables AND
 * into the settings task named by "settings_task", so the choice survives a restart.
 *
 * An app's own tick means "save this app's selection". Unticking one leaves its saved selection
 * exactly as it was, so a pass over one app never disturbs the other thirty-two.
 *
 * The catalogues are whatever `%BR_Cat_<Suffix>` holds, so the caller refreshes them first (the sweep
 * in 「保存項目一括選択」) — this action only reads, opens and saves.
 *
 * Args: "apps" (roster), "separator", "title", "confirm" (button label), "settings_task".
 */
class BackupEditItemsAction : Action {
    override val id = "backup.edititems"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val packages = backupRoster(args)
        if (packages.isEmpty()) return ActionResult.Failure("no apps given")
        val pm = ctx.app.packageManager

        val rows = packages.map { pkg ->
            ProgressRow(
                key = pkg,
                label = appLabel(pm, pkg),
                marked = true,                       // every app is saved unless it is unticked
                children = savedItemRows(ctx, pkg),
            )
        }
        if (!ProgressPanelManager.canOverlay(ctx.app)) {
            return ActionResult.Failure("the panel needs \"Display over other apps\"")
        }
        ProgressPanel.show(
            ProgressPanelState(
                title = args["title"]?.trim()?.ifBlank { null } ?: "保存項目",
                outer = rows,
                outerIndex = -1,
                outerLines = 10,
                innerLines = 8,
                icons = true,
                selecting = true,
                rowsSelectable = true,
                itemsMode = true,
                settingsTask = args["settings_task"]?.trim().orEmpty(),
                confirmLabel = args["confirm"]?.trim()?.ifBlank { null } ?: "保存",
                cancelLabel = "キャンセル",
                textScale = 1.5f,
                projectId = ctx.variables.projectId,
            ),
        )
        ProgressPanelManager.show(ctx.app)
        ctx.logger("Backup item editor: ${rows.size} apps")
        return ActionResult.Success
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
