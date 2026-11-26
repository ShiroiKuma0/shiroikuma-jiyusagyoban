package com.sysadmindoc.ruledeck.core.actions

import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.engine.VariableStore
import com.sysadmindoc.ruledeck.core.model.VariableNamePolicy
import com.sysadmindoc.ruledeck.core.text.countOf

/**
 * Variable Clear. Args: `name`, `pattern`, `local_only`, `clear_all`. The name is read as written,
 * never filled in. A plain name is cleared for the running task the way Reset Return Variable
 * clears it. With `pattern`, every variable whose name matches goes (a Matches pattern, so
 * `%item*` or `%a/%b`), globals too unless `local_only`. No name clears every local, and every
 * global as well with `clear_all`. `local_only` only counts with a pattern and `clear_all` only
 * with no name. The array a step like Split Text keeps under a cleared name goes with it. A name
 * with a path in it (`%json.key`, `%json.list[0]`) removes that field or item from the JSON the
 * variable holds, and fails when the variable is unset or isn't JSON or the field or item isn't
 * there. An element picked as the step runs, `%arr(>)`, `%arr(<)` or `%arr(%idx)`, is cleared as
 * [pickedElement] picks it.
 */
class VarClearAction : DeclaredAction(ActionCatalog.require("var.clear")) {

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"].orEmpty().trim()
        // The element is picked before a path is looked for, the same order clearVariables keeps.
        if (!isPickedElement(name) && clearsAPath(args)) {
            ctx.variables.clearAtPath(name)?.let { why -> return ActionResult.Failure("$name: $why") }
            ctx.logger("var.clear -> removed $name")
            return ActionResult.Success
        }
        // The picked number isn't named, since a secret %idx could hold it.
        val cleared = ctx.variables.clearVariables(args) ?: return ActionResult.Failure(
            if (isPickedElement(name)) "'$name' doesn't pick a variable name" else "invalid variable name '$name'",
        )
        ctx.logger("var.clear -> ${countOf(cleared, "variable")}")
        return ActionResult.Success
    }
}

/**
 * An element Variable Clear picks as it runs: a base of three or more characters, then in
 * parentheses `>` or `<` and anything without parentheses, or a variable of three or more characters.
 */
private val PICKED_ELEMENT =
    Regex("""(%[A-Za-z][A-Za-z0-9_]+[A-Za-z0-9])\(([<>][^()]*|%[A-Za-z][A-Za-z0-9_]+[A-Za-z0-9])\)""")

/** A pick's base, like `%arr`, and what its parentheses hold: `>`, `<...` or a variable. */
private data class ElementPick(val base: String, val pick: String)

/**
 * The pick [name] makes, or null when it makes none. A name read as a path never picks: one with a
 * `.`, or with both `[` and `]`.
 */
private fun elementPick(name: String): ElementPick? {
    if ('.' in name || ('[' in name && ']' in name)) return null
    val match = PICKED_ELEMENT.matchEntire(name) ?: return null
    return ElementPick(base = match.groupValues[1], pick = match.groupValues[2])
}

/** Whether [name] is an element Variable Clear picks as it runs, like `%arr(>)` or `%arr(%idx)`. */
internal fun isPickedElement(name: String): Boolean = elementPick(name) != null

/**
 * The base and element number [name] picks as the step runs: `>` the lowest number set and `<` the
 * highest (0 when that's below 0), 0 when none is set, counting `%arr0` and reading `%arr07` as 7,
 * and `%idx` the whole number `%idx` holds, 0 when it holds none or isn't set. Only the first
 * character after the parenthesis counts, so `%arr(<last)` is `%arr(<)`. A number below 0 fails
 * the step. Null when [name] isn't a pick.
 */
internal fun VariableStore.pickedElement(name: String): Pair<String, Int>? {
    val (base, pick) = elementPick(name) ?: return null
    val numbers = elementsByNumber(base).map { it.first }
    val position = when (pick.first()) {
        '>' -> numbers.firstOrNull() ?: 0
        '<' -> numbers.lastOrNull()?.coerceAtLeast(0) ?: 0
        else -> get(pick)?.toIntOrNull() ?: 0
    }
    return base to position
}

/** Whether [args] name one field or item of a JSON variable, which is cleared that way when Pattern Matching is off. */
private fun clearsAPath(args: Map<String, String>): Boolean {
    val name = args["name"]?.trim().orEmpty()
    return args["pattern"]?.toBooleanStrictOrNull() != true && ('.' in name || '[' in name)
}

/** What [VarClearAction] clears, shared with the preview. Returns how many, or null for a name that isn't a variable. */
internal fun VariableStore.clearVariables(args: Map<String, String>): Int? {
    val name = args["name"]?.trim().orEmpty()
    // The element is picked first, Pattern Matching or not, then cleared the usual way.
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
