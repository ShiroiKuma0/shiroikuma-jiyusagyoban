package com.opentasker.core.actions

import com.opentasker.core.data.StructuredDataReader
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionRetrySafety
import com.opentasker.core.engine.ActionResult

/**
 * Parse a JSON / CSV / XML / HTML string into variables, fully on-device.
 *
 * Args:
 *   - "source": the data to parse (typically a `%var` holding an HTTP response or file contents)
 *   - "format": "json" (default), "csv", "xml", or "html"
 *   - "path": selector — JSON `items[0].name`, CSV column `c` or cell `r,c`, XML `root/item/name`, or HTML CSS selector
 *   - "var": output variable base name (default "data")
 *
 * Sets `%var` to the first extracted value, stores all values as the `var` array (`%var(#)` /
 * `%var(0)` / `%var()`), and sets `%var_count` to the number of values.
 */
class DataReadAction : Action {
    override val id = "data.read"
    override val category = ActionCategory.VARIABLE
    override val retrySafety = ActionRetrySafety.IDEMPOTENT

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val source = args["source"] ?: return ActionResult.Failure("missing source")
        val format = args["format"]?.trim()?.ifBlank { null } ?: "json"
        val path = args["path"].orEmpty()
        val varName = (args["var"] ?: args["variable"])?.trim()?.ifBlank { null } ?: "data"

        val result = StructuredDataReader.read(format, source, path)
            ?: return ActionResult.Failure("could not read $format data at path '$path'")

        ctx.variables.set(varName, result.values.firstOrNull() ?: "")
        ctx.variables.setArray(varName, result.values)
        ctx.variables.set("${varName}_count", result.values.size.toString())
        ctx.logger("data.read: $format -> \$$varName (${result.values.size} value(s))")
        return ActionResult.Success
    }
}
