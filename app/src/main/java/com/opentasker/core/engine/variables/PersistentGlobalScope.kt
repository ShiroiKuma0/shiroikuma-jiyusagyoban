package com.opentasker.core.engine.variables

import com.opentasker.core.storage.SUPER_GLOBAL_PROJECT_ID
import com.opentasker.core.storage.VariableDao
import com.opentasker.core.storage.VariableEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's single source of truth for persistent variables: an in-memory cache (fast runtime reads)
 * write-through to the `variables` table, so globals survive across task runs and reboots.
 *
 * [init] warms the cache once on startup (before any task runs). Writes update the cache immediately
 * and enqueue the DB upsert/delete on a single serial consumer, so persistence applies strictly in
 * call order. The Vars screen observes the same table, and its edits must also route through here so
 * the cache never goes stale.
 */
object PersistentGlobalScope : GlobalVariableScope {
    private val buckets = ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>()

    @Volatile private var dao: VariableDao? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeOps = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** Warm the cache from the database. Call once in Application.onCreate after the DB is built. */
    fun init(dao: VariableDao) {
        this.dao = dao
        runBlocking {
            runCatching { dao.getAll() }.getOrDefault(emptyList()).forEach { e ->
                bucket(e.projectId)[e.name] = e.value
            }
        }
        // One serial writer drains the queue in order, so same-key writes converge correctly.
        io.launch { for (op in writeOps) runCatching { op() } }
    }

    private fun bucket(projectId: Long) = buckets.getOrPut(projectId) { ConcurrentHashMap() }

    override fun get(projectId: Long, name: String): String? = buckets[projectId]?.get(name)

    override fun set(projectId: Long, name: String, value: String) {
        bucket(projectId)[name] = value
        val d = dao ?: return
        writeOps.trySend { d.insert(VariableEntity(projectId, name, value)) }
    }

    override fun unset(projectId: Long, name: String) {
        buckets[projectId]?.remove(name)
        val d = dao ?: return
        writeOps.trySend { d.delete(projectId, name) }
    }

    override fun snapshot(projectId: Long): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        buckets[SUPER_GLOBAL_PROJECT_ID]?.let(out::putAll)
        if (projectId != SUPER_GLOBAL_PROJECT_ID) buckets[projectId]?.let(out::putAll)
        return out
    }
}
