package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.GlobalVariableScope
import com.opentasker.core.engine.variables.TaskerBuiltIns
import com.opentasker.core.engine.variables.InMemoryGlobalScope
import com.opentasker.core.engine.variables.VariableExpander
import com.opentasker.core.expressions.InertTemplateText
import com.opentasker.core.expressions.TemplateExpressionEngine
import com.opentasker.core.expressions.TemplateScope
import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class TrackedExpansion(
    /** Ready for the template engine: a `{{` that came out of a variable's value stays inert. */
    val value: String,
    val isSecretDerived: Boolean,
) {
    /** The finished text, when no template stage follows. */
    val text: String get() = InertTemplateText.restore(value)
}

/**
 * Variable store for one task execution. Three scopes, chosen by the name's casing:
 *   - `%ALLCAPS`   → **super-global**: persistent, app-wide ([GlobalVariableScope] bucket 0).
 *   - `%MixedCase` → **project-global**: persistent, owned by the running task's [projectId]
 *                    (an Unfiled task — projectId 0 — falls back to the super bucket, so nothing breaks).
 *   - `%lowercase` → **task-local**: ephemeral, lives only for this execution (local stack / base scope).
 *
 * Non-ASCII names (e.g. Japanese) have no uppercase first letter, so they are task-local — do NOT
 * gate names through [com.opentasker.core.model.VariableNamePolicy] here; its ASCII-only pattern
 * would silently reject them.
 *
 * Persistent scopes are delegated to a shared [GlobalVariableScope] (the DB-backed singleton at runtime),
 * so globals survive across runs; only the local scopes are per-store. Thread-safe.
 *
 * Secret provenance (upstream): values written under [withSensitiveWrites], flagged via
 * [set]'s `sensitive` parameter, or seeded via [seedGlobals]' `secretNames` are tracked so
 * expansions and the template scope can mask them.
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
    /** 0 = Unfiled/super; >0 = the running task's project. Exposed so actions can resolve
     *  project-scoped references (e.g. a scene by its `(project, name)` key). */
    val projectId: Long,
    private val arrayStore: ArrayStore,
    // Global-name sensitivity is shared with child scopes (the underlying global values are too).
    private val globalSensitiveNames: MutableSet<String>,
    private val declaredSecretGlobals: MutableSet<String>,
    private val sensitiveArrayNames: MutableSet<String>,
) {
    /** Standalone store with no persistence (ad-hoc / unit tests). */
    constructor() : this(
        InMemoryGlobalScope(), SUPER_GLOBAL_PROJECT_ID, ArrayStore(),
        ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
    )

    /** Store for a task run under [taskProjectId] (null = Unfiled → super scope), sharing [globalScope]. */
    constructor(globalScope: GlobalVariableScope, taskProjectId: Long?) : this(
        globalScope, taskProjectId ?: SUPER_GLOBAL_PROJECT_ID, ArrayStore(),
        ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet(),
    )

    // Bottom ephemeral scope: holds `%lowercase` vars set before any scope is pushed.
    private val baseScope = ConcurrentHashMap<String, String>()
    private val localStack = java.util.Collections.synchronizedList(mutableListOf<MutableMap<String, String>>())
    private val baseScopeSensitiveNames = ConcurrentHashMap.newKeySet<String>()
    private val localSensitiveStack = java.util.Collections.synchronizedList(mutableListOf<MutableSet<String>>())
    /** Parallel to [localStack]: true for a scope that holds a sub-task's inputs. */
    private val inputScopes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    /** Parallel to [localStack]: names [clearForTask] hid there, so a caller's value doesn't show through. */
    private val hiddenStack = java.util.Collections.synchronizedList(mutableListOf<MutableSet<String>>())
    private val sensitiveWriteDepth = AtomicInteger(0)
    private val expander = VariableExpander()

    /**
     * A store for a called sub-task: shares the persistent [globalScope] and the [arrayStore], but
     * starts with fresh local scopes (so `%lowercase` locals stay isolated). [childProjectId] is the
     * sub-task's own project, so its `%MixedCase` vars resolve to that project's bucket.
     */
    fun childScope(childProjectId: Long?): VariableStore =
        VariableStore(
            globalScope, childProjectId ?: SUPER_GLOBAL_PROJECT_ID, arrayStore,
            globalSensitiveNames, declaredSecretGlobals, sensitiveArrayNames,
        )

    /** [holdsInputs] marks the scope Perform Task puts a sub-task's inputs (`%par1`, ...) in. */
    fun pushScope(holdsInputs: Boolean = false) {
        synchronized(localStack) {
            localStack.add(java.util.concurrent.ConcurrentHashMap())
            localSensitiveStack.add(ConcurrentHashMap.newKeySet())
            inputScopes.add(holdsInputs)
            hiddenStack.add(ConcurrentHashMap.newKeySet())
        }
    }
    fun popScope() {
        synchronized(localStack) {
            if (localStack.isNotEmpty()) {
                localStack.removeAt(localStack.size - 1)
                localSensitiveStack.removeAt(localSensitiveStack.size - 1)
                inputScopes.removeAt(inputScopes.size - 1)
                hiddenStack.removeAt(hiddenStack.size - 1)
            }
        }
    }

    /** The persistent bucket for a name, or null if the name is task-local (`%lowercase`). */
    private fun bucketOf(name: String): Long? {
        if (name.isEmpty() || !name[0].isUpperCase()) return null            // local
        val allCaps = name.none { it.isLetter() && it.isLowerCase() }
        return if (allCaps) SUPER_GLOBAL_PROJECT_ID else projectId           // super vs project
    }

    /** A "project-scoped" name — MixedCase (uppercase-initial with ≥1 lowercase letter). Such a name is
     *  routed to the running project by [bucketOf]; it must never persist in the super bucket. */
    private fun isProjectScopedName(name: String): Boolean =
        name.isNotEmpty() && name[0].isUpperCase() && name.any { it.isLowerCase() }

    /**
     * Whether [set] would actually persist this name, rather than quietly keeping it task-local.
     *
     * Two names do not persist: an all-lowercase one (task-local by definition), and a MixedCase one in
     * an **unfiled** task — [set]'s guard keeps that local rather than writing a dead shadow-copy into
     * the super bucket. Exposed so a caller can tell the difference; `var.persist` deliberately does
     * NOT refuse on it, because the value still resolves for the rest of the run.
     */
    fun persistsGlobally(name: String): Boolean {
        val bucket = bucketOf(name) ?: return false
        return !(bucket == SUPER_GLOBAL_PROJECT_ID && isProjectScopedName(name))
    }

    fun set(name: String, value: String, sensitive: Boolean = false) {
        val shouldRemainSensitive = sensitive || sensitiveWriteDepth.get() > 0 || isSensitive(name)
        val bucket = bucketOf(name)
        // Invariant: a project-scoped (MixedCase) name has no home in the super bucket. Set outside any
        // project (projectId 0 — an unfiled task), it would become a dead shadow-copy of the real
        // project-global, so keep it task-local instead of promoting it to super. ALL-CAPS super-globals
        // and in-project MixedCase sets are unaffected. (Guard #1 of the "no MixedCase in super" invariant.)
        if (bucket == null || (bucket == SUPER_GLOBAL_PROJECT_ID && isProjectScopedName(name))) {
            setInLocalScope(name, value, shouldRemainSensitive)
            return
        }
        // The sensitivity of a global is a read-modify-write, and it MUST be atomic. Two profile runs
        // racing the same name — one marking it secret, one overwriting it plainly — used to lose the
        // flag: the plain writer sampled `isSensitive` (above) before the secret writer set it, then
        // cleared it on the way out. A cleared flag means the value is persisted and exported in
        // plaintext, so this re-reads the set INSIDE the lock and only ever clears when nobody has
        // marked it. Sensitivity is monotonic within a run: plain writes never undo a secret one.
        synchronized(globalSensitiveLock) {
            val sensitiveNow = shouldRemainSensitive ||
                name in globalSensitiveNames ||
                name in declaredSecretGlobals
            globalScope.set(bucket, name, value)
            updateSensitivity(globalSensitiveNames, name, sensitiveNow)
        }
    }

    /** Guards the check-then-act on [globalSensitiveNames]; see the note in [set]. */
    private val globalSensitiveLock = Any()

    /**
     * Force a value into the task-local scope regardless of the name's casing. Used to inject an
     * event's own snapshot (e.g. a notification's `%NOTIF_*`) for this one invocation, so a queued
     * task reads ITS event's values — [get] checks locals first, shadowing the shared super-global.
     */
    fun setLocal(name: String, value: String) {
        setInLocalScope(name, value, sensitiveWriteDepth.get() > 0)
    }

    private fun setInLocalScope(name: String, value: String, sensitive: Boolean) {
        synchronized(localStack) {
            val target = localStack.lastOrNull()
            if (target == null) {
                baseScope[name] = value
                updateSensitivity(baseScopeSensitiveNames, name, sensitive)
            } else {
                target[name] = value
                updateSensitivity(localSensitiveStack.last(), name, sensitive)
            }
        }
    }

    fun get(name: String): String? {
        synchronized(localStack) {
            for (i in localStack.indices.reversed()) {
                localStack[i][name]?.let { return it }
                if (name in hiddenStack[i]) return TaskerBuiltIns.value(name)
            }
        }
        // A Tasker built-in fills in when nothing is stored under its name, or only an empty value
        // is (a Variable Set to nothing).
        val stored = baseScope[name] ?: bucketOf(name)?.let { globalScope.get(it, name) }
        return if (stored.isNullOrEmpty()) TaskerBuiltIns.value(name) ?: stored else stored
    }

    /** The name as the store keys it: the fork keeps names as written, minus a leading `%`. */
    private fun bare(name: String): String = name.trim().removePrefix("%")

    /**
     * Unsets [name] in the scope [get] reads it from, so it reads as never set. Returns whether it
     * was set. A global's taint stays, as it does in [set]. (Upstream's `remove`, on the fork's
     * bucketed globals.)
     */
    fun remove(name: String): Boolean {
        val n = bare(name)
        if (n.isEmpty()) return false
        synchronized(localStack) {
            for (index in localStack.indices.reversed()) {
                if (localStack[index].remove(n) != null) {
                    localSensitiveStack[index] -= n
                    return true
                }
                if (n in hiddenStack[index]) return false
            }
            if (baseScope.remove(n) != null) {
                baseScopeSensitiveNames -= n
                return true
            }
        }
        val bucket = bucketOf(n) ?: return false
        if (globalScope.get(bucket, n) == null) return false
        globalScope.unset(bucket, n)
        return true
    }

    /**
     * Unsets [name] for the running task only, the way Tasker's Reset Return Variable clears it: a
     * local goes from that task's own scopes (the innermost input scope and every one above it, or
     * all of them in a task nothing called), and a caller's variable of that name stays as it is
     * but reads as unset here until the task ends. A global is unset everywhere, as in [remove].
     */
    fun clearForTask(name: String) {
        val n = bare(name)
        if (n.isEmpty()) return
        if (persistsGlobally(n)) {
            bucketOf(n)?.let { globalScope.unset(it, n) }
            return
        }
        synchronized(localStack) {
            val ownFrom = inputScopes.lastIndexOf(true)
            for (index in maxOf(ownFrom, 0) until localStack.size) {
                localStack[index].remove(n)
                localSensitiveStack[index] -= n
            }
            if (ownFrom < 0) {
                baseScope.remove(n)
                baseScopeSensitiveNames -= n
            } else {
                hiddenStack[ownFrom] += n
            }
        }
    }

    /**
     * Tasker's Variable Clear of a pattern, or of everything: unsets each variable the running task
     * sees whose name, written with its `%`, matches [pattern], or each one when [pattern] is null.
     * Locals go the way [clearForTask] clears them, globals only with [includeGlobals], and so do
     * the arrays [setArray] keeps under a matching name. Built-ins aren't stored, so they stay.
     * Returns how many.
     */
    fun clearMatching(pattern: String?, includeGlobals: Boolean): Int {
        fun matches(name: String) = pattern == null || expander.matches("%$name", pattern)
        val locals = synchronized(localStack) {
            visibleLocalNames().filter(::matches).onEach(::clearForTask)
        }
        val removedGlobals = if (includeGlobals) {
            globalScope.snapshot(projectId).keys.filter(::matches).filter(::remove)
        } else {
            emptyList()
        }
        val removedArrays = arrayStore.snapshot().keys
            .filter { (includeGlobals || bucketOf(it) == null) && matches(it) }
            .filter(arrayStore::remove)
        return (locals + removedGlobals + removedArrays).toSet().size
    }

    /**
     * Drops the array [setArray] keeps under [name], so its `%name(#)` reads 0, and returns whether
     * there was one. These arrays aren't scoped, so a sub-task drops its caller's.
     */
    fun removeArray(name: String): Boolean = arrayStore.remove(storedArrayName(name))

    /** The local names [get] finds a stored value for. Call it holding the [localStack] lock. */
    private fun visibleLocalNames(): Set<String> {
        val names = mutableSetOf<String>()
        val hidden = mutableSetOf<String>()
        for (index in localStack.indices.reversed()) {
            localStack[index].keys.filterTo(names) { it !in hidden }
            hidden += hiddenStack[index]
        }
        baseScope.keys.filterTo(names) { it !in hidden }
        return names
    }

    /**
     * Tasker's array elements: the variables named [base] plus a whole number from 1 (`arr1`,
     * `arr2`, ...), in the scope [base] belongs to, by index. Each one reads the way [get] reads it.
     */
    fun numberedItems(base: String): java.util.NavigableMap<Int, String> {
        val items = java.util.TreeMap<Int, String>()
        val b = bare(base)
        if (b.isEmpty()) return items
        val names = if (persistsGlobally(b)) {
            globalScope.snapshot(projectId).keys.toSet()
        } else {
            synchronized(localStack) { arrayScopes(b).flatMapTo(mutableSetOf()) { (values, _) -> values.keys } }
        }
        names.forEach { name ->
            val index = numberedIndex(b, name) ?: return@forEach
            get(name)?.let { items[index] = it }
        }
        return items
    }

    /**
     * Replaces [base]'s numbered elements with [items] (index to value and secret flag). The old
     * elements are unset in every local scope [arrayScopes] covers, and the new ones go into the
     * outermost of those scopes that held an element, else the one [set] writes to.
     */
    fun replaceNumberedItems(base: String, items: Map<Int, Pair<String, Boolean>>) {
        val b = bare(base)
        if (b.isEmpty()) return
        if (persistsGlobally(b)) {
            globalScope.snapshot(projectId).keys.filter { numberedIndex(b, it) != null }.forEach { remove(it) }
            items.forEach { (index, item) -> set("$b$index", item.first, sensitive = item.second) }
            return
        }
        val tainted = sensitiveWriteDepth.get() > 0
        synchronized(localStack) {
            val scopes = arrayScopes(b)
            val (values, sensitiveNames) = scopes.firstOrNull { (values, _) ->
                values.keys.any { numberedIndex(b, it) != null }
            } ?: scopes.last()
            scopes.forEach { (scopeValues, scopeSensitiveNames) ->
                scopeValues.keys.filter { numberedIndex(b, it) != null }.forEach { name ->
                    scopeValues.remove(name)
                    scopeSensitiveNames -= name
                }
            }
            items.forEach { (index, item) ->
                val name = "$b$index"
                values[name] = item.first
                if (item.second || tainted) sensitiveNames += name
            }
        }
    }

    /** True when any of [base]'s numbered elements holds a secret or a value derived from one. */
    fun anyNumberedItemSensitive(base: String): Boolean {
        val b = bare(base)
        if (b.isEmpty()) return false
        if (persistsGlobally(b)) return globalSensitiveNames.any { numberedIndex(b, it) != null }
        return synchronized(localStack) {
            arrayScopes(b).any { (_, sensitiveNames) -> sensitiveNames.any { numberedIndex(b, it) != null } }
        }
    }

    /**
     * The local scopes, outermost first, that hold the local array [base]: every one, unless a
     * sub-task was handed elements of it as inputs or [clearForTask] hid one of its elements there.
     * Then the array starts at the innermost such input scope. Call it holding the [localStack] lock.
     */
    private fun arrayScopes(base: String): List<Pair<MutableMap<String, String>, MutableSet<String>>> {
        val scopes = listOf<Pair<MutableMap<String, String>, MutableSet<String>>>(baseScope to baseScopeSensitiveNames) +
            localStack.zip(localSensitiveStack)
        val inputs = localStack.indices.lastOrNull { index ->
            inputScopes[index] && (
                base == INPUT_ARRAY ||
                    localStack[index].keys.any { numberedIndex(base, it) != null } ||
                    hiddenStack[index].any { numberedIndex(base, it) != null }
                )
        } ?: return scopes
        return scopes.drop(inputs + 1)
    }

    private fun numberedIndex(base: String, name: String): Int? {
        if (name.length <= base.length || !name.startsWith(base)) return null
        val digits = name.substring(base.length)
        if (digits.first() == '0' || !digits.all { it in '0'..'9' }) return null
        return digits.toIntOrNull()
    }

    fun isSensitive(name: String): Boolean {
        synchronized(localStack) {
            for (index in localStack.indices.reversed()) {
                if (name in localStack[index]) return name in localSensitiveStack[index]
                if (name in hiddenStack[index]) return false
            }
        }
        if (baseScope.containsKey(name)) return name in baseScopeSensitiveNames
        return name in globalSensitiveNames
    }

    /** Unset a variable in whichever scope owns it and drop any array of the same name (Variable Clear). */
    fun unset(name: String) {
        baseScope.remove(name)
        baseScopeSensitiveNames.remove(name)
        synchronized(localStack) {
            localStack.forEach { it.remove(name) }
            localSensitiveStack.forEach { it.remove(name) }
        }
        bucketOf(name)?.let { globalScope.unset(it, name) }
        globalSensitiveNames.remove(name)
        arrayStore.remove(name)
        sensitiveArrayNames.remove(name)
    }

    /**
     * Seed the global scope with previously persisted values before a run starts. With the fork's
     * DB-backed [GlobalVariableScope] the values themselves are already live; this records the
     * secret-provenance metadata (and tolerates in-memory scopes by writing missing values through).
     */
    fun seedGlobals(values: Map<String, String>, secretNames: Set<String> = emptySet()) {
        values.forEach { (name, value) ->
            val bucket = bucketOf(name) ?: return@forEach
            if (globalScope.get(bucket, name) == null) globalScope.set(bucket, name, value)
        }
        declaredSecretGlobals += secretNames
        globalSensitiveNames += secretNames
    }

    /** Snapshot of the current global scope (this store's visible buckets). */
    fun globalSnapshot(): Map<String, String> = globalScope.snapshot(projectId)

    /** Secret/taint metadata paired with [globalSnapshot] for encrypted durable persistence. */
    fun globalSensitiveSnapshot(): Set<String> = globalSensitiveNames.toSet()

    /**
     * Store an array in the array storage.
     * Arrays can be accessed via %arrayName(#) for length, %arrayName(0) for index, etc.
     */
    fun setArray(name: String, values: List<String>, sensitive: Boolean = false) {
        // Named the way [set] names it, so an output field written as %parts stores parts.
        val stored = storedArrayName(name)
        arrayStore.put(stored, values)
        updateSensitivity(
            sensitiveArrayNames,
            stored,
            sensitive || sensitiveWriteDepth.get() > 0 || stored in sensitiveArrayNames,
        )
    }

    /** The key [setArray] stores [name] under, so a lookup and its taint check find the same array. */
    private fun storedArrayName(name: String): String = bare(name).ifEmpty { name }

    /**
     * Empty every stored array whose name starts with [prefix].
     *
     * Arrays are shared between a task and the tasks it calls, and an action that publishes "one
     * array per field of each item" writes nothing at all when there are no items. Without this, the
     * arrays of an earlier call under the same prefix stay behind and a loop walks them again
     * (携帯, 2026-11-19: one chat's messages were handled a second time as the next chat's).
     */
    fun clearArraysWithPrefix(prefix: String) {
        if (prefix.isEmpty()) return
        for (name in arrayStore.snapshot().keys) {
            if (name.startsWith(prefix)) arrayStore.put(name, emptyList())
        }
    }

    /**
     * Returns the elements of a stored array by name, or else Tasker's numbered elements (`arr1`,
     * `arr2`, ...) in index order, or null when neither exists. Used by the `flow.foreach` control
     * action to iterate over array variables. [name] may carry its percent sign, as a For list does.
     */
    fun getArrayItems(name: String): List<String>? =
        arrayStore.snapshot()[storedArrayName(name)] ?: numberedItems(name).values.toList().ifEmpty { null }

    fun isArraySensitive(name: String): Boolean =
        storedArrayName(name) in sensitiveArrayNames || anyNumberedItemSensitive(name)

    /** Expand all variable references in [s] using the current scope chain. */
    fun expand(s: String): String {
        return expander.expand(s, this, arrayStore)
    }

    /**
     * Expands a legacy expression while retaining whether any referenced input was secret.
     * [conditionals] false keeps a leading `(cond) ? a : b` as text. [unset] says how an unset
     * `%name` reads.
     *
     * FORK: the default is BLANK — an unset variable reads as nothing, as it always has here. Upstream
     * moved to Tasker's "left as written" (2026-11-11); every `x%var == x` check in 白い熊's tasks
     * stands on the blank reading, so the fork keeps it.
     */
    fun expandTracked(
        s: String,
        conditionals: Boolean = true,
        unset: VariableExpander.Unset = VariableExpander.Unset.BLANK,
    ): TrackedExpansion = TrackedExpansion(
        value = expander.expand(s, this, arrayStore, InertTemplateText::hide, conditionals, unset),
        isSecretDerived = variableReference.findAll(s).any { match ->
            val name = match.groupValues[1]
            isSensitive(name) || name in sensitiveArrayNames ||
                (s.startsWith("(", match.range.last + 1) && anyNumberedItemSensitive(name))
        },
    )

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

    /**
     * Evaluates a condition the way a run does: its structure is read from [expr] as typed, then
     * each operand goes through legacy `%var` expansion and the bounded `{{ ... }}` stage once.
     * Pass [expr] unexpanded, because a value that has already been substituted would be parsed
     * as part of the condition.
     */
    fun evaluateCondition(
        expr: String,
        templates: TemplateExpressionEngine,
        eventVariables: Map<String, String> = emptyMap(),
        parameters: Map<String, String> = emptyMap(),
    ): Boolean {
        val scope by lazy(LazyThreadSafetyMode.NONE) { toTemplateScope(eventVariables, parameters) }
        // A template the engine can't read fails the whole condition, whichever branch holds it.
        if (expr.contains("{{") && templates.expand(expandTracked(expr).value, scope).warnings.isNotEmpty()) {
            return false
        }
        return expander.evaluateCondition(expr, this, arrayStore) { operand, unset ->
            val legacy = expandTracked(operand, unset = unset)
            if (!legacy.value.contains("{{")) {
                legacy.text
            } else {
                InertTemplateText.restore(templates.expand(legacy.value, scope).value)
            }
        }
    }

    suspend fun <T> withSensitiveWrites(sensitive: Boolean, block: suspend () -> T): T {
        if (!sensitive) return block()
        sensitiveWriteDepth.incrementAndGet()
        return try {
            block()
        } finally {
            sensitiveWriteDepth.decrementAndGet()
        }
    }

    fun toTemplateScope(
        event: Map<String, String> = emptyMap(),
        param: Map<String, String> = emptyMap(),
    ): TemplateScope {
        val taskValues = LinkedHashMap<String, String>()
        taskValues.putAll(baseScope)
        synchronized(localStack) {
            localStack.forEachIndexed { index, scope ->
                hiddenStack[index].forEach(taskValues::remove)
                taskValues += scope
            }
        }
        return TemplateScope(
            global = globalScope.snapshot(projectId),
            task = taskValues,
            event = event.toMap(),
            param = param.toMap(),
            arrays = arrayStore.snapshot(),
            sensitiveGlobal = globalSensitiveNames.toSet(),
            sensitiveTask = synchronized(localStack) {
                localSensitiveStack.flatMapTo(baseScopeSensitiveNames.toMutableSet()) { it }
            },
            sensitiveArrays = sensitiveArrayNames.toSet(),
            sensitiveEvent = event.keys.filterTo(mutableSetOf(), EventTaskVariables::isSensitive),
            builtIns = TaskerBuiltIns::value,
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
     * growing the array with empty strings if needed. Out-of-range indices
     * (negative or above [MAX_ARRAY_INDEX]) fail closed.
     *
     * Returns true if the write succeeded.
     */
    fun setArrayAtIndex(name: String, index: Int, value: String): Boolean {
        if (index < 0 || index > MAX_ARRAY_INDEX) return false
        val items = arrayStore.snapshot()[name]?.toMutableList() ?: mutableListOf()
        while (items.size <= index) items.add("")
        items[index] = value
        arrayStore.put(name, items)
        if (sensitiveWriteDepth.get() > 0) sensitiveArrayNames += name
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
                    if (index < 0 || index > MAX_ARRAY_INDEX) return null
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

    private fun updateSensitivity(target: MutableSet<String>, name: String, sensitive: Boolean) {
        if (sensitive) target += name else target -= name
    }

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

        /** The array Perform Task hands a sub-task its inputs in, as `%par1` and `%par2`. */
        private const val INPUT_ARRAY = "par"
        private val variableReference = Regex("%([A-Za-z][A-Za-z0-9_-]*)")

        /**
         * Upper bound for a nested/array write index. A `var.set` name such as `X[2000000000]`
         * (reachable from an imported/shared profile) would otherwise grow a list ~2 billion
         * entries, hanging the task thread and OOM-ing the process. Writes above this fail closed.
         */
        internal const val MAX_ARRAY_INDEX = 100_000
    }
}
