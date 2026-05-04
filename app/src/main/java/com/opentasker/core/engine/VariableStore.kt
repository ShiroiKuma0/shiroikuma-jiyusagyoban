package com.opentasker.core.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory variable store with a global scope and stack of local scopes.
 *
 * Naming convention (matches Tasker):
 *   - %UPPERCASE   → global, persistent
 *   - %lowercase   → local to current task invocation
 */
class VariableStore {
    private val globals = ConcurrentHashMap<String, String>()
    private val localStack = ArrayDeque<MutableMap<String, String>>()

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

    private fun isGlobalName(name: String): Boolean =
        name.isNotEmpty() && name[0].isUpperCase()
}
