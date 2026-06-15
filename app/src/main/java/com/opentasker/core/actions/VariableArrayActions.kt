package com.opentasker.core.actions

import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor

// ---------------------------------------------------------------------------------------------
// Wave 1 — Tasker-equivalent Variable & Array actions plus Parse/Format DateTime.
// Pure logic: no permissions, no platform calls. Variable names are bare (no leading %).
// Args reach run() already expanded by TaskRunner; a variable's *current* value is read from the
// store via ctx.variables.get(name) / getArrayItems(name).
// ---------------------------------------------------------------------------------------------

internal fun truthy(s: String?): Boolean = s?.trim()?.lowercase() in setOf("true", "1", "yes", "on")

private fun formatNumber(d: Double): String =
    if (!d.isInfinite() && !d.isNaN() && d == floor(d)) d.toLong().toString() else d.toString()

/** Interpret common backslash escapes in a user-typed delimiter (\n, \t, \r). */
private fun unescape(s: String): String =
    s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")

/** `Variable Clear` (Tasker 549) — unset a variable (and any array of the same name). */
class ClearVariableAction : Action {
    override val id = "var.clear"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        ctx.variables.unset(name)
        ctx.logger("Cleared %$name")
        return ActionResult.Success
    }
}

/** `Variable Split` (Tasker 590) — split a variable's value into an array of the same name. */
class SplitVariableAction : Action {
    override val id = "var.split"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val splitter = unescape(args["splitter"].orEmpty())
        val value = ctx.variables.get(name).orEmpty()
        val parts = if (splitter.isEmpty()) value.map { it.toString() } else value.split(splitter)
        ctx.variables.setArray(name, parts)
        if (truthy(args["delete_base"])) ctx.variables.unset(name)
        ctx.logger("Split %$name into ${parts.size} part(s)")
        return ActionResult.Success
    }
}

/** `Variable Join` (Tasker 592) — join the array `name` into the scalar `name`. */
class JoinVariableAction : Action {
    override val id = "var.join"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val joiner = unescape(args["joiner"].orEmpty())
        val items = ctx.variables.getArrayItems(name) ?: emptyList()
        val joined = items.joinToString(joiner)
        ctx.variables.set(name, joined)
        ctx.logger("Joined ${items.size} item(s) into %$name")
        return ActionResult.Success
    }
}

/** `Variable Search Replace` (Tasker 598) — regex replace in a variable, optionally store matches. */
class SearchReplaceVariableAction : Action {
    override val id = "var.replace"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val search = args["search"].orEmpty()
        if (search.isEmpty()) return ActionResult.Failure("missing search pattern")
        val value = ctx.variables.get(name).orEmpty()
        val options = buildSet {
            if (truthy(args["ignore_case"])) add(RegexOption.IGNORE_CASE)
            if (truthy(args["multiline"])) add(RegexOption.MULTILINE)
        }
        val regex = try {
            Regex(search, options)
        } catch (e: Exception) {
            return ActionResult.Failure("bad regex: ${e.message}")
        }
        val storeIn = args["store_matches"]?.trim().orEmpty()
        if (storeIn.isNotEmpty()) {
            ctx.variables.setArray(storeIn, regex.findAll(value).map { it.value }.toList())
        }
        val replacement = args["replace"].orEmpty()
        val replaced = regex.replace(value, Regex.escapeReplacement(replacement))
        ctx.variables.set(name, replaced)
        ctx.logger("Search/replace on %$name")
        return ActionResult.Success
    }
}

/** `Variable Convert` (Tasker 596) — transform a variable's value with a named function. */
class ConvertVariableAction : Action {
    override val id = "var.convert"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val fn = args["function"]?.trim()?.lowercase()?.replace(" ", "")?.replace("_", "") ?: ""
        val v = ctx.variables.get(name).orEmpty()
        val result = when (fn) {
            "upper", "uppercase", "toupper" -> v.uppercase()
            "lower", "lowercase", "tolower" -> v.lowercase()
            "trim" -> v.trim()
            "length", "len" -> v.length.toString()
            "reverse" -> v.reversed()
            "capitalize", "title" -> v.replaceFirstChar { it.uppercase() }
            "urlencode" -> URLEncoder.encode(v, "UTF-8")
            "urldecode" -> URLDecoder.decode(v, "UTF-8")
            "base64encode", "base64", "base64enc" ->
                java.util.Base64.getEncoder().encodeToString(v.toByteArray())
            "base64decode", "base64dec" ->
                runCatching { String(java.util.Base64.getDecoder().decode(v)) }.getOrElse { return ActionResult.Failure("invalid base64") }
            "md5" -> hashHex("MD5", v)
            "sha1" -> hashHex("SHA-1", v)
            "sha256" -> hashHex("SHA-256", v)
            else -> return ActionResult.Failure("unknown function: ${args["function"]}")
        }
        ctx.variables.set(name, result)
        ctx.logger("Converted %$name ($fn)")
        return ActionResult.Success
    }

    private fun hashHex(algo: String, s: String): String =
        MessageDigest.getInstance(algo).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** `Variable Add` (Tasker 888) — add a number to a numeric variable, with optional wrap and round. */
class AddVariableAction : Action {
    override val id = "var.add"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val current = ctx.variables.get(name)?.toDoubleOrNull() ?: 0.0
        val amount = args["value"]?.toDoubleOrNull() ?: return ActionResult.Failure("value is not a number")
        var sum = current + amount
        args["wrap"]?.toDoubleOrNull()?.let { wrap -> if (wrap > 0) { sum %= wrap; if (sum < 0) sum += wrap } }
        if (truthy(args["round"])) sum = sum.let { Math.round(it).toDouble() }
        ctx.variables.set(name, formatNumber(sum))
        ctx.logger("%$name += $amount → ${formatNumber(sum)}")
        return ActionResult.Success
    }
}

/** `Parse/Format DateTime` (Tasker 394) — produce a formatted date/time string into a variable. */
class DateTimeAction : Action {
    override val id = "datetime"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val store = args["store"]?.trim().orEmpty()
        if (store.isEmpty()) return ActionResult.Failure("missing store variable")
        val source = args["source"]?.trim()?.lowercase()?.replace(" ", "") ?: "now"
        val input = args["input"].orEmpty().trim()
        val millis: Long = try {
            when (source) {
                "now", "" -> System.currentTimeMillis()
                "seconds", "epochsec", "sec", "unix" -> (input.toDouble() * 1000).toLong()
                "millis", "milliseconds", "epochms", "ms" -> input.toLong()
                "formatted", "format" -> {
                    val inFmt = args["inputformat"]?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd HH:mm:ss"
                    SimpleDateFormat(inFmt, Locale.US).parse(input)?.time
                        ?: return ActionResult.Failure("could not parse input date")
                }
                else -> return ActionResult.Failure("unknown source: ${args["source"]}")
            }
        } catch (e: Exception) {
            return ActionResult.Failure("date error: ${e.message}")
        }
        val outFmt = args["format"]?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd HH:mm:ss"
        val out = try {
            SimpleDateFormat(outFmt, Locale.US).format(Date(millis))
        } catch (e: Exception) {
            return ActionResult.Failure("bad output format: ${e.message}")
        }
        ctx.variables.set(store, out)
        ctx.logger("DateTime → %$store = $out")
        return ActionResult.Success
    }
}

/** `Array Set` (Tasker 354) — populate an array from a delimited string. */
class ArraySetAction : Action {
    override val id = "array.set"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val splitter = unescape(args["splitter"].takeUnless { it.isNullOrEmpty() } ?: ",")
        val values = args["values"].orEmpty()
        val parts = if (values.isEmpty()) emptyList() else values.split(splitter)
        ctx.variables.setArray(name, parts)
        ctx.logger("Array %$name set with ${parts.size} item(s)")
        return ActionResult.Success
    }
}

/** `Array Push` (Tasker 355) — insert a value into an array (1-based position, default end). */
class ArrayPushAction : Action {
    override val id = "array.push"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val list = (ctx.variables.getArrayItems(name) ?: emptyList()).toMutableList()
        val value = args["value"].orEmpty()
        val pos = args["position"]?.trim()?.toIntOrNull()
        val index = if (pos == null) list.size else (pos - 1).coerceIn(0, list.size)
        list.add(index, value)
        ctx.variables.setArray(name, list)
        ctx.logger("Pushed onto %$name (now ${list.size})")
        return ActionResult.Success
    }
}

/** `Array Pop` (Tasker 356) — remove an element (1-based position, default end) into a variable. */
class ArrayPopAction : Action {
    override val id = "array.pop"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val list = (ctx.variables.getArrayItems(name) ?: emptyList()).toMutableList()
        if (list.isEmpty()) return ActionResult.Success
        val pos = args["position"]?.trim()?.toIntOrNull()
        val index = if (pos == null) list.lastIndex else (pos - 1).coerceIn(0, list.lastIndex)
        val removed = list.removeAt(index)
        ctx.variables.setArray(name, list)
        args["store"]?.trim()?.takeIf { it.isNotEmpty() }?.let { ctx.variables.set(it, removed) }
        ctx.logger("Popped from %$name (now ${list.size})")
        return ActionResult.Success
    }
}

/** `Array Clear` (Tasker 357) — empty an array. */
class ArrayClearAction : Action {
    override val id = "array.clear"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        ctx.variables.setArray(name, emptyList())
        ctx.logger("Cleared array %$name")
        return ActionResult.Success
    }
}

/** `Array Process` (Tasker 369) — sort / reverse / shuffle / dedupe / squash an array. */
class ArrayProcessAction : Action {
    override val id = "array.process"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val name = args["name"]?.trim().orEmpty()
        if (name.isEmpty()) return ActionResult.Failure("missing name")
        val items = ctx.variables.getArrayItems(name) ?: emptyList()
        val type = args["type"]?.trim()?.lowercase()?.replace(" ", "")?.replace("-", "") ?: "sort"
        val processed = when {
            type.contains("numeric") -> items.sortedBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }
            type.contains("desc") || type == "sortza" || type == "zaz" -> items.sortedDescending()
            type.startsWith("sort") || type == "asc" || type == "az" -> items.sorted()
            type.contains("reverse") -> items.reversed()
            type.contains("shuffle") || type.contains("random") -> items.shuffled()
            type.contains("unique") || type.contains("dedup") || type.contains("distinct") -> items.distinct()
            type.contains("squash") || type.contains("empty") -> items.filter { it.isNotEmpty() }
            else -> return ActionResult.Failure("unknown type: ${args["type"]}")
        }
        ctx.variables.setArray(name, processed)
        ctx.logger("Processed array %$name ($type)")
        return ActionResult.Success
    }
}

/** `Arrays Merge` (Tasker 393) — concatenate several arrays into one. */
class ArrayMergeAction : Action {
    override val id = "array.merge"
    override val category = ActionCategory.VARIABLE
    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val into = args["into"]?.trim().orEmpty()
        if (into.isEmpty()) return ActionResult.Failure("missing result array name")
        val names = args["arrays"].orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val merged = names.flatMap { ctx.variables.getArrayItems(it) ?: emptyList() }
        ctx.variables.setArray(into, merged)
        ctx.logger("Merged ${names.size} array(s) → %$into (${merged.size})")
        return ActionResult.Success
    }
}
