package com.opentasker.core.actions

import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import com.opentasker.core.engine.VariableStore
import com.opentasker.core.model.VariableNamePolicy

/**
 * Tasker's Variable Clear. Args: `name`, `pattern`, `local_only`, `clear_all`. The name is read as
 * written, never filled in. A plain name is cleared for the running task the way Reset Return
 * Variable clears it. With `pattern`, every variable whose name matches goes (Tasker's Matches, so
 * `%item*` or `%a/%b`), globals too unless `local_only`. No name clears every local, and every
 * global as well with `clear_all`. Both switches only count where Tasker reads them. The array a
 * step like Split Text keeps under a cleared name goes with it. A name with a path in it
 * (`%json.key`, `%json.list[0]`) removes that field or item from the JSON the variable holds, and
 * fails when the variable is unset or isn't JSON or the field or item isn't there, as in Tasker.
 */
class VarClearAction : DeclaredAction(ActionCatalog.require("var.clear")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        if (clearsAPath(args)) {
            val path = args["name"].orEmpty().trim()
            ctx.variables.clearAtPath(path)?.let { why -> return ActionResult.Failure("$path: $why") }
            ctx.logger("var.clear -> removed $path")
            return ActionResult.Success
        }
        val cleared = ctx.variables.clearVariables(args)
            ?: return ActionResult.Failure("invalid variable name '${args["name"].orEmpty().trim()}'")
        ctx.logger("var.clear -> $cleared variable(s)")
        return ActionResult.Success
    }
}

/** Whether [args] name one field or item of a JSON variable, which Tasker clears that way when Pattern Matching is off. */
private fun clearsAPath(args: Map<String, String>): Boolean {
    val name = args["name"]?.trim().orEmpty()
    return args["pattern"]?.toBooleanStrictOrNull() != true && ('.' in name || '[' in name)
}

/** What [VarClearAction] clears, shared with the preview. Returns how many, or null for a name that isn't a variable. */
internal fun VariableStore.clearVariables(args: Map<String, String>): Int? {
    val name = args["name"]?.trim().orEmpty()
    fun flag(key: String) = args[key]?.toBooleanStrictOrNull() == true
    return when {
        name.isEmpty() -> clearMatching(pattern = null, includeGlobals = flag("clear_all"))
        flag("pattern") -> clearMatching(name, includeGlobals = !flag("local_only"))
        clearsAPath(args) -> if (clearAtPath(name) == null) 1 else 0
        else -> {
            val variable = VariableNamePolicy.normalize(name) ?: return null
            val wasSet = get(variable) != null
            clearForTask(variable)
            val hadArray = removeArray(variable)
            if (wasSet || hadArray) 1 else 0
        }
    }
}
