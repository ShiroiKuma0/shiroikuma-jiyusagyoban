package com.opentasker.ui.screens

import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistoryEntity

/**
 * The three shapes an undoable change takes. Each clears any redo branch first, so a new edit made
 * after undoing discards the future it replaced, and prunes the oldest entries for that entity.
 */
suspend fun EditHistoryDao.recordEdit(
    entityType: String,
    entityId: Long,
    previousJson: String,
    nextJson: String,
) {
    deleteRedoBranch(entityType, entityId)
    insert(
        EditHistoryEntity(
            entityType = entityType,
            entityId = entityId,
            previousJson = previousJson,
            nextJson = nextJson,
        ),
    )
    pruneOld(entityType, entityId)
}

suspend fun EditHistoryDao.recordCreation(entityType: String, entityId: Long, nextJson: String) {
    deleteRedoBranch(entityType, entityId)
    insert(
        EditHistoryEntity(
            entityType = entityType,
            entityId = entityId,
            previousJson = "",
            nextJson = nextJson,
        ),
    )
    pruneOld(entityType, entityId)
}

suspend fun EditHistoryDao.recordDeletion(entityType: String, entityId: Long, previousJson: String) {
    deleteRedoBranch(entityType, entityId)
    insert(
        EditHistoryEntity(
            entityType = entityType,
            entityId = entityId,
            previousJson = previousJson,
            nextJson = "",
        ),
    )
    pruneOld(entityType, entityId)
}
