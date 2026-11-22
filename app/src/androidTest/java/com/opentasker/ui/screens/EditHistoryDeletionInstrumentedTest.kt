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

    /**
     * Deleting a project moves its members without a history row of their own. Undo wrote back the
     * snapshot's project, so it put each item in the deleted project, where no view shows it, and
     * undoing the project deletion then refused because the members weren't where it left them.
     */
    @Test
    fun undoAndRedoOfAnEditKeepTheProjectTheItemIsIn() = runBlocking {
        val db = buildDb()
        try {
            db.projectDao().insert(ProjectEntity(id = 1L, name = "Default", position = 0))
            db.projectDao().insert(ProjectEntity(id = 2L, name = "Work", position = 1))
            val task = Task(id = 10L, name = "Task", projectId = 2L)
            val profile = Profile(id = 11L, name = "Profile", enterTaskId = 10L, projectId = 2L)
            val scene = Scene(id = 12L, name = "Scene", widthDp = 240, heightDp = 160, projectId = 2L)
            val editedTask = task.copy(name = "Task edited")
            val editedProfile = profile.copy(name = "Profile edited")
            val editedScene = scene.copy(name = "Scene edited")
            db.taskDao().insert(editedTask.toEntity())
            db.profileDao().insert(editedProfile.toEntity())
            db.sceneDao().insert(editedScene.toEntity())
            val history = db.editHistoryDao()
            history.recordEdit(EditHistoryDao.TYPE_TASK, 10L, StorageJson.encodeToString(task), StorageJson.encodeToString(editedTask))
            history.recordEdit(EditHistoryDao.TYPE_PROFILE, 11L, StorageJson.encodeToString(profile), StorageJson.encodeToString(editedProfile))
            history.recordEdit(EditHistoryDao.TYPE_SCENE, 12L, StorageJson.encodeToString(scene), StorageJson.encodeToString(editedScene))
            val repository = variableRepository(db)
            deleteProjectIntoDefault(db, repository, taskIds = listOf(10L), profileIds = listOf(11L), sceneIds = listOf(12L))

            val transitions = transitions(db, repository)
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_TASK, 10L, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROFILE, 11L, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, 12L, redo = false))
            assertEquals("Task" to 1L, db.taskDao().getById(10L)?.let { it.name to it.projectId })
            assertEquals("Profile" to 1L, db.profileDao().getById(11L)?.let { it.name to it.projectId })
            assertEquals("Scene" to 1L, db.sceneDao().getById(12L)?.let { it.name to it.projectId })

            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROJECT, 2L, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_TASK, 10L, redo = true))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROFILE, 11L, redo = true))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, 12L, redo = true))
            assertEquals("Task edited" to 2L, db.taskDao().getById(10L)?.let { it.name to it.projectId })
            assertEquals("Profile edited" to 2L, db.profileDao().getById(11L)?.let { it.name to it.projectId })
            assertEquals("Scene edited" to 2L, db.sceneDao().getById(12L)?.let { it.name to it.projectId })
        } finally {
            db.close()
        }
    }

    /** An item deleted before its project was comes back in Default rather than the gone project. */
    @Test
    fun undoingADeletionWhoseProjectIsGoneRestoresIntoDefault() = runBlocking {
        val db = buildDb()
        try {
            db.projectDao().insert(ProjectEntity(id = 1L, name = "Default", position = 0))
            db.projectDao().insert(ProjectEntity(id = 2L, name = "Work", position = 1))
            val task = Task(id = 10L, name = "Task", projectId = 2L)
            val profile = Profile(id = 11L, name = "Profile", enterTaskId = 10L, projectId = 2L)
            val scene = Scene(id = 12L, name = "Scene", widthDp = 240, heightDp = 160, projectId = 2L)
            db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_TASK, task.id, StorageJson.encodeToString(task))
            db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_PROFILE, profile.id, StorageJson.encodeToString(profile))
            db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_SCENE, scene.id, StorageJson.encodeToString(scene))
            val repository = variableRepository(db)
            deleteProjectIntoDefault(db, repository, taskIds = emptyList(), profileIds = emptyList(), sceneIds = emptyList())

            val transitions = transitions(db, repository)
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_TASK, task.id, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_PROFILE, profile.id, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, scene.id, redo = false))
            assertEquals(1L, db.taskDao().getById(task.id)?.projectId)
            assertEquals(1L, db.profileDao().getById(profile.id)?.projectId)
            assertEquals(1L, db.sceneDao().getById(scene.id)?.projectId)
        } finally {
            db.close()
        }
    }

    /** Redoing a creation puts it back in its project, or in Default once that project is gone. */
    @Test
    fun redoingACreationKeepsItsProjectOrFallsBackToDefault() = runBlocking {
        val db = buildDb()
        try {
            db.projectDao().insert(ProjectEntity(id = 1L, name = "Default", position = 0))
            db.projectDao().insert(ProjectEntity(id = 2L, name = "Work", position = 1))
            val kept = Scene(id = 12L, name = "Kept", widthDp = 240, heightDp = 160, projectId = 2L)
            val orphaned = Scene(id = 13L, name = "Orphaned", widthDp = 240, heightDp = 160, projectId = 2L)
            listOf(kept, orphaned).forEach { scene ->
                db.sceneDao().insert(scene.toEntity())
                db.editHistoryDao().recordCreation(EditHistoryDao.TYPE_SCENE, scene.id, StorageJson.encodeToString(scene))
            }
            val repository = variableRepository(db)
            val transitions = transitions(db, repository)

            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, kept.id, redo = false))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, kept.id, redo = true))
            assertEquals(2L, db.sceneDao().getById(kept.id)?.projectId)

            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, orphaned.id, redo = false))
            deleteProjectIntoDefault(db, repository, taskIds = emptyList(), profileIds = emptyList(), sceneIds = listOf(kept.id))
            assertNotNull(transitions.transition(EditHistoryDao.TYPE_SCENE, orphaned.id, redo = true))
            assertEquals(1L, db.sceneDao().getById(orphaned.id)?.projectId)
        } finally {
            db.close()
        }
    }

    /** What `deleteProject` does to project 2 with Default as the destination. */
    private suspend fun deleteProjectIntoDefault(
        db: AppDatabase,
        repository: VariableRepository,
        taskIds: List<Long>,
        profileIds: List<Long>,
        sceneIds: List<Long>,
    ) {
        val snapshot = ProjectDeletionSnapshot(
            project = Project(id = 2L, name = "Work", position = 1),
            targetProjectId = 1L,
            taskIds = taskIds,
            profileIds = profileIds,
            sceneIds = sceneIds,
            variableNames = emptyList(),
        )
        repository.withMutationLock {
            db.withTransaction {
                db.editHistoryDao().recordDeletion(EditHistoryDao.TYPE_PROJECT, 2L, StorageJson.encodeToString(snapshot))
                db.taskDao().reassignProject(2L, 1L)
                db.profileDao().reassignProject(2L, 1L)
                db.sceneDao().reassignProject(2L, 1L)
                reassignProject(2L, 1L)
                assertEquals(1, db.projectDao().deleteIfNotDefault(2L))
            }
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
