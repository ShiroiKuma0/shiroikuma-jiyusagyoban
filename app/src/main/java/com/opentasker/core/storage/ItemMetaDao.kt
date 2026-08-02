package com.opentasker.core.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Per-item UI metadata, keyed by (tab, itemKey), shared across every list tab so the entities themselves
 * stay untouched. [itemKey] is a string so it covers both numeric-id tabs (profiles / tasks / scenes —
 * key = the id as text) and name-keyed tabs (widgets — key = the template name; vars later). Phase 1 uses
 * [note] + [noteExpanded]; [groupId] + [position] back the foldable groups (Phase 2).
 */
@Entity("item_meta", primaryKeys = ["tab", "itemKey"])
data class ItemMetaEntity(
    val tab: String,
    val itemKey: String,
    val groupId: Long? = null,
    val note: String = "",
    val noteExpanded: Boolean = false,
    val position: Int = 0,
)

/**
 * A foldable group within one tab of one project — a labelled container that items are filed into.
 * Groups nest: [parentGroupId] (null = top level) points at an enclosing group, so sub-groups form a tree.
 */
@Entity("item_groups")
data class ItemGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long?,
    val tab: String,
    val name: String,
    val note: String = "",
    val position: Int = 0,
    val expanded: Boolean = true,
    val noteExpanded: Boolean = false,
    val parentGroupId: Long? = null,
)

@Dao
interface ItemMetaDao {
    @Query("SELECT * FROM item_meta WHERE tab = :tab") suspend fun getForTab(tab: String): List<ItemMetaEntity>
    @Query("SELECT * FROM item_meta WHERE tab = :tab") fun getForTabAsFlow(tab: String): Flow<List<ItemMetaEntity>>
    @Query("SELECT * FROM item_meta WHERE tab = :tab AND itemKey = :itemKey") suspend fun get(tab: String, itemKey: String): ItemMetaEntity?
    @Query("SELECT * FROM item_meta WHERE tab = :tab AND itemKey = :itemKey") fun getAsFlow(tab: String, itemKey: String): Flow<ItemMetaEntity?>
    @Query("SELECT * FROM item_meta") suspend fun getAll(): List<ItemMetaEntity>
    @Query("SELECT * FROM item_meta") fun getAllAsFlow(): Flow<List<ItemMetaEntity>>
    @Upsert suspend fun upsert(meta: ItemMetaEntity)
    @Query("DELETE FROM item_meta WHERE tab = :tab AND itemKey = :itemKey") suspend fun delete(tab: String, itemKey: String)
    @Query("UPDATE item_meta SET groupId = NULL WHERE tab = :tab AND groupId = :groupId") suspend fun clearGroup(tab: String, groupId: Long)
}

@Dao
interface ItemGroupDao {
    @Query("SELECT * FROM item_groups WHERE tab = :tab ORDER BY position, name") suspend fun getForTab(tab: String): List<ItemGroupEntity>
    @Query("SELECT * FROM item_groups WHERE tab = :tab") fun getForTabAsFlow(tab: String): Flow<List<ItemGroupEntity>>
    @Query("SELECT * FROM item_groups WHERE id = :id") suspend fun getById(id: Long): ItemGroupEntity?
    @Query("SELECT * FROM item_groups") suspend fun getAll(): List<ItemGroupEntity>
    @Query("SELECT * FROM item_groups") fun getAllAsFlow(): Flow<List<ItemGroupEntity>>
    @Upsert suspend fun upsert(group: ItemGroupEntity): Long
    @Query("DELETE FROM item_groups WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM item_groups WHERE projectId = :projectId") suspend fun deleteForProject(projectId: Long)
    @Query("UPDATE item_groups SET parentGroupId = NULL WHERE parentGroupId = :parentId") suspend fun orphanChildren(parentId: Long)
}
