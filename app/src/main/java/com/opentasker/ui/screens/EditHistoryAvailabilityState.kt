package com.opentasker.ui.screens

import androidx.compose.runtime.Immutable
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.EditHistoryDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Whether the per-entity Undo and Redo controls have anything to act on. */
@Immutable
data class EditHistoryAvailabilityState(
    private val byKey: Map<String, Pair<Boolean, Boolean>> = emptyMap(),
) {
    fun canUndo(entityType: String, entityId: Long): Boolean = byKey[key(entityType, entityId)]?.first == true

    fun canRedo(entityType: String, entityId: Long): Boolean = byKey[key(entityType, entityId)]?.second == true

    fun canUndoProfile(id: Long): Boolean = canUndo(EditHistoryDao.TYPE_PROFILE, id)

    fun canRedoProfile(id: Long): Boolean = canRedo(EditHistoryDao.TYPE_PROFILE, id)

    fun canUndoTask(id: Long): Boolean = canUndo(EditHistoryDao.TYPE_TASK, id)

    fun canRedoTask(id: Long): Boolean = canRedo(EditHistoryDao.TYPE_TASK, id)

    fun canUndoScene(id: Long): Boolean = canUndo(EditHistoryDao.TYPE_SCENE, id)

    fun canRedoScene(id: Long): Boolean = canRedo(EditHistoryDao.TYPE_SCENE, id)

    companion object {
        internal fun key(entityType: String, entityId: Long) = "$entityType:$entityId"
    }
}

/**
 * Live Undo/Redo availability for every entity that has history.
 *
 * One grouped query rather than a lookup per card: the lists render every profile and task, and a
 * per-row suspending check would either block composition or flicker.
 */
internal fun editHistoryAvailability(
    db: AppDatabase,
    scope: CoroutineScope,
): StateFlow<EditHistoryAvailabilityState> = db.editHistoryDao()
    .availabilityAsFlow()
    .map { rows ->
        EditHistoryAvailabilityState(
            rows.associate { row ->
                EditHistoryAvailabilityState.key(row.entityType, row.entityId) to (row.canUndo to row.canRedo)
            },
        )
    }
    .stateIn(scope, SharingStarted.WhileSubscribed(5_000), EditHistoryAvailabilityState())
