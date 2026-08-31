package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * Parse a sister app's `LIST_CATEGORIES` payload — the 保存復元 backup contract's answer, one line per
 * exportable item:
 *
 * ```
 * id <TAB> label [<TAB> parent] [<TAB> on|off]
 * ```
 *
 * and store its columns as comma-joined lists ready for `dialog.pickmulti`:
 *
 * | variable | contents |
 * | --- | --- |
 * | `<store>_ids` | every id, in order |
 * | `<store>_labels` | the labels (commas inside a label become `・`, since a comma is the separator) |
 * | `<store>_parents` | the parent per line, `""` for a top-level item |
 * | `<store>_defaults` | just the ids whose fourth field is not `off` — **what the picker pre-ticks** |
 *
 * Done natively because the fourth field is positional and optional: a line may carry two, three or
 * four fields, which a chain of `var.replace` regexes cannot take apart without mangling the short
 * ones. The picker starts from `_defaults` every time it is opened, so an item the app marks `off`
 * (a cover cache, generated thumbnails — large, derived, re-creatable) starts unticked, and what
 * 白い熊 ticks in that dialog is the explicit choice that gets stored.
 *
 * Args: "text" (the payload with `OK:` already stripped), "store" (variable prefix, default "cat").
 */
class BackupCategoriesAction : Action {
    override val id = "backup.categories"
    override val category = ActionCategory.APP

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim()?.removePrefix("%")?.takeIf { it.isNotEmpty() } ?: "cat"
        val lines = args["text"].orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val ids = ArrayList<String>(lines.size)
        val labels = ArrayList<String>(lines.size)
        val parents = ArrayList<String>(lines.size)
        val defaults = ArrayList<String>(lines.size)

        for (line in lines) {
            val f = line.split("\t")
            val id = f.getOrNull(0)?.trim().orEmpty()
            if (id.isEmpty()) continue
            ids += id
            // A comma inside a label would split the picker's list, so it becomes ・ (as before).
            labels += (f.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: id).replace(",", "・")
            parents += f.getOrNull(2)?.trim().orEmpty()
            if (!f.getOrNull(3)?.trim().equals("off", ignoreCase = true)) defaults += id
        }

        ctx.variables.set("${store}_ids", ids.joinToString(","))
        ctx.variables.set("${store}_labels", labels.joinToString(","))
        ctx.variables.set("${store}_parents", parents.joinToString(","))
        ctx.variables.set("${store}_defaults", defaults.joinToString(","))
        ctx.logger("Categories: ${ids.size} (${defaults.size} on by default) → %${store}_*")
        return ActionResult.Success
    }
}
