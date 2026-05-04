package com.opentasker.core.engine.variables

import com.opentasker.core.engine.VariableStore
import java.util.regex.Pattern
import kotlin.math.floor

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
     * - "5" → "5"
     * - "%VAR" → value of VAR
     * - "%VAR(+10)" → parse VAR as number, add 10
     * - "%VAR(upper)" → uppercase VAR
     * - "%VAR(regex:(\d+):1)" → extract first digit group from VAR
     * - "%VAR(split:,)" → split VAR by comma, return array
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
            val result = evaluateCondition(condition, variableStore, arrayStore)
            return if (result) trueVal else falseVal
        }

        // Handle array access: %list(#), %list(1), %list()
        if (expr.startsWith('%') && '(' in expr) {
            val varMatch = VAR_WITH_OP_PATTERN.find(expr)
            if (varMatch != null) {
                val (name, op) = varMatch.destructured
                return evaluateVarOp(name, op, variableStore, arrayStore)
            }
        }

        // Handle JSON path: %json.path.to.field
        if (expr.startsWith("%json.")) {
            val path = expr.substring(6)
            return evaluateJsonPath(variableStore.get("json") ?: "{}", path)
        }

        // Simple variable expansion
        if (expr.startsWith('%')) {
            val name = expr.substring(1).takeWhile { it.isLetterOrDigit() || it == '_' }
            return variableStore.get(name) ?: ""
        }

        // Literal
        return expr
    }

    private fun evaluateVarOp(name: String, op: String, store: VariableStore, arrays: ArrayStore): String {
        val baseValue = store.get(name) ?: return ""

        // Array operations
        if (name.endsWith("()")) {
            val arrayName = name.dropLast(2)
            return when {
                op == "#" -> (arrays.length(arrayName)).toString()
                op.toIntOrNull() != null -> arrays.get(arrayName, op.toInt())
                op.isEmpty() -> arrays.join(arrayName, "")
                else -> arrays.joinWith(arrayName, op)
            }
        }

        // Math operations
        val numValue = baseValue.toDoubleOrNull()
        if (numValue != null) {
            val result = when {
                op.startsWith("+") -> numValue + (op.substring(1).toDoubleOrNull() ?: 0.0)
                op.startsWith("-") -> numValue - (op.substring(1).toDoubleOrNull() ?: 0.0)
                op.startsWith("*") -> numValue * (op.substring(1).toDoubleOrNull() ?: 1.0)
                op.startsWith("/") -> {
                    val divisor = op.substring(1).toDoubleOrNull() ?: 1.0
                    if (divisor != 0.0) numValue / divisor else 0.0
                }
                op == "//" -> floor(numValue)
                op == "/round" -> numValue.toLong().toDouble()
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
                baseValue.substring(start.coerceIn(0, baseValue.length), end.coerceIn(0, baseValue.length))
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
                val parts = op.substring(6).split(":")
                val pattern = parts.getOrNull(0) ?: ""
                val groupIdx = parts.getOrNull(1)?.toIntOrNull() ?: 0
                try {
                    val regex = Pattern.compile(pattern)
                    val matcher = regex.matcher(baseValue)
                    if (matcher.find()) matcher.group(groupIdx) else ""
                } catch (e: Exception) {
                    ""
                }
            }
            op.startsWith("replace:") -> {
                val parts = op.substring(8).split(":", limit = 2)
                val pattern = parts.getOrNull(0) ?: ""
                val replacement = parts.getOrNull(1) ?: ""
                try {
                    baseValue.replace(Regex(pattern), replacement)
                } catch (e: Exception) {
                    baseValue
                }
            }
            else -> baseValue
        }
    }

    private fun evaluateCondition(cond: String, store: VariableStore, arrays: ArrayStore): Boolean {
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
        // Simplified JSON path evaluation: json.field.subfield
        // For now, just handle top-level keys
        try {
            val keys = path.split(".")
            var current = json
            for (key in keys) {
                val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
                val match = Regex(pattern).find(current)
                if (match != null) {
                    current = match.groupValues[1]
                } else {
                    return ""
                }
            }
            return current
        } catch (e: Exception) {
            return ""
        }
    }

    companion object {
        private val TERNARY_PATTERN = Regex("""^\(([^)]+)\)\s*\?\s*([^:]+)\s*:\s*(.+)$""")
        private val VAR_WITH_OP_PATTERN = Regex("""^%(\w+)\(([^)]*)\)$""")
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

    fun join(name: String, delimiter: String): String {
        return arrays[name]?.joinToString(delimiter) ?: ""
    }

    fun joinWith(name: String, delimiter: String): String {
        return arrays[name]?.joinToString(delimiter) ?: ""
    }
}
