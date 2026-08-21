package com.opentasker.ui.screens

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.location.LocationDwellStateStore
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AesGcmVariableSecretCodec
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.EditHistoryDao
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.ProjectDeletionSnapshot
import com.opentasker.core.storage.ProjectEntity
import com.opentasker.core.storage.StorageJson
import com.opentasker.core.storage.VariableEditHistoryIdentity
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditHistoryDeletionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun secretVariableDeletionUndoRestoresReadableCiphertextAndRedoDeletesAgain() = runBlocking {
        val db = buildDb()
        try {
            db.projectDao().insert(ProjectEntity(id = 1L, name = "Default", position = 0))
            val repository = variableRepository(db)
            repository.upsert(
                Variable("TOKEN", "secret", isGlobal = true, isSecret = true, projectId = 1L),
            )
            val historyId = VariableEditHistoryIdentity.entityId(1L, "TOKEN")
            repository.withMutationLock {
                db.withTransaction {
                    val snapshot = requireNotNull(getStored("TOKEN", 1L))
                    assertTrue(!snapshot.value.contains("secret"))
                    db.editHistoryDao().recordDeletion(
                        EditHistoryDao.TYPE_VARIABLE,
                        historyId,
                        StorageJson.encodeToString(snapshot),
                    )
                    delete("TOKEN", 1L)
                }
            }

            val transitions = transitions(db, repository)
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_VARIABLE, historyId, redo = false))
            assertEquals("secret", repository.get("TOKEN", 1L)?.value)
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_VARIABLE, historyId, redo = true))
            assertNull(repository.get("TOKEN", 1L))
        } finally {
            db.close()
        }
    }

    @Test
    fun projectDeletionUndoAndRedoMoveOnlyCapturedMembershipAndReencryptSecrets() = runBlocking {
        val db = buildDb()
        try {
            db.projectDao().insert(ProjectEntity(id = 1L, name = "Default", position = 0))
            db.projectDao().insert(ProjectEntity(id = 2L, name = "Work", position = 1))
            db.projectDao().insert(ProjectEntity(id = 3L, name = "Archive", position = 2))
            db.taskDao().insert(Task(id = 10L, name = "Task", projectId = 2L).toEntity())
            db.profileDao().insert(Profile(id = 11L, name = "Profile", enterTaskId = 10L, projectId = 2L).toEntity())
            db.sceneDao().insert(Scene(id = 12L, name = "Scene", widthDp = 240, heightDp = 160, projectId = 2L).toEntity())
            val repository = variableRepository(db)
            repository.upsert(Variable("TOKEN", "secret", isGlobal = true, isSecret = true, projectId = 2L))
            repository.upsert(Variable("Existing", "target", isGlobal = true, projectId = 3L))
            val snapshot = ProjectDeletionSnapshot(
                project = Project(id = 2L, name = "Work", position = 1),
                targetProjectId = 3L,
                taskIds = listOf(10L),
                profileIds = listOf(11L),
                sceneIds = listOf(12L),
                variableNames = listOf("TOKEN"),
            )
            repository.withMutationLock {
                db.withTransaction {
                    db.editHistoryDao().recordDeletion(
                        EditHistoryDao.TYPE_PROJECT,
                        2L,
                        StorageJson.encodeToString(snapshot),
                    )
                    db.taskDao().reassignProject(2L, 3L)
                    db.profileDao().reassignProject(2L, 3L)
                    db.sceneDao().reassignProject(2L, 3L)
                    reassignProject(2L, 3L)
                    assertEquals(1, db.projectDao().deleteIfNotDefault(2L))
                }
            }

            val transitions = transitions(db, repository)
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROJECT, 2L, redo = false))
            assertNotNull(db.projectDao().getById(2L))
            assertEquals(2L, db.taskDao().getById(10L)?.projectId)
            assertEquals(2L, db.profileDao().getById(11L)?.projectId)
            assertEquals(2L, db.sceneDao().getById(12L)?.projectId)
            assertEquals("secret", repository.get("TOKEN", 2L)?.value)
            assertEquals("target", repository.get("Existing", 3L)?.value)

            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROJECT, 2L, redo = true))
            assertNull(db.projectDao().getById(2L))
            assertEquals(3L, db.taskDao().getById(10L)?.projectId)
            assertEquals(3L, db.profileDao().getById(11L)?.projectId)
            assertEquals(3L, db.sceneDao().getById(12L)?.projectId)
            assertEquals("secret", repository.get("TOKEN", 3L)?.value)
        } finally {
            db.close()
        }
    }

    @Test
    fun existingTaskProfileAndSceneDeletionUndoStillRestoresAllThree() = runBlocking {
        val db = buildDb()
        try {
            db.projectDao().insert(ProjectEntity(id = 1L, name = "Default", position = 0))
            val task = Task(id = 10L, name = "Task")
            val profile = Profile(id = 11L, name = "Profile", enterTaskId = 10L)
            val scene = Scene(id = 12L, name = "Scene", widthDp = 240, heightDp = 160)
            db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_TASK, task.id, StorageJson.encodeToString(task))
            db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_PROFILE, profile.id, StorageJson.encodeToString(profile))
            db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_SCENE, scene.id, StorageJson.encodeToString(scene))

            val transitions = transitions(db, variableRepository(db))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_TASK, task.id, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROFILE, profile.id, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, scene.id, redo = false))
            assertNotNull(db.taskDao().getById(task.id))
            assertNotNull(db.profileDao().getById(profile.id))
            assertNotNull(db.sceneDao().getById(scene.id))
        } finally {
            db.close()
        }
    }

    private fun buildDb(): AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private fun variableRepository(db: AppDatabase): VariableRepository {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return VariableRepository(db.variableDao(), AesGcmVariableSecretCodec(keyProvider = { key }))
    }

    private fun transitions(db: AppDatabase, repository: VariableRepository): EditHistoryTransitions =
        EditHistoryTransitions(
            db = db,
            appContext = context,
            fallbackTaskSettings = FallbackTaskSettings(context),
            locationDwellStateStore = LocationDwellStateStore(context),
            writeSettingsGuard = WriteSettingsGuard(db, context),
            variableRepository = repository,
        )
}
