package com.opentasker.ui.screens

import android.content.Context
import androidx.room.withTransaction
import com.opentasker.core.diff.AutomationSemanticDiff
import com.opentasker.core.diff.SemanticDiffDocument
import com.opentasker.core.diff.SemanticDiffEntry
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.plugins.locale.LocaleConditionGrantStore
import com.opentasker.core.plugins.locale.LocaleGrantStore
import com.opentasker.core.references.AutomationReferenceIndex
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.EditHistorySnapshotDecoder
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.storage.toEntity

/**
 * Undo/redo restore for profiles, tasks and scenes.
 *
 * Lives outside [ActiveAutomationViewModel] so the history transaction, which is the largest
 * single responsibility the view model had, stops crowding the file against its line ceiling.
 */
internal class EditHistoryTransitions(
    private val db: AppDatabase,
    private val appContext: Context,
    private val fallbackTaskSettings: FallbackTaskSettings,
    private val locationDwellStateStore: LocationDwellStateStore,
    private val writeSettingsGuard: WriteSettingsGuard,
) {
    suspend fun transition(entityType: String, entityId: Long, redo: Boolean): SemanticDiffDocument? = db.withTransaction {
        val history = db.editHistoryDao()
        val snapshot = if (redo) {
            history.getRedoCandidate(entityType, entityId)
        } else {
            history.getUndoCandidate(entityType, entityId)
        } ?: return@withTransaction null

        if (snapshot.nextJson.isBlank()) {
            if (redo) {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                        val currentTask = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        val references = AutomationReferenceIndex.referencesTo(
                            task = currentTask,
                            profiles = db.profileDao().getAll().map { it.toDomain() },
                            tasks = db.taskDao().getAll().map { it.toDomain() },
                            scenes = db.sceneDao().getAll().map { it.toDomain() },
                            globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                        )
                        if (references.isNotEmpty()) return@withTransaction null
                        db.taskDao().delete(current)
                        LocaleGrantStore(appContext).revokeAllForTask(entityId)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareTask(currentTask, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                        val currentProfile = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.profileDao().delete(current)
                        LocaleConditionGrantStore(appContext).apply {
                            revokeAllForBinding(LocaleConditionGrantStore.profileKey(entityId))
                            currentProfile.contexts.indices.forEach { index ->
                                revokeAllForBinding(LocaleConditionGrantStore.contextKey(entityId, index))
                            }
                        }
                        locationDwellStateStore.clearProfile(entityId)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareProfile(currentProfile, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                        val currentScene = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.sceneDao().delete(current)
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareScene(currentScene, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            } else {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        if (db.taskDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.task(snapshot.previousJson, entityId)
                        db.taskDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareTask(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        if (db.profileDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.profile(snapshot.previousJson, entityId)
                        writeSettingsGuard.requireWriteSettingsIfEnabled(restored)
                        db.profileDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareProfile(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        if (db.sceneDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.scene(snapshot.previousJson, entityId)
                        db.sceneDao().insert(restored.toEntity())
                        history.markUndone(snapshot.id, "")
                        return@withTransaction AutomationSemanticDiff.compareScene(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            }
        }

        if (snapshot.previousJson.isBlank()) {
            if (redo) {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        if (db.taskDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.task(snapshot.nextJson, entityId)
                        db.taskDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareTask(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        if (db.profileDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.profile(snapshot.nextJson, entityId)
                        writeSettingsGuard.requireWriteSettingsIfEnabled(restored)
                        db.profileDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareProfile(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        if (db.sceneDao().getById(entityId) != null) return@withTransaction null
                        val restored = EditHistorySnapshotDecoder.scene(snapshot.nextJson, entityId)
                        db.sceneDao().insert(restored.toEntity())
                        history.markRedone(snapshot.id)
                        return@withTransaction AutomationSemanticDiff.compareScene(null, restored)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            } else {
                when (entityType) {
                    EditHistoryDao.TYPE_TASK -> {
                        val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                        val currentTask = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        val references = AutomationReferenceIndex.referencesTo(
                            task = currentTask,
                            profiles = db.profileDao().getAll().map { it.toDomain() },
                            tasks = db.taskDao().getAll().map { it.toDomain() },
                            scenes = db.sceneDao().getAll().map { it.toDomain() },
                            globalFallbackTaskId = fallbackTaskSettings.loadTaskId(),
                        )
                        if (references.isNotEmpty()) return@withTransaction null
                        db.taskDao().delete(current)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareTask(currentTask, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_PROFILE -> {
                        val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                        val currentProfile = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.profileDao().delete(current)
                        locationDwellStateStore.clearProfile(entityId)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareProfile(currentProfile, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    EditHistoryDao.TYPE_SCENE -> {
                        val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                        val currentScene = current.toDomainDecodeResult().also { result ->
                            result.issue?.let { issue -> throw CorruptRecordOverwriteException(issue) }
                        }.value
                        db.sceneDao().delete(current)
                        history.markUndone(snapshot.id, snapshot.nextJson)
                        return@withTransaction AutomationSemanticDiff.compareScene(currentScene, null)
                            ?.let(::documentOf)
                            ?: SemanticDiffDocument()
                    }
                    else -> return@withTransaction null
                }
            }
        }

        val targetJson = if (redo) snapshot.nextJson else snapshot.previousJson
        if (targetJson.isBlank()) return@withTransaction null

        when (entityType) {
            EditHistoryDao.TYPE_TASK -> {
                val current = db.taskDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.actionsJson
                }
                val target = EditHistorySnapshotDecoder.task(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareTask(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.taskDao().update(target.toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            EditHistoryDao.TYPE_PROFILE -> {
                val current = db.profileDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.contextsJson
                }
                val target = EditHistorySnapshotDecoder.profile(targetJson, entityId)
                writeSettingsGuard.requireWriteSettingsIfEnabled(target)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareProfile(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.profileDao().upsert(target.toEntity())
                locationDwellStateStore.clearProfile(entityId)
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            EditHistoryDao.TYPE_SCENE -> {
                val current = db.sceneDao().getById(entityId) ?: return@withTransaction null
                val currentDecoded = current.toDomainDecodeResult()
                val currentJson = if (currentDecoded.issue == null) {
                    StorageJson.encodeToString(currentDecoded.value)
                } else {
                    current.elementsJson
                }
                val target = EditHistorySnapshotDecoder.scene(targetJson, entityId)
                val diff = currentDecoded.issue?.let { SemanticDiffDocument() }
                    ?: AutomationSemanticDiff.compareScene(currentDecoded.value, target)?.let(::documentOf)
                    ?: SemanticDiffDocument()
                db.sceneDao().update(target.toEntity())
                if (redo) history.markRedone(snapshot.id) else history.markUndone(snapshot.id, currentJson)
                return@withTransaction diff
            }

            else -> return@withTransaction null
        }
    }
}

private fun documentOf(entry: SemanticDiffEntry): SemanticDiffDocument = SemanticDiffDocument(listOf(entry))
