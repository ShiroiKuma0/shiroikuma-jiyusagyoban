package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.VariableExpander
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory variable store with a global scope and stack of local scopes.
 *
 * Naming convention (matches Tasker):
 *   - %UPPERCASE   → global, persistent
 *   - %lowercase   → local to current task invocation
 *
 * Enhanced with operator support:
 *   - Math: %VAR(+5), %VAR(*2), %VAR(//), %VAR(/round)
 *   - Strings: %VAR(upper), %VAR(lower), %VAR(trim), %VAR(substring:0:5)
 *   - Regex: %VAR(regex:pattern:group), %VAR(replace:pattern:replacement)
 *   - Arrays: %list(#), %list(1), %list()
 *   - JSON: %json.path.to.field
 */
class VariableStore {
    private val globals = ConcurrentHashMap<String, String>()
    private val localStack = ArrayDeque<MutableMap<String, String>>()
    private val expander = VariableExpander()
    private val arrayStore = ArrayStore()

    fun pushScope() { localStack.addLast(mutableMapOf()) }
    fun popScope() { if (localStack.isNotEmpty()) localStack.removeLast() }

    fun set(name: String, value: String) {
        if (isGlobalName(name)) globals[name] = value
        else (localStack.lastOrNull() ?: globals)[name] = value
    }

    fun get(name: String): String? {
        for (i in localStack.indices.reversed()) {
            localStack[i][name]?.let { return it }
        }
        return globals[name]
    }

    /**
     * Store an array in the array storage.
     * Arrays can be accessed via %arrayName(#) for length, %arrayName(0) for index, etc.
     */
    fun setArray(name: String, values: List<String>) {
        arrayStore.put(name, values)
    }

    /** Expand all %var references in [s] using the current scope chain. */
    fun expand(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 1 < s.length && s[i + 1].isLetter()) {
                var j = i + 1
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                val name = s.substring(i + 1, j)
                out.append(get(name) ?: "")
                i = j
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /**
     * Expand with operator support. Examples:
     * - "%VAR(+5)" → parse VAR as number, add 5
     * - "%VAR(upper)" → uppercase VAR
     * - "%VAR(regex:(\d+):1)" → extract first digit group
     * - "(x > 5) ? yes : no" → conditional
     */
    fun expandWithOperators(expr: String): String {
        return expander.expand(expr, this, arrayStore)
    }

    private fun isGlobalName(name: String): Boolean =
        name.isNotEmpty() && name[0].isUpperCase()
}
