package com.opentasker.core.engine

import com.opentasker.core.engine.variables.ArrayStore
import com.opentasker.core.engine.variables.GlobalVariableScope
import com.opentasker.core.engine.variables.InMemoryGlobalScope
import com.opentasker.core.engine.variables.VariableExpander
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
    val value: String,
    val isSecretDerived: Boolean,
)

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

    fun pushScope() {
        localStack.add(java.util.concurrent.ConcurrentHashMap())
        localSensitiveStack.add(ConcurrentHashMap.newKeySet())
    }
    fun popScope() {
        synchronized(localStack) {
            if (localStack.isNotEmpty()) {
                localStack.removeAt(localStack.size - 1)
                localSensitiveStack.removeAt(localSensitiveStack.size - 1)
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
            }
        }
        baseScope[name]?.let { return it }
        val bucket = bucketOf(name) ?: return null
        return globalScope.get(bucket, name)
    }

    fun isSensitive(name: String): Boolean {
        synchronized(localStack) {
            for (index in localStack.indices.reversed()) {
                if (name in localStack[index]) return name in localSensitiveStack[index]
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
        arrayStore.put(name, values)
        updateSensitivity(
            sensitiveArrayNames,
            name,
            sensitive || sensitiveWriteDepth.get() > 0 || name in sensitiveArrayNames,
        )
    }

    /**
     * Returns the elements of a stored array by name, or null if no array with that name exists.
     * Used by the `flow.foreach` control action to iterate over array variables.
     */
    fun getArrayItems(name: String): List<String>? =
        arrayStore.snapshot()[name]

    fun isArraySensitive(name: String): Boolean = name in sensitiveArrayNames

    /** Expand all variable references in [s] using the current scope chain. */
    fun expand(s: String): String {
        return expander.expand(s, this, arrayStore)
    }

    /** Expands a legacy expression while retaining whether any referenced input was secret. */
    fun expandTracked(s: String): TrackedExpansion = TrackedExpansion(
        value = expand(s),
        isSecretDerived = variableReference.findAll(s).any { match ->
            val name = match.groupValues[1]
            isSensitive(name) || name in sensitiveArrayNames
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
        synchronized(localStack) { localStack.forEach { scope -> taskValues += scope } }
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
        private val variableReference = Regex("%([A-Za-z][A-Za-z0-9_-]*)")

        /**
         * Upper bound for a nested/array write index. A `var.set` name such as `X[2000000000]`
         * (reachable from an imported/shared profile) would otherwise grow a list ~2 billion
         * entries, hanging the task thread and OOM-ing the process. Writes above this fail closed.
         */
        internal const val MAX_ARRAY_INDEX = 100_000
    }
}
