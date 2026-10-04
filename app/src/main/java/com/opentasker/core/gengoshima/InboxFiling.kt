package com.opentasker.core.gengoshima

import com.opentasker.core.storage.GengoshimaDao
import com.opentasker.core.storage.GengoshimaInboxEntity
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.core.storage.GengoshimaSentenceEntity

/**
 * 「すべて確定」 on the 未分類 page: every inbox sentence goes to the end of its island, as `new`.
 *
 * Sentences go in the order they were SPOKEN (capturedAt), because an island is a monologue. A new
 * island is created once per distinct (topic, register) — the same new topic on five sentences makes one
 * island — and a "new" topic that is really an existing island's name (case aside) goes into that island.
 */
object InboxFiling {

    /** Where one inbox row is going: an existing island, or a new one's (topic, register). */
    data class Target(val islandId: Long?, val newName: String, val newRegister: String) {
        val isNew get() = islandId == null
        companion object {
            fun of(row: GengoshimaInboxEntity) = Target(row.islandId, row.newIslandName.trim(), row.newIslandRegister.trim())
        }
    }

    /** Rows that cannot be filed yet: no island at all. */
    fun unfiled(rows: List<GengoshimaInboxEntity>) =
        rows.filter { it.islandId == null && it.newIslandName.isBlank() }

    /**
     * The plan, pure: rows in spoken order, each resolved to an existing island id or to a key of
     * [Plan.newIslands] (in first-appearance order).
     */
    data class Plan(
        val newIslands: List<Pair<String, String>>,
        val placements: List<Pair<GengoshimaInboxEntity, Any>>,
    )

    fun plan(rows: List<GengoshimaInboxEntity>, islands: List<GengoshimaIslandEntity>): Plan {
        val byName = islands.associateBy { it.nameEn.trim().lowercase() }
        val ids = islands.map { it.id }.toSet()
        val newOnes = LinkedHashMap<Pair<String, String>, Unit>()
        val placements = rows.sortedWith(compareBy({ it.capturedAt }, { it.uuid })).map { row ->
            val t = Target.of(row)
            val where: Any = when {
                t.islandId != null && t.islandId in ids -> t.islandId
                byName.containsKey(t.newName.lowercase()) -> byName.getValue(t.newName.lowercase()).id
                else -> (t.newName to t.newRegister).also { newOnes[it] = Unit }
            }
            row to where
        }
        return Plan(newOnes.keys.toList(), placements)
    }

    /** Execute [plan]: create islands, append sentences, mark rows filed. Returns sentences filed. */
    suspend fun file(dao: GengoshimaDao, rows: List<GengoshimaInboxEntity>, now: Long = System.currentTimeMillis()): Int {
        require(unfiled(rows).isEmpty()) { "every sentence needs an island first" }
        val plan = plan(rows, dao.islands())
        val created = HashMap<Pair<String, String>, Long>()
        for ((name, register) in plan.newIslands) {
            created[name to register] = dao.insertIsland(
                GengoshimaIslandEntity(
                    position = dao.lastIslandPosition() + 1,
                    nameEn = name,
                    register = register,
                    createdAt = now,
                ),
            )
        }
        val done = ArrayList<GengoshimaInboxEntity>()
        for ((row, where) in plan.placements) {
            @Suppress("UNCHECKED_CAST")
            val islandId = (where as? Long) ?: created.getValue(where as Pair<String, String>)
            val id = dao.insertSentence(
                GengoshimaSentenceEntity(
                    islandId = islandId,
                    position = dao.lastSentencePosition(islandId) + 1,
                    en = row.en,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            done += row.copy(state = GengoshimaInboxEntity.STATE_FILED, islandId = islandId, sentenceId = id)
        }
        dao.updateInbox(done)
        return done.size
    }
}
