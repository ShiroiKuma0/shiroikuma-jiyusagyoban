package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.GlobalVariableScope
import com.opentasker.core.engine.variables.InMemoryGlobalScope
import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import java.util.concurrent.ConcurrentHashMap

/**
 * Variable store for one task execution. Three scopes, chosen by the name's casing:
 *   - `%ALLCAPS`   → **super-global**: persistent, app-wide ([GlobalVariableScope] bucket 0).
 *   - `%MixedCase` → **project-global**: persistent, owned by the running task's [projectId]
 *                    (an Unfiled task — projectId 0 — falls back to the super bucket, so nothing breaks).
 *   - `%lowercase` → **task-local**: ephemeral, lives only for this execution (local stack / base scope).
 *
 * Persistent scopes are delegated to a shared [GlobalVariableScope] (the DB-backed singleton at runtime),
 * so globals survive across runs; only the local scopes are per-store. Thread-safe.
 *
 * Enhanced with operator support:
 *   - Math: %VAR(+5), %VAR(*2), %VAR(//), %VAR(/round)
 *   - Strings: %VAR(upper), %VAR(lower), %VAR(trim), %VAR(substring:0:5)
 *   - Linear-time regex: %VAR(regex:pattern:group), %VAR(replace:pattern:replacement)
 *   - Arrays: %list(#), %list(1), %list()
 *   - JSON: %json.path.to.field
 */
class VariableStore private constructor(
    private val globalScope: GlobalVariableScope,
    private val projectId: Long,            // 0 = Unfiled/super; >0 = the running task's project
    private val arrayStore: ArrayStore,
) {
    /** Standalone store with no persistence (ad-hoc / unit tests). */
    constructor() : this(InMemoryGlobalScope(), SUPER_GLOBAL_PROJECT_ID, ArrayStore())

    /** Store for a task run under [taskProjectId] (null = Unfiled → super scope), sharing [globalScope]. */
    constructor(globalScope: GlobalVariableScope, taskProjectId: Long?) :
        this(globalScope, taskProjectId ?: SUPER_GLOBAL_PROJECT_ID, ArrayStore())

    // Bottom ephemeral scope: holds `%lowercase` vars set before any scope is pushed.
    private val baseScope = ConcurrentHashMap<String, String>()
    private val localStack = java.util.Collections.synchronizedList(mutableListOf<MutableMap<String, String>>())
    private val expander = VariableExpander()

    /**
     * A store for a called sub-task: shares the persistent [globalScope] and the [arrayStore], but
     * starts with fresh local scopes (so `%lowercase` locals stay isolated). [childProjectId] is the
     * sub-task's own project, so its `%MixedCase` vars resolve to that project's bucket.
     */
    fun childScope(childProjectId: Long?): VariableStore =
        VariableStore(globalScope, childProjectId ?: SUPER_GLOBAL_PROJECT_ID, arrayStore)

    fun pushScope() { localStack.add(java.util.concurrent.ConcurrentHashMap()) }
    fun popScope() { 
        synchronized(localStack) {
            if (localStack.isNotEmpty()) localStack.removeAt(localStack.size - 1)
        }
    }

    /** The persistent bucket for a name, or null if the name is task-local (`%lowercase`). */
    private fun bucketOf(name: String): Long? {
        if (name.isEmpty() || !name[0].isUpperCase()) return null            // local
        val allCaps = name.none { it.isLetter() && it.isLowerCase() }
        return if (allCaps) SUPER_GLOBAL_PROJECT_ID else projectId           // super vs project
    }

    fun set(name: String, value: String) {
        val bucket = bucketOf(name)
        if (bucket == null) {
            synchronized(localStack) { (localStack.lastOrNull() ?: baseScope)[name] = value }
        } else {
            globalScope.set(bucket, name, value)
        }
    }

    /**
     * Force a value into the task-local scope regardless of the name's casing. Used to inject an
     * event's own snapshot (e.g. a notification's `%NOTIF_*`) for this one invocation, so a queued
     * task reads ITS event's values — [get] checks locals first, shadowing the shared super-global.
     */
    fun setLocal(name: String, value: String) {
        synchronized(localStack) { (localStack.lastOrNull() ?: baseScope)[name] = value }
    }

    fun get(name: String): String? {
        synchronized(localStack) {
            for (i in localStack.indices.reversed()) {
                localStack[i][name]?.let { return it }
            }
        }
        baseScope[name]?.let { return it }
        val bucket = bucketOf(name) ?: return null
        return globalScope.get(bucket, name)
    }

    /** Unset a variable in whichever scope owns it and drop any array of the same name (Variable Clear). */
    fun unset(name: String) {
        baseScope.remove(name)
        synchronized(localStack) { localStack.forEach { it.remove(name) } }
        bucketOf(name)?.let { globalScope.unset(it, name) }
        arrayStore.remove(name)
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

    fun toTemplateScope(
        event: Map<String, String> = emptyMap(),
        param: Map<String, String> = emptyMap(),
    ): TemplateScope {
        val taskValues = LinkedHashMap<String, String>()
        taskValues.putAll(baseScope)
        synchronized(localStack) { localStack.forEach { scope -> taskValues += scope } }
        return TemplateScope(
            global = globalScope.snapshot(projectId),
            task = taskValues,
            event = event.toMap(),
            param = param.toMap(),
            arrays = arrayStore.snapshot(),
        )
    }
}
