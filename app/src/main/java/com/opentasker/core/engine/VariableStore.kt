package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.expressions.TemplateScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 *   - Linear-time regex: %VAR(regex:pattern:group), %VAR(replace:pattern:replacement)
 *   - Arrays: %list(#), %list(1), %list()
 *   - JSON: %json.path.to.field
 */
class VariableStore {
    private val globals = ConcurrentHashMap<String, String>()
    private val localStack = java.util.Collections.synchronizedList(mutableListOf<MutableMap<String, String>>())
    private val expander = VariableExpander()
    private val arrayStore = ArrayStore()

    fun pushScope() { localStack.add(java.util.concurrent.ConcurrentHashMap()) }
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

    /**
     * Set a value at a nested JSON path within an existing variable.
     *
     * `fullPath` is parsed as `base.key1.key2` or `base[0]` or `base.key[0].nested`.
     * If no selectors are found, this falls through to a flat [set].
     * If the base variable does not exist or is not valid JSON, a new JSON structure is created.
     *
     * Returns true if the write succeeded, false if the path is unparseable.
     */
    fun setAtPath(fullPath: String, value: String): Boolean {
        val parsed = parsePathSelectors(fullPath) ?: return false
        if (parsed.selectors.isEmpty()) {
            set(parsed.base, value)
            return true
        }

        val current = get(parsed.base)
        val root: JsonElement = if (current != null) {
            try { jsonCodec.parseToJsonElement(current) } catch (_: Exception) { JsonObject(emptyMap()) }
        } else {
            JsonObject(emptyMap())
        }

        val updated = setInJson(root, parsed.selectors, JsonPrimitive(value)) ?: return false
        set(parsed.base, updated.toString())
        return true
    }

    /**
     * Set a value at a nested path within an array variable.
     *
     * `fullPath` is `arrayName[index]`. Sets the element at the given index,
     * growing the array with empty strings if needed.
     *
     * Returns true if the write succeeded.
     */
    fun setArrayAtIndex(name: String, index: Int, value: String): Boolean {
        val items = arrayStore.snapshot()[name]?.toMutableList() ?: mutableListOf()
        while (items.size <= index) items.add("")
        items[index] = value
        arrayStore.put(name, items)
        return true
    }

    private fun setInJson(
        element: JsonElement,
        selectors: List<PathSelector>,
        value: JsonElement,
    ): JsonElement? {
        if (selectors.isEmpty()) return value
        val head = selectors.first()
        val tail = selectors.drop(1)

        return when (head) {
            is PathSelector.Property -> {
                val obj = (element as? JsonObject) ?: JsonObject(emptyMap())
                val child = obj[head.name] ?: JsonObject(emptyMap())
                val updated = setInJson(child, tail, value) ?: return null
                buildJsonObject {
                    obj.forEach { (k, v) -> put(k, v) }
                    put(head.name, updated)
                }
            }
            is PathSelector.Index -> {
                val arr = (element as? JsonArray) ?: JsonArray(emptyList())
                val items = arr.toMutableList()
                while (items.size <= head.index) items.add(JsonPrimitive(""))
                val child = items[head.index]
                val updated = setInJson(child, tail, value) ?: return null
                items[head.index] = updated
                buildJsonArray { items.forEach(::add) }
            }
        }
    }

    private fun parsePathSelectors(fullPath: String): ParsedPath? {
        if (fullPath.isBlank()) return null
        var cursor = 0
        while (cursor < fullPath.length && isPathBaseChar(fullPath[cursor])) cursor++
        if (cursor == 0) return null
        val base = fullPath.substring(0, cursor)
        val selectors = mutableListOf<PathSelector>()

        while (cursor < fullPath.length) {
            when (fullPath[cursor]) {
                '.' -> {
                    cursor++
                    val start = cursor
                    while (cursor < fullPath.length && isPathBaseChar(fullPath[cursor])) cursor++
                    if (cursor == start) return null
                    selectors += PathSelector.Property(fullPath.substring(start, cursor))
                }
                '[' -> {
                    val close = fullPath.indexOf(']', startIndex = cursor + 1)
                    if (close == -1) return null
                    val body = fullPath.substring(cursor + 1, close).trim()
                    val index = body.toIntOrNull() ?: return null
                    if (index < 0) return null
                    selectors += PathSelector.Index(index)
                    cursor = close + 1
                }
                else -> return null
            }
        }
        return ParsedPath(base, selectors)
    }

    private fun isPathBaseChar(char: Char): Boolean =
        char.isLetterOrDigit() || char == '_' || char == '-'

    private fun isGlobalName(name: String): Boolean =
        name.isNotEmpty() && name[0].isUpperCase()

    private sealed interface PathSelector {
        data class Property(val name: String) : PathSelector
        data class Index(val index: Int) : PathSelector
    }

    private data class ParsedPath(
        val base: String,
        val selectors: List<PathSelector>,
    )

    companion object {
        private val jsonCodec = Json { ignoreUnknownKeys = true }
    }
}
