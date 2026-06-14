package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.expressions.TemplateScope
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory variable store with a global scope and stack of local scopes.
 * Thread-safe for concurrent read/write access.
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
    private val localStack = java.util.Collections.synchronizedList(mutableListOf<MutableMap<String, String>>())
    private val expander = VariableExpander()
    private val arrayStore = ArrayStore()

    fun pushScope() { localStack.add(mutableMapOf()) }
    fun popScope() { 
        synchronized(localStack) {
            if (localStack.isNotEmpty()) localStack.removeAt(localStack.size - 1)
        }
    }

    fun set(name: String, value: String) {
        if (isGlobalName(name)) globals[name] = value
        else {
            synchronized(localStack) {
                (localStack.lastOrNull() ?: globals)[name] = value
            }
        }
    }

    fun get(name: String): String? {
        synchronized(localStack) {
            for (i in localStack.indices.reversed()) {
                localStack[i][name]?.let { return it }
            }
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

    /**
     * Returns the elements of a stored array by name, or null if no array with that name exists.
     * Used by the `flow.foreach` control action to iterate over array variables.
     */
    fun getArrayItems(name: String): List<String>? =
        arrayStore.snapshot()[name]

    /** Expand all variable references in [s] using the current scope chain. */
    fun expand(s: String): String {
        return expander.expand(s, this, arrayStore)
    }

    /**
     * Expand with operator support. Examples:
     * - "%VAR(+5)" → parse VAR as number, add 5
     * - "%VAR(upper)" → uppercase VAR
     * - "%VAR(regex:(\d+):1)" → extract first digit group
     * - "(x > 5) ? yes : no" → conditional
     */
    fun expandWithOperators(expr: String): String {
        return expand(expr)
    }

    fun evaluateCondition(expr: String): Boolean {
        return expander.evaluateCondition(expr, this, arrayStore)
    }

    fun toTemplateScope(event: Map<String, String> = emptyMap()): TemplateScope {
        val taskValues = mutableMapOf<String, String>()
        synchronized(localStack) {
            localStack.forEach { scope -> taskValues += scope }
        }
        return TemplateScope(
            global = globals.toMap(),
            task = taskValues.toMap(),
            event = event.toMap(),
            arrays = arrayStore.snapshot(),
        )
    }

    private fun isGlobalName(name: String): Boolean =
        name.isNotEmpty() && name[0].isUpperCase()
}
