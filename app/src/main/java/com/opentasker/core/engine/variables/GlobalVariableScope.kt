package com.opentasker.core.engine.variables

import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import java.util.concurrent.ConcurrentHashMap

/**
 * Backend for the *persistent* variable scopes addressed by a [VariableStore]:
 *   - `projectId == 0` → super-globals (`%ALLCAPS`), app-wide.
 *   - `projectId > 0`  → project-globals (`%MixedCase`) owned by that project.
 *
 * Task-local (`%lowercase`) variables never reach here — they stay in the store's ephemeral scopes.
 * Implementations: [InMemoryGlobalScope] (standalone stores / tests) and the DB-backed
 * `PersistentGlobalScope` singleton used at runtime.
 */
interface GlobalVariableScope {
    fun get(projectId: Long, name: String): String?
    fun set(projectId: Long, name: String, value: String)
    fun unset(projectId: Long, name: String)

    /** Super-globals merged with [projectId]'s project-globals (a project name shadows a super one). */
    fun snapshot(projectId: Long): Map<String, String>
}

/** Ephemeral, process-local backend with no persistence — for standalone stores and unit tests. */
class InMemoryGlobalScope : GlobalVariableScope {
    private val buckets = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()
    private fun bucket(projectId: Long) = buckets.getOrPut(projectId) { ConcurrentHashMap() }

    override fun get(projectId: Long, name: String): String? = buckets[projectId]?.get(name)
    override fun set(projectId: Long, name: String, value: String) { bucket(projectId)[name] = value }
    override fun unset(projectId: Long, name: String) { buckets[projectId]?.remove(name) }

    override fun snapshot(projectId: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        buckets[SUPER_GLOBAL_PROJECT_ID]?.let(out::putAll)
        if (projectId != SUPER_GLOBAL_PROJECT_ID) buckets[projectId]?.let(out::putAll)
        return out
    }
}
