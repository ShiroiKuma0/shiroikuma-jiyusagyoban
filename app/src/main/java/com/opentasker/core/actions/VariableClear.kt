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
 * An element picked as the step runs, `%arr(>)`, `%arr(<)` or `%arr(%idx)`, is cleared the way
 * Tasker picks it (see [pickedElement]).
 */
class VarClearAction : DeclaredAction(ActionCatalog.require("var.clear")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"].orEmpty().trim()
        // Tasker picks the element before it looks for a path, the same order clearVariables keeps.
        if (!isTaskerPickedElement(name) && clearsAPath(args)) {
            ctx.variables.clearAtPath(name)?.let { why -> return ActionResult.Failure("$name: $why") }
            ctx.logger("var.clear -> removed $name")
            return ActionResult.Success
        }
        // The picked number isn't named, since a secret %idx could hold it.
        val cleared = ctx.variables.clearVariables(args) ?: return ActionResult.Failure(
            if (isTaskerPickedElement(name)) "'$name' doesn't pick a variable name" else "invalid variable name '$name'",
        )
        ctx.logger("var.clear -> $cleared variable(s)")
        return ActionResult.Success
    }
}

/**
 * Tasker's element picked as Variable Clear runs: a base of three or more characters, then `>` or
 * `<` and anything without parentheses, or a variable of three or more characters (`dq.Q0`, `dq.c1`).
 */
private val TASKER_PICKED_ELEMENT =
    Regex("""(%[A-Za-z][A-Za-z0-9_]+[A-Za-z0-9])\(([<>][^()]*|%[A-Za-z][A-Za-z0-9_]+[A-Za-z0-9])\)""")

/**
 * Whether [name] is an element Variable Clear picks as it runs, like `%arr(>)` or `%arr(%idx)`.
 * Tasker never picks in a name it reads as a path (`fr.M`): one with a `.`, or with both `[` and `]`.
 */
internal fun isTaskerPickedElement(name: String): Boolean =
    TASKER_PICKED_ELEMENT.matches(name) && '.' !in name && !('[' in name && ']' in name)

/**
 * The base and element number [name] picks as the step runs, the way Tasker's Variable Clear picks
 * it (decompiled 6.7.8, `dq.i` and `dq.V`): `>` the lowest number set and `<` the highest, 0 when
 * none is, counting `%arr0` and reading `%arr07` as 7, and `%idx` the whole number `%idx` holds,
 * read like Tasker's `new Integer(text)`, 0 when it holds none or isn't set. Tasker fails the step
 * on a number below 0. Null when [name] isn't a pick.
 */
internal fun VariableStore.pickedElement(name: String): Pair<String, Int>? {
    if (!isTaskerPickedElement(name)) return null
    val (base, pick) = TASKER_PICKED_ELEMENT.matchEntire(name)!!.destructured
    val numbers = taskerElements(base).map { it.first }
    val position = when {
        pick.startsWith('>') -> if (numbers.isEmpty()) 0 else numbers.first()
        pick.startsWith('<') -> numbers.lastOrNull()?.coerceAtLeast(0) ?: 0
        else -> get(pick)?.toIntOrNull() ?: 0
    }
    return base to position
}

/** Whether [args] name one field or item of a JSON variable, which Tasker clears that way when Pattern Matching is off. */
private fun clearsAPath(args: Map<String, String>): Boolean {
    val name = args["name"]?.trim().orEmpty()
    return args["pattern"]?.toBooleanStrictOrNull() != true && ('.' in name || '[' in name)
}

/** What [VarClearAction] clears, shared with the preview. Returns how many, or null for a name that isn't a variable. */
internal fun VariableStore.clearVariables(args: Map<String, String>): Int? {
    val name = args["name"]?.trim().orEmpty()
    // Tasker picks the element first, Pattern Matching or not, then clears it the usual way.
    pickedElement(name)?.let { (base, position) ->
        return if (position < 0) null else clearVariables(args + ("name" to "$base$position"))
    }
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
