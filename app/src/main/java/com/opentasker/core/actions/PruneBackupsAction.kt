package com.opentasker.core.actions

import android.content.pm.PackageManager
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.progress.ProgressPanel
import com.opentasker.core.progress.ProgressPanelState
import com.opentasker.core.progress.ProgressRow
import com.opentasker.core.progress.ProgressRowState
import com.opentasker.progress.ProgressPanelManager
import com.opentasker.progress.humanSize
import java.io.File

/**
 * `Prune Backups` — the housekeeping half of 保存復元. Scans the backup directory, groups every
 * archive by the app that wrote it, and opens the 保存 panel in **selection** mode with everything
 * except each app's newest file already ticked for deletion.
 *
 * The window is the one from a backup run, minus the item pane: one row per app — 「(9/10) · 1.2 GB」
 * and a chevron — which unfolds to that app's archives, newest first, each with its own date, size
 * and tick. Tapping a file toggles it; tapping the app row toggles all of its files; the header and
 * the delete button carry the live totals. Nothing is deleted until 「削除」 is pressed, and the same
 * window then reports what went.
 *
 * File names follow the family convention `<app-with-dashes>_<yyyy-MM-dd_HH-mm-ss>.zip`, so an app's
 * archives are found by turning its package into that prefix (`shiroikuma.memo` → `shiroikuma-memo_`).
 *
 * Args:
 *   - "dir": the backup directory (required)
 *   - "apps": the roster, split by "separator" (default a space) — only these apps are listed
 *   - "keep": how many newest archives per app to leave ticked-off (default 1)
 *   - "title": panel heading (default 「保存の整理」)
 */
class PruneBackupsAction : Action {
    override val id = "backup.prune"
    override val category = ActionCategory.FILE

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val dir = args["dir"]?.trim().orEmpty()
        if (dir.isEmpty()) return ActionResult.Failure("missing dir")
        val folder = File(dir)
        if (!folder.isDirectory) return ActionResult.Failure("not a directory: $dir")
        val separator = args["separator"]?.takeUnless { it.isEmpty() } ?: " "
        val packages = args["apps"].orEmpty().split(separator, "\n")
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (packages.isEmpty()) return ActionResult.Failure("no apps given")
        val keep = args["keep"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 1

        val files = folder.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }.orEmpty()
        val pm = ctx.app.packageManager

        val rows = packages.mapNotNull { pkg ->
            val prefix = pkg.replace('.', '-') + "_"
            // Newest first: the family stamps the name, so the name sorts chronologically — but fall
            // back to the file's own timestamp so an oddly-named archive still lands sensibly.
            val mine = files.filter { it.name.startsWith(prefix) }
                .sortedWith(compareByDescending<File> { it.name }.thenByDescending { it.lastModified() })
            if (mine.isEmpty()) return@mapNotNull null
            val children = mine.mapIndexed { index, file ->
                ProgressRow(
                    key = file.absolutePath,
                    // The stamp is what distinguishes them; the prefix is the row above.
                    label = file.name.removePrefix(prefix).removeSuffix(".zip"),
                    detail = humanSize(file.length()),
                    bytes = file.length(),
                    marked = index >= keep,          // everything but the newest
                    state = if (index < keep) ProgressRowState.DONE else ProgressRowState.PENDING,
                )
            }
            ProgressRow(
                key = pkg,
                label = appLabel(pm, pkg),
                children = children,
                bytes = children.sumOf { it.bytes },
                state = ProgressRowState.PENDING,
            )
        }

        if (rows.isEmpty()) return ActionResult.Failure("no backups found in $dir")
        if (!ProgressPanelManager.canOverlay(ctx.app)) {
            return ActionResult.Failure("the panel needs \"Display over other apps\"")
        }
        ProgressPanel.show(
            ProgressPanelState(
                title = args["title"]?.trim()?.ifBlank { null } ?: "保存の整理",
                outer = rows,
                outerIndex = -1,
                outerLines = args["lines"]?.trim()?.toIntOrNull()?.coerceIn(3, 30) ?: 5,
                innerLines = 5,
                // Read rather than glanced at: half again the size of a progress row.
                textScale = 1.5f,
                icons = true,
                selecting = true,
                confirmLabel = "削除",
                cancelLabel = "キャンセル",
                projectId = ctx.variables.projectId,
            ),
        )
        ProgressPanelManager.show(ctx.app)
        val total = rows.sumOf { it.children.size }
        ctx.logger("Prune: ${rows.size} apps, $total archives")
        return ActionResult.Success
    }
}
