package com.opentasker.core.engine.variables

import com.opentasker.core.data.JsonNesting
import com.opentasker.core.engine.VariableStore
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import com.google.re2j.Pattern as Re2Pattern
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Enhanced variable expression evaluator supporting:
 * - Math operators: %var(+5), %var(*2), %var(//) floor, %var(/round)
 * - String operations: %var(upper), %var(lower), %var(substring:0:5), %var(trim), %var(split:,), %var(join:-)
 * - Linear-time regex: %var(regex:pattern:group) extract, %var(replace:pattern:replacement)
 * - Conditionals: %var = (expr) ? true_val : false_val
 * - Arrays: %list(#) count, %list(1) indexed, %list() join
 * - JSON: %json.path.to.field parse nested JSON
 */
class VariableExpander {

    /**
     * Expand an expression with operators. Examples:
     * - "5" -> "5"
     * - "%VAR" -> value of VAR
     * - "%VAR(+10)" -> parse VAR as number, add 10
     * - "%VAR(upper)" -> uppercase VAR
     * - "%VAR(regex:(\d+):1)" -> extract first digit group from VAR
     * - "%VAR(split:,)" -> split VAR by comma, return array
     */
    /**
     * [conditionals] false leaves a leading `(cond) ? a : b` as text, for a value that isn't a whole
     * expression. [unset] says how a `%name` with no value reads.
     */
    fun expand(
        expr: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        quoteValue: (String) -> String = { it },
        conditionals: Boolean = true,
        // FORK: an unset variable reads as nothing by default (see VariableStore.expandTracked).
        unset: Unset = Unset.BLANK,
    ): String {
        return try {
            expandInternal(expr, variableStore, arrayStore, quoteValue, conditionals, unset)
        } catch (e: Exception) {
            // If evaluation fails, return original expression
            expr
        }
    }

    /** How a `%name` with no value reads. */
    enum class Unset {
        /** Left as written: a user variable that has never had a value assigned isn't replaced. */
        AS_WRITTEN,

        /** Filled in as nothing, for Is Set and an array index. */
        BLANK,

        /**
         * Read as 0 when unset or empty, which is how a plain variable fills in for maths. An
         * operator or array form, like `%x(+1)` or `%arr(#)`, reads as it always does.
         */
        ZERO,
    }

    private fun expandInternal(
        expr: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        quoteValue: (String) -> String = { it },
        conditionals: Boolean = true,
        // FORK: an unset variable reads as nothing by default (see VariableStore.expandTracked).
        unset: Unset = Unset.BLANK,
    ): String {
        // Handle conditionals: (cond) ? true_val : false_val
        val ternary = if (conditionals) parseTernary(expr) else null
        if (ternary != null) {
            val result = evaluateConditionInternal(ternary.condition) { operand, mode ->
                expandInternal(operand, variableStore, arrayStore, unset = mode)
            }
            return expandText(if (result) ternary.trueValue else ternary.falseValue, variableStore, arrayStore, unset, quoteValue)
        }

        return expandText(expr, variableStore, arrayStore, unset, quoteValue)
    }

    private data class Ternary(val condition: String, val trueValue: String, val falseValue: String)

    /**
     * Parse a leading `(condition) ? trueValue : falseValue` expression. The condition is matched by
     * balanced parentheses so operator expressions that contain parens (e.g. `(%A(+1) > 5)`) are
     * handled, unlike the previous `[^)]+` regex which stopped at the first `)`.
     */
    private fun parseTernary(expr: String): Ternary? {
        if (expr.isEmpty() || expr[0] != '(') return null
        var depth = 0
        var conditionEnd = -1
        for (i in expr.indices) {
            when (expr[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) { conditionEnd = i; break }
            }
        }
        if (conditionEnd == -1) return null
        var cursor = conditionEnd + 1
        while (cursor < expr.length && expr[cursor].isWhitespace()) cursor++
        if (cursor >= expr.length || expr[cursor] != '?') return null
        val rest = expr.substring(cursor + 1)
        val colon = rest.indexOf(':')
        if (colon == -1) return null
        return Ternary(
            condition = expr.substring(1, conditionEnd),
            trueValue = rest.substring(0, colon).trim(),
            falseValue = rest.substring(colon + 1).trim(),
        )
    }

    /** [unset] defaults to [Unset.BLANK] for an array index, where an unset `%i` names no element. */
    private fun expandText(
        expr: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        unset: Unset = Unset.BLANK,
        quoteValue: (String) -> String,
    ): String {
        if ('%' !in expr) return expr

        val out = StringBuilder(expr.length)
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            // `%%` is a literal percent.
            //
            // Nothing consumed it before, so a text ending "電池 %HUAWEI_BatteryPct%%" expanded the
            // variable and then printed BOTH signs — 「電池 19%%」 on the one notification meant to
            // be read at a glance (白い熊, 2026-09-08). Writing `%%` for a literal `%` beside a
            // variable is the natural thing to reach for, and it was silently wrong.
            //
            // Safe to add now rather than later: `%%` occurs exactly three times in the whole
            // workspace, all three in that one pair of tasks and all three meaning this. The
            // alternative — editing the tasks — would re-import them, and a task re-imported alone
            // is re-IDed, which dangles whatever profile runs it.
            if (c == '%' && i + 1 < expr.length && expr[i + 1] == '%') {
                out.append('%')
                i += 2
                continue
            }
            if (expr.startsWith("\\%", i) && startsVariable(expr, i + 2)) {
                // \%%name is a percent sign and the value, and an unset name reads as blank there.
                val token = readVariableToken(expr, i + 2, variableStore, arrayStore)
                out.append('%').append(if (token.unset) "" else quoteValue(token.value))
                i = token.nextIndex
            } else if (c == '\\') {
                i = appendEscape(expr, i, out)
            } else if (startsVariable(expr, i)) {
                val token = readVariableToken(expr, i, variableStore, arrayStore)
                out.append(
                    when {
                        unset == Unset.ZERO && token.value.isEmpty() && !token.operator -> "0"
                        // The text kept is the author's, not a value.
                        unset == Unset.AS_WRITTEN && token.unset -> expr.substring(i, token.nextIndex)
                        else -> quoteValue(token.value)
                    },
                )
                i = token.nextIndex
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString()
    }

    private fun startsVariable(expr: String, index: Int): Boolean =
        index + 1 < expr.length && expr[index] == '%' && expr[index + 1].isLetter()

    /**
     * A backslash before a variable: `\%name` is the reference as written without the backslash,
     * and `\\%name` is one backslash and then the value, so a longer run loses one backslash.
     * [expandText] reads `\%%name`, which needs the value. A backslash before anything else stays.
     * Returns the index to carry on from.
     */
    private fun appendEscape(expr: String, start: Int, out: StringBuilder): Int {
        var run = start
        while (run < expr.length && expr[run] == '\\') run++
        val backslashes = run - start
        return when {
            backslashes == 1 && startsVariable(expr, run) -> {
                // Not read, because reading an operator form like split: stores an array.
                val end = variableTokenEnd(expr, run)
                out.append(expr, run, end)
                end
            }
            backslashes > 1 && startsVariable(expr, run) -> {
                repeat(backslashes - 1) { out.append('\\') }
                run
            }
            else -> {
                out.append(expr, start, run)
                run
            }
        }
    }

    /** Where the reference at [start] ends, as [readVariableToken] reads it, without reading its value. */
    private fun variableTokenEnd(expr: String, start: Int): Int {
        var cursor = start + 1
        while (cursor < expr.length && isVariableNameChar(expr[cursor])) cursor++
        if (expr.substring(start + 1, cursor) == "json" && cursor < expr.length && expr[cursor] == '.') {
            val path = readJsonPath(expr, cursor + 1)
            if (path.value.isNotEmpty()) return path.nextIndex
        }
        if (cursor < expr.length && expr[cursor] == '(') {
            val close = findOperatorClose(expr, cursor)
            if (close != -1) return close + 1
        }
        return cursor
    }

    private fun readVariableToken(
        expr: String,
        start: Int,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
    ): TokenExpansion {
        var cursor = start + 1
        while (cursor < expr.length && isVariableNameChar(expr[cursor])) cursor++
        val name = expr.substring(start + 1, cursor)

        if (name == "json" && cursor < expr.length && expr[cursor] == '.') {
            val path = readJsonPath(expr, cursor + 1)
            if (path.value.isNotEmpty()) {
                val json = variableStore.get("json") ?: return TokenExpansion("", path.nextIndex, unset = true)
                return TokenExpansion(evaluateJsonPath(json, path.value), path.nextIndex)
            }
        }

        if (cursor < expr.length && expr[cursor] == '(') {
            val close = findOperatorClose(expr, cursor)
            if (close != -1) {
                val op = expr.substring(cursor + 1, close)
                return TokenExpansion(
                    evaluateVarOp(name, op, variableStore, arrayStore),
                    close + 1,
                    operator = true,
                )
            }
        }

        variableStore.get(name)?.let { return TokenExpansion(it, cursor) }

        // A hyphen is a legal name character, so `%count-1` scans greedily as the name "count-1".
        // When no such variable exists, fall back to the longest defined prefix so ordinary text
        // keeps expanding `%count` and leaving `-1` behind, the way it did before hyphens were
        // accepted in names. Tasks written for engines that never allowed a hyphen rely on this.
        var separator = name.lastIndexOf('-')
        while (separator > 0) {
            val prefix = name.substring(0, separator)
            variableStore.get(prefix)?.let { value ->
                return TokenExpansion(value, start + 1 + separator)
            }
            separator = name.lastIndexOf('-', separator - 1)
        }

        return TokenExpansion("", cursor, unset = true)
    }

    private fun findOperatorClose(expr: String, openIndex: Int): Int {
        var depth = 0
        for (index in openIndex until expr.length) {
            when (expr[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    /** `+2`, `-1.5`, `*3`, `/4`, `//` and `/round`: the fork's arithmetic on a plain numeric value. */
    private val FORK_ARITHMETIC_FORM = Regex("""//|/round|[+\-*/]-?\d+(\.\d+)?""")

    private fun evaluateVarOp(name: String, op: String, store: VariableStore, arrays: ArrayStore): String {
        val baseValue = store.get(name)

        // FORK: arithmetic on a plain number comes first. Upstream's Tasker join form (a415d8ce) reads
        // `%k(+1)` as "every element joined by 1" whenever an array of the same name exists — a kept
        // list or numbered elements alike; here `%k(+1)` on a numeric %k has always been k + 1, and
        // 白い熊's tasks count that way.
        val plainArithmetic = baseValue?.toDoubleOrNull() != null && FORK_ARITHMETIC_FORM.matches(op)
        if (!plainArithmetic) {
            if (arrays.contains(name)) storedArrayValue(name, op, baseValue, store, arrays)?.let { return it }
            arrayFormValue(name, op, baseValue, store, arrays)?.let { return it }
        }

        if (baseValue == null) return ""

        // Math operations
        val numValue = baseValue.toDoubleOrNull()
        if (numValue != null) {
            val result = when {
                op == "//" -> floor(numValue)
                // roundToLong throws on NaN, so a value that isn't finite comes out the way the
                // other operations leave it.
                op == "/round" -> if (numValue.isFinite()) numValue.roundToLong().toDouble() else numValue
                op.startsWith("+") -> numValue + (op.substring(1).toDoubleOrNull() ?: 0.0)
                op.startsWith("-") -> numValue - (op.substring(1).toDoubleOrNull() ?: 0.0)
                op.startsWith("*") -> numValue * (op.substring(1).toDoubleOrNull() ?: 1.0)
                op.startsWith("/") -> {
                    val divisor = op.substring(1).toDoubleOrNull() ?: 1.0
                    if (divisor != 0.0) numValue / divisor else 0.0
                }
                else -> numValue
            }
            return when {
                result == floor(result) -> result.toLong().toString()
                else -> result.toString()
            }
        }

        // String operations
        return when {
            op == "upper" -> baseValue.uppercase()
            op == "lower" -> baseValue.lowercase()
            op == "trim" -> baseValue.trim()
            op.startsWith("substring:") -> {
                val parts = op.substring(10).split(":")
                val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val end = parts.getOrNull(1)?.toIntOrNull() ?: baseValue.length
                val boundedStart = start.coerceIn(0, baseValue.length)
                val boundedEnd = end.coerceIn(0, baseValue.length)
                if (boundedEnd < boundedStart) "" else baseValue.substring(boundedStart, boundedEnd)
            }
            op.startsWith("split:") -> {
                val delimiter = op.substring(6)
                val parts = baseValue.split(delimiter)
                arrays.put(name + "_split", parts)
                "${name}_split"
            }
            op.startsWith("join:") -> {
                val delimiter = op.substring(5)
                arrays.join(name, delimiter)
            }
            op.startsWith("regex:") -> {
                val (pattern, groupIdx) = parseRegexOp(op.substring(6))
                if (pattern.length > MAX_REGEX_LENGTH || baseValue.length > MAX_REGEX_INPUT_LENGTH) return ""
                extractRegexGroup(pattern, baseValue, groupIdx)
            }
            op.startsWith("replace:") -> {
                val parts = op.substring(8).split(":", limit = 2)
                val pattern = parts.getOrNull(0) ?: ""
                val replacement = parts.getOrNull(1) ?: ""
                if (pattern.length > MAX_REGEX_LENGTH || baseValue.length > MAX_REGEX_INPUT_LENGTH) return baseValue
                replaceRegex(pattern, baseValue, replacement) ?: baseValue
            }
            else -> baseValue
        }
    }

    /**
     * The 0-based arrays text.split and text.match store: `#` counts the elements, `#>` and `#<` are
     * 1 and the count (0 when empty), the indexes Array Push, Pop and Process give this list, a
     * number is one element and `a:b` joins a range with commas, backwards when a is past b. A variable inside
     * the parentheses is filled in first and trimmed, so `%parts(%i)` reads the element at `%i` and
     * gives empty while `%i` is, as does a range with an empty variable on either side. Any other
     * form joins the elements with it. Both actions also set [name] to the first element, so while
     * it holds a value the stored array answers only those array forms and `+sep` and `*` (see [joinOrPick]). It never answers once
     * [name] has numbered elements (`%parts1`, ...) of its own, whether [name] is set or not. Null
     * leaves [op] to them.
     */
    private fun storedArrayValue(
        name: String,
        op: String,
        baseValue: String?,
        store: VariableStore,
        arrays: ArrayStore,
    ): String? {
        val expanded = if ('%' in op) expandText(op, store, arrays) { it } else op
        val form = expanded.javaTrim()
        val pick = joinOrPick(expanded)
        // `+sep` and `*` are array forms too, so they read the array while [name] holds its first element.
        val arrayForm = pick != null || STORED_ARRAY_FORM.matches(form)
        if (store.numberedItems(name).isNotEmpty() || (baseValue != null && !arrayForm)) return null
        if (form.isEmpty() && '%' in op) return ""
        if (':' in op && op.split(':', limit = 2).any { side -> '%' in side && expandText(side, store, arrays) { it }.isBlank() }) return ""
        val size = arrays.length(name)
        // Ahead of the joiner fallback below, which would read `+=` or `*` as the text to join with.
        when (pick) {
            JoinOrPick.AtRandom -> return if (size == 0) "" else arrays.get(name, Random.nextInt(size))
            // A `1,3` list or a search isn't one of the stored array's forms yet, so it joins with all
            // of it, as before. Any other range that doesn't read, like one past what a number holds, gives empty.
            is JoinOrPick.Join -> return storedRange(name, pick.range.javaTrim(), size, arrays)?.joinToString(pick.separator)
                ?: if (isPositionList(pick.range, pick.range) || arraySearch(pick.range) { it } != null) {
                    arrays.join(name, "${pick.separator}+${pick.range}")
                } else {
                    ""
                }
            null -> Unit
        }
        return when {
            form == "#" -> size.toString()
            // The first and last index as the array actions number this list, from 1.
            form == "#>" -> if (size == 0) "0" else "1"
            form == "#<" -> size.toString()
            form.isEmpty() || !arrayForm -> arrays.join(name, expanded)
            ':' !in form -> form.toIntOrNull()?.let { arrays.get(name, it) }.orEmpty()
            else -> storedRange(name, form, size, arrays)?.joinToString(",").orEmpty()
        }
    }

    /** The stored array's values a `a:b`, `:b`, `a:`, `:`, empty or single-index [form] names, 0-based; null when it isn't one. */
    private fun storedRange(name: String, form: String, size: Int, arrays: ArrayStore): List<String>? {
        if (form.isNotEmpty() && ':' !in form) {
            val index = form.toIntOrNull() ?: return null
            return if (index in 0 until size) listOf(arrays.get(name, index)) else emptyList()
        }
        val from = form.substringBefore(':').ifEmpty { "0" }.toIntOrNull() ?: return null
        val to = form.substringAfter(':', "").ifEmpty { null }?.let { it.toIntOrNull() ?: return null } ?: (size - 1)
        // Cut to the array before walking it, so `0:2000000000` doesn't count to two billion.
        val range = if (from <= to) from.coerceAtLeast(0)..to.coerceAtMost(size - 1) else from.coerceAtMost(size - 1) downTo to.coerceAtLeast(0)
        return range.map { arrays.get(name, it) }
    }

    /**
     * The `+sep` (every element, joined by sep), `+sep+range` (the elements in range, as in
     * `%arr(+>+2:4)`) and `*` (one element at random) forms, read from the form with its variables
     * filled in. Null for any other form. The separator is everything after the first `+`, so
     * `%arr(+, )` joins with a comma and a space, unless [hasJoinRange] says a range follows the
     * last `+`.
     */
    private sealed interface JoinOrPick {
        data class Join(val separator: String, val range: String) : JoinOrPick
        data object AtRandom : JoinOrPick
    }

    private fun joinOrPick(expanded: String): JoinOrPick? {
        if (expanded.javaTrim() == "*") return JoinOrPick.AtRandom
        if (!expanded.startsWith('+')) return null
        val rest = expanded.substring(1)
        if (!hasJoinRange(rest)) return JoinOrPick.Join(rest, "")
        val split = if ("+\$?" in rest) rest.indexOf("+\$?") else rest.lastIndexOf('+')
        val range = rest.substring(split + 1)
        // The range reads as `%arr(range)` would, trimmed. Text after the last `+` that isn't one of
        // the forms read here stays part of the separator, so it joins every element with all of it.
        val readable = RANGE_FORM.matches(range.javaTrim()) || isPositionList(range, range) || arraySearch(range) { it } != null
        return if (readable) JoinOrPick.Join(rest.substring(0, split), range) else JoinOrPick.Join(rest, "")
    }

    /**
     * Whether the text after a join's first `+` names a range after a later `+`. It needs a second
     * `+`, and then one of: a `+$?` search, a `:` or `,` along with a decimal digit and no line
     * break but `\n`, or an ending of `+` and a single digit with no line break before it. So
     * `%arr(+;+2)` reads element 2 while `%arr(+;+12)` and `%arr(+x+:)` join with `;+12` and `x+:`.
     * A digit is a decimal digit in any script and a line break is [isAndroidLineTerminator], so a
     * test on the JVM reads a form the way a phone does.
     */
    private fun hasJoinRange(rest: String): Boolean {
        if ('+' !in rest) return false
        if ("+\$?" in rest) return true
        val namesRange = ':' in rest || ',' in rest
        val noLineBreakButNewline = rest.none { it != '\n' && isAndroidLineTerminator(it) }
        if (namesRange && noLineBreakButNewline && hasDecimalDigit(rest)) return true
        return endsInPlusAndOneDigit(rest)
    }

    /** Something with no line break, then `+` and one decimal digit at the end. */
    private fun endsInPlusAndOneDigit(text: String): Boolean {
        if (text.isEmpty()) return false
        val last = text.codePointBefore(text.length)
        val plusAt = text.length - Character.charCount(last) - 1
        return Character.isDigit(last) && plusAt >= 1 && text[plusAt] == '+' &&
            text.substring(0, plusAt).none(::isAndroidLineTerminator)
    }

    private fun hasDecimalDigit(text: String): Boolean {
        var at = 0
        while (at < text.length) {
            val codePoint = text.codePointAt(at)
            if (Character.isDigit(codePoint)) return true
            at += Character.charCount(codePoint)
        }
        return false
    }

    /** A line break, as Android's regex `.` refuses to match it: vertical tab and form feed included. */
    private fun isAndroidLineTerminator(c: Char): Boolean =
        c == '\n' || c.code == 0x0B || c.code == 0x0C || c == '\r' || c.code == 0x85 || c.code == 0x2028 || c.code == 0x2029

    /**
     * The array forms over numbered variables (`%arr1`, `%arr2`, ...): `#` counts the elements,
     * `#>` and `#<` give the first and last index, `>` and `<` the first and last value, a number
     * one element, and an empty, `:` or `a:b` form joins the elements set in that range with commas,
     * backwards when a is past b. A variable inside the parentheses is filled in first, so
     * `%arr(%i)` reads the element `%i` names, and gives empty when `%i` is, or when a variable on
     * either side of a range is. Returns null when [op] is none of these, or when [name] is a plain
     * variable with no elements, so its own operators still apply.
     */
    private fun arrayFormValue(
        name: String,
        op: String,
        baseValue: String?,
        store: VariableStore,
        arrays: ArrayStore,
    ): String? {
        val expanded = if ('%' in op) expandText(op, store, arrays) { it } else op
        val form = expanded.javaTrim()
        val pick = joinOrPick(expanded)
        val search = when (pick) {
            null -> arraySearch(op) { text -> if ('%' in text) expandText(text, store, arrays) { it } else text }
            // A join's range is filled in already.
            is JoinOrPick.Join -> arraySearch(pick.range) { it }
            JoinOrPick.AtRandom -> null
        }
        if (pick == null && search == null && !NUMBERED_ARRAY_FORM.matches(form) && !isPositionListForm(op, expanded)) return null
        val items = store.numberedItems(name)
        // The parts Split Text keeps don't search yet, so they read as before.
        if (search != null && items.isEmpty() && arrays.contains(name)) return null
        // A plain variable with no elements keeps its own operators, like `%n(+5)` adding 5. The
        // counts and searches aren't among them: they read the elements whatever the base holds, so
        // a count of a set base with no elements is 0.
        if (items.isEmpty() && baseValue != null && form !in COUNT_FORMS && search == null) return null
        search?.let { return readArraySearch(it, store.elementsByNumber(name), items, joiner = (pick as? JoinOrPick.Join)?.separator ?: ",") }
        when (pick) {
            JoinOrPick.AtRandom -> return items.values.randomOrNull().orEmpty()
            is JoinOrPick.Join -> return numberedRange(items, pick.range)?.joinToString(pick.separator).orEmpty()
            null -> Unit
        }
        // An index variable that came out empty names no element, so it isn't the empty form
        // that joins them all, nor the open side of a range (%arr(%a:%b) isn't %arr(:)).
        if (form.isEmpty() && '%' in op) return ""
        // A list's parts aren't ranges, so `%arr(%a:1,2)` still reads element 2.
        if (':' in op && !isPositionList(op, expanded) &&
            op.split(':', limit = 2).any { side -> '%' in side && expandText(side, store, arrays) { it }.isBlank() }
        ) {
            return ""
        }
        val lastIndex = if (items.isEmpty()) 0 else items.lastKey()
        return when (form) {
            "#" -> items.size.toString()
            "#>" -> (if (items.isEmpty()) 0 else items.firstKey()).toString()
            "#<" -> lastIndex.toString()
            ">" -> items.firstEntry()?.value.orEmpty()
            "<" -> items.lastEntry()?.value.orEmpty()
            else -> numberedRange(items, expanded)?.joinToString(",").orEmpty()
        }
    }

    /**
     * A list of positions, `%arr(1,3)`: the form, [filledIn] and trimmed, holds a comma, and the
     * text as [written] isn't a search, `#?...` or `$...?`, which is spotted before trimming (see
     * [arraySearch]).
     */
    private fun isPositionList(written: String, filledIn: String): Boolean =
        ',' in filledIn.javaTrim() && !written.startsWith("#?") && !(written.startsWith('$') && '?' in written)

    /**
     * An array search: `#?pattern` reads the numbers of the elements that match, `$sep?pattern`
     * their values, joined by `sep` when there is one. A null [pattern] is a `$...?` text that
     * doesn't split into a joiner and a pattern, which finds nothing.
     */
    private class ArraySearch(val values: Boolean, val pattern: String?, val joiner: String?)

    /**
     * The search the text as [written] makes, spotted before trimming. Null when it isn't one, or
     * is a bare `#?` or `$?`, which aren't searches. [fillIn] puts the variables in, after `#?` for
     * a number search and, for a value search, into the trimmed text, which isn't trimmed again,
     * so a pattern can end in a space a variable put there.
     */
    private fun arraySearch(written: String, fillIn: (String) -> String): ArraySearch? = when {
        written.startsWith("#?") && written.length > 2 -> ArraySearch(values = false, pattern = fillIn(written.substring(2)), joiner = null)
        written.startsWith('$') && '?' in written -> fillIn(written.javaTrim()).takeIf { it.length != 2 }?.let { text ->
            val split = splitValueSearch(text)
            ArraySearch(values = true, pattern = split?.second, joiner = split?.first?.ifEmpty { null })
        }
        else -> null
    }

    /**
     * Splits a value search, `$joiner?pattern`, into its joiner and pattern, or null when it has
     * none. The joiner may hold `\n` but no other line break ([isAndroidLineTerminator]), and the
     * pattern runs from its `?` to the next line break of any kind. The latest `?` whose pattern
     * fits wins: any non-empty pattern, or, when [text] holds `~R` anywhere, one that starts `~R`
     * or `!~R` and has more after that.
     */
    private fun splitValueSearch(text: String): Pair<String, String>? {
        if (!text.startsWith('$')) return null
        val regex = "~R" in text
        // The joiner stops short of the first line break other than \n.
        val reach = (1 until text.length).firstOrNull { text[it] != '\n' && isAndroidLineTerminator(text[it]) } ?: text.length
        for (mark in reach - 1 downTo 1) {
            if (text[mark] != '?') continue
            val pattern = text.substring(mark + 1).takeWhile { !isAndroidLineTerminator(it) }
            val fits = if (regex) {
                (pattern.startsWith("~R") && pattern.length > 2) || (pattern.startsWith("!~R") && pattern.length > 3)
            } else {
                pattern.isNotEmpty()
            }
            if (fits) return text.substring(1, mark) to pattern
        }
        return null
    }

    /**
     * What [search] reads, joined by its own joiner or [joiner]. It looks through [elements],
     * `%arr0` and `%arr07` included, and an element matches when it equals the pattern or [matches]
     * says so; none matching reads 0. A value search reads each value back by its number from the
     * [numbered] elements, so `%arr07` reads `%arr7`'s value, and `%arr0` or a number with no
     * element reads as nothing.
     */
    private fun readArraySearch(
        search: ArraySearch,
        elements: List<Pair<Int, String>>,
        numbered: java.util.NavigableMap<Int, String>,
        joiner: String,
    ): String {
        val pattern = search.pattern ?: return "0"
        val hits = elements.filter { (_, value) -> value == pattern || matches(value, pattern) }.map { it.first }
        if (hits.isEmpty()) return "0"
        val parts = if (search.values) hits.mapNotNull { numbered[it] } else hits.map(Int::toString)
        return parts.joinToString(search.joiner ?: joiner)
    }

    /**
     * [isPositionList], unless the form as [written] starts with a letter: this app's own operators,
     * like `%text(split:,)`, only count right there, so `%arr( a,1)` and `%arr(%i,1)` are still lists.
     */
    private fun isPositionListForm(written: String, filledIn: String): Boolean =
        isPositionList(written, filledIn) && !(written.isNotEmpty() && Character.isLetter(written.codePointAt(0)))

    /**
     * Java's `String.trim`, which the array forms are read with: it drops every character up to a
     * space, control characters included, and keeps other spaces, like a no-break space, that
     * Kotlin's `trim()` would drop.
     */
    private fun String.javaTrim(): String = trim { it <= ' ' }

    /**
     * The numbered elements a `a:b`, `:b`, `a:`, `:`, empty or single-index form names, spaces
     * trimmed, or a `1,3` list in its order, with each part read from [text] as written, so a part
     * that isn't a whole number, spaces and all, names nothing; null when it isn't one.
     */
    private fun numberedRange(items: java.util.NavigableMap<Int, String>, text: String): List<String>? {
        // Its callers have already told a list from a search.
        if (',' in text.javaTrim()) return text.split(',').mapNotNull { part -> part.toIntOrNull()?.let { items[it] } }
        val form = text.javaTrim()
        if (form.isNotEmpty() && ':' !in form) return listOfNotNull(items[form.toIntOrNull() ?: return null])
        val lastIndex = if (items.isEmpty()) 0 else items.lastKey()
        val from = form.substringBefore(':').ifEmpty { "1" }.toIntOrNull() ?: return null
        val to = form.substringAfter(':', "").ifEmpty { null }?.let { it.toIntOrNull() ?: return null } ?: lastIndex
        val range = if (from <= to) items.subMap(from, true, to, true) else items.subMap(to, true, from, true).descendingMap()
        return range.values.toList()
    }

    private fun parseRegexOp(body: String): Pair<String, Int> {
        val splitAt = body.lastIndexOf(':')
        if (splitAt <= 0) return body to 0
        val groupIdx = body.substring(splitAt + 1).toIntOrNull() ?: return body to 0
        return body.substring(0, splitAt) to groupIdx
    }

    /**
     * Evaluates [cond] as the author typed it. Its `||`, `&&`, parentheses and comparison operator
     * are read from that text first, and only then is each operand handed to [expandOperand], once.
     *
     * Callers must not expand [cond] themselves. A value is a stranger's words as often as not (an
     * SMS body, a notification title), and expanding before parsing let `true ||` in a message add
     * a branch to the condition, and let `%PIN` in a message be read back as the PIN's value when
     * the operand was expanded a second time.
     *
     * [expandOperand] defaults to legacy `%var` expansion. A caller with a template stage passes
     * its own, and a `{{ ... }}` block in [cond] is kept whole for it. Its second argument says how
     * an unset `%name` reads. It stays as written on either side of a text comparison, so
     * `%par1 ~ %*` is how a task asks whether it was given anything. It's nothing for Is Set and
     * Isn't Set, which ask whether there's a value, and 0 in the comparisons worked out as maths:
     * `<`, `>`, `<=`, `>=`, Is Even and Is Odd.
     */
    fun evaluateCondition(
        cond: String,
        variableStore: VariableStore,
        arrayStore: ArrayStore,
        expandOperand: ((String, Unset) -> String)? = null,
    ): Boolean =
        try {
            evaluateConditionInternal(
                cond,
                expandOperand ?: { operand, unset -> expandInternal(operand, variableStore, arrayStore, unset = unset) },
            )
        } catch (e: Exception) {
            false
        }

    /**
     * How [evaluateCondition] reads [cond] at its top level, once its outer brackets come off: the
     * parts it ORs (`||`, the looser) or ANDs (`&&`), or no joiner and the one comparison left. An
     * importer or exporter reads a guard through this so it handles the joiners the way the guard runs.
     */
    internal fun conditionParts(cond: String): Pair<String?, List<String>> {
        val normalized = stripOuterParens(cond.trim())
        splitTopLevel(normalized, "||")?.let { return "||" to it }
        splitTopLevel(normalized, "&&")?.let { return "&&" to it }
        return null to listOf(normalized)
    }

    /**
     * The one comparison [evaluateCondition] finds in [cond] as its left side, operator token and
     * right side, or null when there's none or more than one outside brackets. An importer or
     * exporter reads a condition through this, so an operator inside brackets stays part of its side.
     */
    internal fun comparisonParts(cond: String): Triple<String, String, String>? =
        parseComparison(cond.trim())?.let { Triple(it.left, it.operator.token, it.right) }

    private fun evaluateConditionInternal(cond: String, expandOperand: (String, Unset) -> String): Boolean {
        val (joiner, parts) = conditionParts(cond)
        when (joiner) {
            "||" -> return parts.any { evaluateConditionInternal(it, expandOperand) }
            "&&" -> return parts.all { evaluateConditionInternal(it, expandOperand) }
        }
        val normalized = parts.single()
        if (normalized.isEmpty()) return false

        // A real binary comparison always wins over the unary is_set/not_set reading: e.g.
        // "%status == is_set", comparing a variable against the literal string "is_set", must
        // stay an equality check, not get hijacked into an existence check just because the text
        // happens to end with that word. Unary existence syntax is tried only once no binary
        // comparison matches at all -- which also means "is_set"/"not_set" can never appear as a
        // *value* on either side of a real ==, !=, ~, etc. comparison and be misread as the unary
        // operator, since parseComparison always gets first look.
        val comparison = parseComparison(normalized)
        if (comparison == null) {
            unaryExistenceResult(normalized, expandOperand)?.let { return it }
            unaryParityResult(normalized, expandOperand)?.let { return it }
            // A variable on its own, such as `%enabled`, is a boolean once it has its value.
            return expandOperand(normalized, Unset.BLANK).trim().toBoolean()
        }
        // Trimmed after expansion: command output and file reads end with a newline, and a value
        // that came back empty matches nothing, so two empty sides aren't "equal". An unset side
        // stays as written, so `%a == %b` is false while both are unset, and `%a == %a` is true,
        // except in a numeric comparison, where it's 0. A number isn't trimmed here, since
        // [readNumber] decides for itself which spaces and controls it drops.
        val numeric = comparison.operator in NUMERIC_OPERATORS
        // FORK: an unset side reads as nothing on both kinds of comparison, as it always has here —
        // upstream's AS_WRITTEN / ZERO (Tasker's reading) would turn every `x%var == x` check in
        // 白い熊's tasks around, and make an unset `%n < 5` true.
        val unset = Unset.BLANK
        // FORK: both sides are trimmed on every comparison, numeric ones included, as before — a
        // number read from a file or a command ends with a line break.
        val left = expandOperand(comparison.left, unset).trim()
        val right = expandOperand(comparison.right, unset).trim()

        return when (comparison.operator) {
            ComparisonOperator.EQ -> left.isNotEmpty() && left == right
            ComparisonOperator.NE -> left != right
            ComparisonOperator.LE -> compareNumbers(left, right) { l, r -> l <= r }
            ComparisonOperator.GE -> compareNumbers(left, right) { l, r -> l >= r }
            ComparisonOperator.LT -> compareNumbers(left, right) { l, r -> l < r }
            ComparisonOperator.GT -> compareNumbers(left, right) { l, r -> l > r }
            // FORK: `~` keeps the fork's wildcard match, where `*` is the only special character.
            // Upstream's Tasker reading (2026-11-11: `/` alternatives, `+`, a leading `!`, `~R`, case
            // ignored for a lowercase pattern) changes what existing conditions mean — `/ping*` would
            // match "ping", `*/sdcard/*` everything. Tasker's own matcher stays available as
            // [matches] for the steps that are Tasker's (Variable Clear).
            ComparisonOperator.MATCHES -> left.isNotEmpty() && matchesStarGlob(left, right)
            ComparisonOperator.NOT_MATCHES -> !matchesStarGlob(left, right)
            ComparisonOperator.MATCHES_REGEX -> findsRegex(left, right)
            ComparisonOperator.NOT_MATCHES_REGEX -> !findsRegex(left, right)
        }
    }

    /**
     * Matches. A pattern starting `~R` or `!~R` is a regular expression, and a leading `!` turns
     * the rest of the pattern around, so `%a ~ !x` is `%a !~ x`. Anything else goes to
     * [matchesGlob], where [emptyMatchesNothing] keeps `%a ~ *` false while `%a` is empty. An empty
     * pattern matches nothing, and that's checked before the `!`, so `!` alone isn't "not empty".
     */
    /** The fork's `~`: `*` is the only special character, everything else is literal, case counts. */
    private fun matchesStarGlob(value: String, pattern: String): Boolean {
        if (pattern.length > MAX_REGEX_LENGTH || value.length > MAX_REGEX_INPUT_LENGTH) return false
        val regex = buildString {
            append("(?s)\\A")
            pattern.split("*").forEachIndexed { index, literal ->
                if (index > 0) append(".*")
                append(Re2Pattern.quote(literal))
            }
            append("\\z")
        }
        val matcher = compileLinearRegex(regex)?.matcher(value) ?: return false
        return try {
            matcher.find()
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun matchesPattern(value: String, pattern: String, emptyMatchesNothing: Boolean): Boolean = when {
        pattern.isEmpty() -> false
        pattern.startsWith("~R") -> findsRegex(value, pattern.substring(2))
        pattern.startsWith("!~R") -> !findsRegex(value, pattern.substring(3))
        pattern.startsWith("!") -> !matchesGlob(value, pattern.substring(1))
        else -> !(emptyMatchesNothing && value.isEmpty()) && matchesGlob(value, pattern)
    }

    /**
     * Matches and Doesn't Match add `/on` to a pattern that is just the word for on in the phone's
     * language, and `/off` to the word for off, so `%BLUE ~ On`, or `Ein` in German, also matches
     * the `on` a state variable holds. The whole pattern is compared, case included, and `on` and
     * `off` themselves are left alone.
     */
    private fun withEnglishOnOff(pattern: String, locale: Locale = Locale.getDefault()): String {
        val (on, off) = ON_OFF_WORDS[onOffLanguage(locale)] ?: ON_OFF_WORDS.getValue("")
        return when {
            pattern != "on" && pattern == on -> "$pattern/on"
            pattern != "off" && pattern == off -> "$pattern/off"
            else -> pattern
        }
    }

    /** The [ON_OFF_WORDS] key for [locale], chosen the way Android picks a translated resource. */
    private fun onOffLanguage(locale: Locale): String = when (val language = locale.language) {
        "zh" -> if (locale.country == "TW" || locale.script == "Hant") "zh-TW" else "zh"
        // Android still reports Indonesian by its old code, and the JVM by the new one.
        "id" -> "in"
        else -> language
    }

    /** [matchesPattern] for a value that is never empty, like the variable names Variable Clear matches. */
    fun matches(value: String, pattern: String): Boolean = matchesPattern(value, pattern, emptyMatchesNothing = false)

    /**
     * Matches Regex (`~R`, and `!~R` for Doesn't Match Regex). The pattern only has to match part
     * of the value, and case counts unless the pattern says `(?i)`. An empty pattern, or one RE2
     * can't run (backreferences, lookarounds), matches nothing, like any pattern that won't compile.
     */
    private fun findsRegex(value: String, pattern: String): Boolean {
        if (pattern.isEmpty() || pattern.length > MAX_REGEX_LENGTH || value.length > MAX_REGEX_INPUT_LENGTH) return false
        val matcher = compileLinearRegex(pattern)?.matcher(value) ?: return false
        return try {
            matcher.find()
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Simple pattern matching: `/` separates alternatives, `*` is any number of any character and
     * `+` is one or more, and an alternative with no capital letter ignores case. The whole value
     * has to match. Used for "Matches"/"Doesn't Match" conditions, e.g. `%pa_do ~ view_url` or
     * `%SSID ~ home/work`. [matchesPattern] handles a leading `!` before this.
     *
     * Goes through [compileLinearRegex] (RE2, linear-time) rather than Kotlin's backtracking
     * `Regex`: `pattern` here comes from a condition a user or an import wrote,
     * not a fixed literal, so several `*` wildcards is exactly the shape that causes catastrophic
     * backtracking in a standard engine. `com.google.re2j.Pattern.quote` is RE2J's per-character
     * `quoteMeta` escaper, not `kotlin.text.Regex.escape`'s `\Q...\E` -- RE2 doesn't implement
     * `\Q...\E` as a syntax construct at all, so the latter would either fail to compile or (worse)
     * be silently misinterpreted.
     *
     * The [MAX_REGEX_LENGTH] cap this file puts on every regex built from imported or variable
     * text applies per alternative here, so a long list of short choices still works. An
     * alternative over it, or a value or whole pattern over [MAX_REGEX_INPUT_LENGTH], matches
     * nothing, which makes `!~` true for it.
     *
     * `\A`/`\z` anchor for a full-string match (RE2J's `Matcher` is not confirmed to expose a
     * `.matches()` convenience the way `java.util.regex.Matcher` does, so this uses the same
     * `.find()` call already proven out at the other [compileLinearRegex] call sites).
     */
    private fun matchesGlob(value: String, pattern: String): Boolean {
        if (pattern.length > MAX_REGEX_INPUT_LENGTH || value.length > MAX_REGEX_INPUT_LENGTH) return false
        return pattern.trim('/').split('/').any {
            it.length <= MAX_REGEX_LENGTH && matchesPatternAlternative(value, it)
        }
    }

    /**
     * One alternative, built as a regex from the literals between its wildcards: between two
     * literals every `*` and `+` counts, but in a run at either end only the outermost one does,
     * so `*+a` is `*a` and `a+*` is `a*`.
     */
    private fun matchesPatternAlternative(value: String, alternative: String): Boolean {
        val literals = alternative.split(PATTERN_WILDCARD).dropLastWhile { it.isEmpty() }
        val wildcards = alternative.filter { it == '*' || it == '+' }
        val regex = buildString {
            append(if (alternative.lowercase() == alternative) "(?si)\\A" else "(?s)\\A")
            alternative.firstOrNull()?.let { if (it == '*') append(".*") else if (it == '+') append(".+") }
            var literalsSoFar = 0
            literals.forEachIndexed { index, literal ->
                if (literalsSoFar > 0) append(if (wildcards[index - 1] == '*') ".*" else ".+")
                if (literal.isNotEmpty()) {
                    literalsSoFar++
                    append(Re2Pattern.quote(literal))
                }
            }
            // The last character counts only if it isn't the leading one already used.
            if (alternative.length > 1) {
                when (alternative.last()) {
                    '*' -> append(".*")
                    '+' -> append(".+")
                }
            }
            append("\\z")
        }
        val matcher = compileLinearRegex(regex)?.matcher(value) ?: return false
        return try {
            matcher.find()
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * The index just past a `{{ ... }}` block opening at [index], or -1 when none opens there. The
     * structure scans step over these: a template's pipes, quoted defaults and brackets belong to
     * the template stage, never to the condition around it.
     */
    private fun templateBlockEnd(expr: String, index: Int): Int {
        if (!expr.startsWith("{{", index)) return -1
        val close = expr.indexOf("}}", index + 2)
        return if (close == -1) -1 else close + 2
    }

    private fun splitTopLevel(expr: String, delimiter: String): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var partStart = 0
        var index = 0
        while (index < expr.length) {
            val templateEnd = templateBlockEnd(expr, index)
            if (templateEnd != -1) {
                index = templateEnd
                continue
            }
            when (expr[index]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (depth == 0 && expr.startsWith(delimiter, index)) {
                parts += expr.substring(partStart, index).trim()
                index += delimiter.length
                partStart = index
                continue
            }
            index++
        }
        if (parts.isEmpty()) return null
        parts += expr.substring(partStart).trim()
        return parts
    }

    /**
     * Unary existence checks (Is Set and Not Set, which have no right-hand operand).
     * Suffix-anchored, not scanned like the binary operators, so a
     * variable value that happens to contain "is_set"/"not_set" mid-string can't false-match.
     * Returns null when `normalized` doesn't end with either suffix at all, so the caller can
     * fall through to its own default (`normalized.toBoolean()`).
     *
     * `normalized` is the author's text, so the operand still holds its `%variable` and is expanded
     * here, once, with an unset variable filled in as nothing. A bare "is_set"/"not_set" with
     * nothing before it means "the value is empty".
     */
    private fun unaryExistenceResult(normalized: String, expandOperand: (String, Unset) -> String): Boolean? {
        for ((suffix, wantsNonEmpty) in UNARY_EXISTENCE_SUFFIXES) {
            val bareSuffix = suffix.trim()
            if (normalized.equals(bareSuffix, ignoreCase = true)) {
                return !wantsNonEmpty
            }
            if (normalized.endsWith(suffix, ignoreCase = true)) {
                val operand = expandOperand(normalized.dropLast(suffix.length).trim(), Unset.BLANK).trim()
                return if (wantsNonEmpty) operand.isNotEmpty() else operand.isEmpty()
            }
        }
        return null
    }

    /**
     * Is Even and Is Odd, which take no right-hand operand either. A value that isn't a finite
     * number is neither, and otherwise Even is `value % 2 == 0` and Odd is anything else, so 2.5 is
     * odd. The variable fills in as maths, so an unset or empty one is 0 and even. Null when
     * neither suffix is there.
     */
    private fun unaryParityResult(normalized: String, expandOperand: (String, Unset) -> String): Boolean? {
        for ((suffix, wantsEven) in UNARY_PARITY_SUFFIXES) {
            if (!normalized.endsWith(suffix, ignoreCase = true)) continue
            val value = readNumber(expandOperand(normalized.dropLast(suffix.length).trim(), Unset.ZERO))
                ?: return false
            val remainder = value % 2.0
            return if (wantsEven) remainder == 0.0 else remainder != 0.0
        }
        return null
    }

    private fun stripOuterParens(expr: String): String {
        var result = expr
        while (result.length >= 2 && result.first() == '(' && matchingCloseParen(result, 0) == result.lastIndex) {
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun matchingCloseParen(expr: String, openIndex: Int): Int {
        var depth = 0
        var index = openIndex
        while (index < expr.length) {
            val templateEnd = templateBlockEnd(expr, index)
            if (templateEnd != -1) {
                index = templateEnd
                continue
            }
            when (expr[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    private fun parseComparison(expr: String): ConditionComparison? {
        val matches = mutableListOf<ConditionComparison>()
        var depth = 0
        var index = 0
        while (index < expr.length) {
            val templateEnd = templateBlockEnd(expr, index)
            if (templateEnd != -1) {
                index = templateEnd
                continue
            }
            when (expr[index]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (depth == 0) {
                val operator = COMPARISON_OPERATORS.firstOrNull { expr.matchesOperatorAt(it, index) }
                if (operator != null) {
                    val left = expr.substring(0, index).trim()
                    val right = expr.substring(index + operator.token.length).trim()
                    // Fork: one empty side is an empty VALUE, not a non-comparison. A flow.if condition
                    // reaches here already expanded, so `%list !~ *|3|*` with %list empty arrives as
                    // ` !~ *|3|*`; refusing it made the whole condition plain false, and 携帯's relay
                    // skipped every received SMS (2026-11-01). Both sides empty is still no comparison.
                    if (left.isEmpty() && right.isEmpty()) return null
                    matches += ConditionComparison(left, operator, right)
                    // A pattern is the whole right-hand side, so once the first operator is a
                    // match, the rest is its pattern, a `<` or `==` in it included.
                    if (matches.size == 1 && operator in TILDE_OPERATORS) return matches.single()
                    index += operator.token.length
                    continue
                }
            }
            index++
        }
        return matches.singleOrNull()
    }

    /**
     * True if `operator`'s token occurs at `index`, and -- for [ComparisonOperator.MATCHES] /
     * [ComparisonOperator.NOT_MATCHES] and their `~R` / `!~R` regex forms only -- is bounded by
     * whitespace or a string edge on both sides. `~` and `!~` are ordinary characters in real
     * values in a way `==`/`<`/etc. are not (paths like `~/backups`, version strings like
     * `1.2~rc1`, approximations like `~100`), so
     * without this a literal tilde inside an otherwise unambiguous comparison's left/right text
     * makes parseComparison find two operator matches instead of one, `matches.singleOrNull()`
     * returns null, and the whole comparison silently falls back to `normalized.toBoolean()`
     * (false) -- turning a working condition into one that's always false. The other operators
     * don't get this treatment: it would be a larger, non-additive behavior change for tokens
     * this project already shipped with, out of scope for this fix.
     */
    private fun String.matchesOperatorAt(operator: ComparisonOperator, index: Int): Boolean {
        if (!startsWith(operator.token, index)) return false
        if (operator !in TILDE_OPERATORS) return true
        val before = index == 0 || this[index - 1].isWhitespace()
        val afterIndex = index + operator.token.length
        val after = afterIndex >= length || this[afterIndex].isWhitespace()
        return before && after
    }

    private fun compareNumbers(left: String, right: String, predicate: (Double, Double) -> Boolean): Boolean {
        val l = readNumber(left) ?: return false
        val r = readNumber(right) ?: return false
        return predicate(l, r)
    }

    /**
     * [text] as one number for maths. It drops every white space character first, reads `0x` as a
     * hexadecimal whole number, and otherwise takes a plain decimal, a trailing type letter like
     * `4f` included, with a sign-free exponent, since a `+` or `-` there would be an operator. The
     * result has to be finite, and it's rounded half up to 10 decimal places, so
     * `0.30000000000000004` equals `0.3`. Kotlin's parser alone also takes `0x1p3`, `NaN` and
     * `Infinity`, which aren't numbers here.
     */
    private fun readNumber(text: String): Double? {
        // White space is every Unicode White_Space character, no-break spaces and U+0085 included
        // ([isAndroidRegexSpace]). Any control character left at either end goes too. A zero width
        // space or U+FEFF is neither, so it stays and the text isn't a number.
        val compact = text.filterNot { isAndroidRegexSpace(it) }.trim { it <= ' ' }
        val value = when {
            // A sign after 0x would be an operator, so `0x-1` isn't a number.
            compact.startsWith("0x", ignoreCase = true) ->
                compact.substring(2).takeUnless { it.startsWith('+') || it.startsWith('-') }?.toLongOrNull(16)?.toDouble()
            PLAIN_DECIMAL.matches(compact) -> compact.toDouble()
            else -> null
        }
        return value?.takeIf { it.isFinite() }
            ?.let { BigDecimal(it.toString()).setScale(NUMBER_DECIMAL_PLACES, RoundingMode.HALF_UP).toDouble() }
    }

    private fun evaluateJsonPath(json: String, path: String): String {
        // org.json recurses per level and a StackOverflowError gets past the catch below.
        if (JsonNesting.lenientDepth(json) > JsonNesting.MAX_DATA_DEPTH) return ""
        try {
            var current: Any? = JSONObject(json)
            val keys = path.split(".")
            for (key in keys) {
                current = if (current is JSONObject) {
                    current.opt(key)
                } else {
                    return ""
                }
            }
            return current?.toString() ?: ""
        } catch (e: Exception) {
            return ""
        }
    }

    private fun readJsonPath(expr: String, start: Int): TokenExpansion {
        val path = StringBuilder()
        var cursor = start
        while (cursor < expr.length) {
            val segmentStart = cursor
            while (cursor < expr.length && isJsonPathSegmentChar(expr[cursor])) cursor++
            if (cursor == segmentStart) break
            if (path.isNotEmpty()) path.append('.')
            path.append(expr, segmentStart, cursor)
            if (cursor < expr.length && expr[cursor] == '.' &&
                cursor + 1 < expr.length && isJsonPathSegmentChar(expr[cursor + 1])
            ) {
                cursor++
            } else {
                break
            }
        }
        return TokenExpansion(path.toString(), cursor)
    }

    private fun extractRegexGroup(pattern: String, input: String, groupIdx: Int): String {
        val matcher = compileLinearRegex(pattern)?.matcher(input) ?: return ""
        return try {
            if (!matcher.find()) ""
            else matcher.group(groupIdx) ?: ""
        } catch (_: RuntimeException) {
            ""
        }
    }

    private fun replaceRegex(pattern: String, input: String, replacement: String): String? {
        val matcher = compileLinearRegex(pattern)?.matcher(input) ?: return null
        return try {
            matcher.replaceAll(replacement)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun isJsonPathSegmentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    /**
     * [unset] marks a plain `%name` that has no value, which [value] fills in as nothing, and
     * [operator] a `%name(...)` form.
     */
    private data class TokenExpansion(
        val value: String,
        val nextIndex: Int,
        val unset: Boolean = false,
        val operator: Boolean = false,
    )

    private data class ConditionComparison(
        val left: String,
        val operator: ComparisonOperator,
        val right: String,
    )

    private enum class ComparisonOperator(val token: String) {
        EQ("=="),
        NE("!="),
        LE("<="),
        GE(">="),
        LT("<"),
        GT(">"),
        MATCHES("~"),
        NOT_MATCHES("!~"),
        MATCHES_REGEX("~R"),
        NOT_MATCHES_REGEX("!~R"),
    }

    companion object {
        /** `%arr(%n)` — the op is exactly one variable reference, read as the index. */
        private val INDEX_VARIABLE = Regex("%([A-Za-z0-9_]+)")
        private val ARRAY_INDEX = Regex("\\d+")

        // (suffix, isSetWhenTrue) — order matters: "not_set"/"!is_set" style negatives must be
        // checked before a shorter positive suffix could partially match, though with these two
        // literal strings neither is a suffix of the other, so this is just future-proofing.
        private val UNARY_EXISTENCE_SUFFIXES = listOf(
            " is_set" to true,
            " not_set" to false,
        )
        // (suffix, isEvenWhenTrue)
        private val UNARY_PARITY_SUFFIXES = listOf(
            " is_even" to true,
            " is_odd" to false,
        )
        private val COMPARISON_OPERATORS = listOf(
            ComparisonOperator.EQ,
            ComparisonOperator.NE,
            ComparisonOperator.LE,
            ComparisonOperator.GE,
            ComparisonOperator.LT,
            ComparisonOperator.NOT_MATCHES_REGEX,
            ComparisonOperator.NOT_MATCHES,
            ComparisonOperator.MATCHES_REGEX,
            ComparisonOperator.MATCHES,
            ComparisonOperator.GT,
        )
        private val TILDE_OPERATORS = setOf(
            ComparisonOperator.MATCHES,
            ComparisonOperator.NOT_MATCHES,
            ComparisonOperator.MATCHES_REGEX,
            ComparisonOperator.NOT_MATCHES_REGEX,
        )

        /** The comparisons worked out as maths: less than, greater than, and their or-equal forms. */
        private val NUMERIC_OPERATORS = setOf(
            ComparisonOperator.LT,
            ComparisonOperator.GT,
            ComparisonOperator.LE,
            ComparisonOperator.GE,
        )
        // [0-9], not \d, which Android's ICU regex matches against every script's digits.
        private val PLAIN_DECIMAL = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][0-9]+)?[fFdD]?""")
        private const val NUMBER_DECIMAL_PLACES = 10

        // The word for on and off in each language Matches knows, "" for the default.
        private val ON_OFF_WORDS = mapOf(
            "" to ("On" to "Off"),
            "cs" to ("Zapnuto" to "Vypnuto"),
            "de" to ("Ein" to "Aus"),
            "es" to ("Encendido" to "Apagado"),
            "fr" to ("On" to "Off"),
            "hu" to ("Be" to "Ki"),
            "in" to ("Nyala" to "Mati"),
            "it" to ("Acceso" to "Spento"),
            "ja" to ("オン" to "オフ"),
            "ko" to ("켜기" to "끄기"),
            "nl" to ("Aan" to "Uit"),
            "pl" to ("Włączony" to "Wyłączony"),
            "pt" to ("Ligado" to "Desligar"),
            "ro" to ("Activat" to "Dezactivat"),
            "ru" to ("Вкл" to "Выкл"),
            "tr" to ("Devrede" to "kapalı"),
            "uk" to ("Увімк." to "Вимк."),
            "vi" to ("Trong" to "Tắt"),
            "zh" to ("开启" to "关闭"),
            "zh-TW" to ("開" to "關"),
        )
        private const val MAX_REGEX_LENGTH = 256
        private const val MAX_REGEX_INPUT_LENGTH = 10_000
        private val PATTERN_WILDCARD = Regex("[*+]")
        // \p{Nd} is what \d means on Android, so a test on the JVM reads these forms as a phone does.
        private val NUMBERED_ARRAY_FORM = Regex("""#[<>]?|[<>]|\p{Nd}*(?::\p{Nd}*)?""")
        private val COUNT_FORMS = setOf("#", "#<", "#>")
        private val STORED_ARRAY_FORM = Regex("""#[<>]?|\p{Nd}*(?::\p{Nd}*)?""")
        private val RANGE_FORM = Regex("""\p{Nd}*(?::\p{Nd}*)?""")

        private fun isVariableNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

        /**
         * True when [written] is a lone `%name` and [expanded] is that same text, which is how an
         * unset variable comes back. A caller that needs a value, such as an array position or a
         * goto target, treats it as empty. A variable holding its own name reads the same.
         */
        fun keptUnset(written: String?, expanded: String?): Boolean {
            val text = written?.trim().orEmpty()
            return text.length > 1 && text[0] == '%' && text[1].isLetter() &&
                text.drop(1).all(::isVariableNameChar) && expanded?.trim() == text
        }

        private fun compileLinearRegex(pattern: String): Re2Pattern? {
            return try {
                Re2Pattern.compile(pattern)
            } catch (_: RuntimeException) {
                null
            }
        }

        /**
         * Android's regex `\s`, which is `\p{IsWhite_Space}` there and only ASCII on the JVM the unit
         * tests run on, so the set is spelled out for both to agree.
         */
        internal fun isAndroidRegexSpace(char: Char): Boolean = with(char) {
            code in 0x09..0x0D || code == 0x20 || code == 0x85 || code == 0xA0 || code == 0x1680 ||
                code in 0x2000..0x200A || code == 0x2028 || code == 0x2029 || code == 0x202F ||
                code == 0x205F || code == 0x3000
        }
    }
}

/**
 * Array storage for list variables.
 * Arrays are accessed as %list(#) for count, %list(1) for index, %list() for join.
 */
class ArrayStore {
    // Access-ordered LRU: the least-recently-used array is evicted at the cap, not an
    // arbitrary entry. All access is synchronized on [lock] because the store is shared
    // across concurrently running tasks.
    private val lock = Any()
    private val arrays = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > MAX_ARRAYS
    }

    fun put(name: String, values: List<String>) {
        synchronized(lock) { arrays[name] = values }
    }

    fun clear() {
        synchronized(lock) { arrays.clear() }
    }

    /** Drops the array [name], returning whether there was one. */
    fun remove(name: String): Boolean {
        return synchronized(lock) { arrays.remove(name) != null }
    }

    fun get(name: String, index: Int): String {
        return synchronized(lock) { arrays[name]?.getOrNull(index) } ?: ""
    }

    fun length(name: String): Int {
        return synchronized(lock) { arrays[name]?.size } ?: 0
    }

    fun contains(name: String): Boolean {
        return synchronized(lock) { arrays.containsKey(name) }
    }

    fun join(name: String, delimiter: String): String {
        return synchronized(lock) { arrays[name]?.joinToString(delimiter) } ?: ""
    }

    fun snapshot(): Map<String, List<String>> =
        synchronized(lock) { LinkedHashMap(arrays).mapValues { (_, values) -> values.toList() } }

    companion object {
        private const val MAX_ARRAYS = 500
    }
}
