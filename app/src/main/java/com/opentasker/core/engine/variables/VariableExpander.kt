package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Enhanced variable expression evaluator supporting:
 * - Math operators: %var(+5), %var(*2), %var(//) floor, %var(/round)
 * - String operations: %var(upper), %var(lower), %var(substring:0:5), %var(trim), %var(split:,), %var(join:-)
 * - Regex: %var(regex:pattern:group) extract, %var(replace:pattern:replacement)
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
    fun expand(expr: String, variableStore: VariableStore, arrayStore: ArrayStore): String {
        return try {
            expandInternal(expr, variableStore, arrayStore)
        } catch (e: Exception) {
            // If evaluation fails, return original expression
            expr
        }
    }

    private fun expandInternal(expr: String, variableStore: VariableStore, arrayStore: ArrayStore): String {
        // Handle conditionals: (cond) ? true_val : false_val
        val ternaryMatch = TERNARY_PATTERN.find(expr)
        if (ternaryMatch != null) {
            val (condition, trueVal, falseVal) = ternaryMatch.destructured
            val result = evaluateConditionInternal(condition, variableStore, arrayStore)
            return expandText(if (result) trueVal else falseVal, variableStore, arrayStore)
        }

        return expandText(expr, variableStore, arrayStore)
    }

    private fun expandText(expr: String, variableStore: VariableStore, arrayStore: ArrayStore): String {
        if ('%' !in expr) return expr

        val out = StringBuilder(expr.length)
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c == '%' && i + 1 < expr.length && expr[i + 1].isLetter()) {
                val token = readVariableToken(expr, i, variableStore, arrayStore)
                out.append(token.value)
                i = token.nextIndex
            } else {
                out.append(c)
                i++
            }
        }

        return out.toString()
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
                return TokenExpansion(
                    evaluateJsonPath(variableStore.get("json") ?: "{}", path.value),
                    path.nextIndex,
                )
            }
        }

        if (cursor < expr.length && expr[cursor] == '(') {
            val close = expr.indexOf(')', startIndex = cursor + 1)
            if (close != -1) {
                val op = expr.substring(cursor + 1, close)
                return TokenExpansion(
                    evaluateVarOp(name, op, variableStore, arrayStore),
                    close + 1,
                )
            }
        }

        return TokenExpansion(variableStore.get(name) ?: "", cursor)
    }

    private fun evaluateVarOp(name: String, op: String, store: VariableStore, arrays: ArrayStore): String {
        val baseValue = store.get(name)

        if (arrays.contains(name) && baseValue == null) {
            return when {
                op == "#" -> arrays.length(name).toString()
                op.toIntOrNull() != null -> arrays.get(name, op.toInt())
                op.isEmpty() -> arrays.join(name, "")
                else -> arrays.joinWith(name, op)
            }
        }

        if (baseValue == null) return ""

        // Math operations
        val numValue = baseValue.toDoubleOrNull()
        if (numValue != null) {
            val result = when {
                op == "//" -> floor(numValue)
                op == "/round" -> numValue.roundToLong().toDouble()
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
                try {
                    val match = Regex(pattern).find(baseValue)
                    match?.groups?.get(groupIdx)?.value ?: ""
                } catch (e: Exception) {
                    ""
                }
            }
            op.startsWith("replace:") -> {
                val parts = op.substring(8).split(":", limit = 2)
                val pattern = parts.getOrNull(0) ?: ""
                val replacement = parts.getOrNull(1) ?: ""
                if (pattern.length > MAX_REGEX_LENGTH || baseValue.length > MAX_REGEX_INPUT_LENGTH) return baseValue
                try {
                    baseValue.replace(Regex(pattern), replacement)
                } catch (e: Exception) {
                    baseValue
                }
            }
            else -> baseValue
        }
    }

    private fun parseRegexOp(body: String): Pair<String, Int> {
        val splitAt = body.lastIndexOf(':')
        if (splitAt <= 0) return body to 0
        val groupIdx = body.substring(splitAt + 1).toIntOrNull() ?: return body to 0
        return body.substring(0, splitAt) to groupIdx
    }

    fun evaluateCondition(cond: String, variableStore: VariableStore, arrayStore: ArrayStore): Boolean =
        try {
            evaluateConditionInternal(cond, variableStore, arrayStore)
        } catch (e: Exception) {
            false
        }

    private fun evaluateConditionInternal(cond: String, store: VariableStore, arrays: ArrayStore): Boolean {
        // Simple comparison operators: ==, !=, <, >, <=, >=
        return when {
            cond.contains("==") -> {
                val (left, right) = cond.split("==", limit = 2).map { it.trim() }
                expandInternal(left, store, arrays) == expandInternal(right, store, arrays)
            }
            cond.contains("!=") -> {
                val (left, right) = cond.split("!=", limit = 2).map { it.trim() }
                expandInternal(left, store, arrays) != expandInternal(right, store, arrays)
            }
            cond.contains("<=") -> {
                val (left, right) = cond.split("<=", limit = 2).map { it.trim() }
                val l = expandInternal(left, store, arrays).toDoubleOrNull() ?: return false
                val r = expandInternal(right, store, arrays).toDoubleOrNull() ?: return false
                l <= r
            }
            cond.contains(">=") -> {
                val (left, right) = cond.split(">=", limit = 2).map { it.trim() }
                val l = expandInternal(left, store, arrays).toDoubleOrNull() ?: return false
                val r = expandInternal(right, store, arrays).toDoubleOrNull() ?: return false
                l >= r
            }
            cond.contains("<") -> {
                val (left, right) = cond.split("<", limit = 2).map { it.trim() }
                val l = expandInternal(left, store, arrays).toDoubleOrNull() ?: return false
                val r = expandInternal(right, store, arrays).toDoubleOrNull() ?: return false
                l < r
            }
            cond.contains(">") -> {
                val (left, right) = cond.split(">", limit = 2).map { it.trim() }
                val l = expandInternal(left, store, arrays).toDoubleOrNull() ?: return false
                val r = expandInternal(right, store, arrays).toDoubleOrNull() ?: return false
                l > r
            }
            else -> cond.toBoolean()
        }
    }

    private fun evaluateJsonPath(json: String, path: String): String {
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

    private fun isVariableNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun isJsonPathSegmentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    private data class TokenExpansion(
        val value: String,
        val nextIndex: Int,
    )

    companion object {
        private val TERNARY_PATTERN = Regex("""^\(([^)]+)\)\s*\?\s*([^:]+)\s*:\s*(.+)$""")
        private const val MAX_REGEX_LENGTH = 256
        private const val MAX_REGEX_INPUT_LENGTH = 10_000
    }
}

/**
 * Array storage for list variables.
 * Arrays are accessed as %list(#) for count, %list(1) for index, %list() for join.
 */
class ArrayStore {
    private val arrays = mutableMapOf<String, List<String>>()

    fun put(name: String, values: List<String>) {
        arrays[name] = values
    }

    fun get(name: String, index: Int): String {
        return arrays[name]?.getOrNull(index) ?: ""
    }

    fun length(name: String): Int {
        return arrays[name]?.size ?: 0
    }

    fun contains(name: String): Boolean {
        return arrays.containsKey(name)
    }

    fun join(name: String, delimiter: String): String {
        return arrays[name]?.joinToString(delimiter) ?: ""
    }

    fun joinWith(name: String, delimiter: String): String {
        return arrays[name]?.joinToString(delimiter) ?: ""
    }

    fun snapshot(): Map<String, List<String>> =
        arrays.mapValues { (_, values) -> values.toList() }
}
