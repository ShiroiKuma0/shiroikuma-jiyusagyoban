package com.opentasker.core.transfer

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.CorruptStoredRecordException
import com.opentasker.core.storage.TaskEntity
import com.opentasker.core.storage.VariableRepository
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenTaskerBundleRepositoryInstrumentedTest {
    @Test
    fun exportRefusesCorruptStoredTaskWithoutChangingRawPayload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val rawPayload = "{not-json"

        try {
            val id = db.taskDao().insert(
                TaskEntity(
                    name = "Corrupt task",
                    priority = 0,
                    collisionMode = "ABORT_NEW",
                    actionsJson = rawPayload,
                ),
            )

            val failure = runCatching {
                OpenTaskerBundleRepository(db).exportBundle(appVersion = "test")
            }.exceptionOrNull()

            assertTrue(failure is CorruptStoredRecordException)
            assertEquals(rawPayload, db.taskDao().getById(id)?.actionsJson)
        } finally {
            db.close()
        }
    }

    @Test
    fun exportImportRoundTripRemapsIdsAndDisablesProfiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val exitTaskId = source.taskDao().insert(
                Task(
                    name = "Exit task",
                    actions = listOf(ActionSpec(type = "notify.show", args = mapOf("text" to "bye"))),
                ).toEntity()
            )
            val enterTaskId = source.taskDao().insert(
                Task(
                    name = "Log task",
                    actions = listOf(
                        ActionSpec(type = "log", args = mapOf("message" to "hello")),
                        ActionSpec(type = "task.run", args = mapOf("id" to exitTaskId.toString())),
                        ActionSpec(
                            type = "notify.show",
                            args = mapOf("button1_label" to "Exit", "button1_task_id" to exitTaskId.toString()),
                        ),
                    ),
                ).toEntity()
            )
            val fallbackTaskId = source.taskDao().insert(
                Task(
                    name = "Fallback task",
                    actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "recovered"))),
                ).toEntity()
            )
            source.profileDao().insert(
                Profile(
                    name = "Enabled profile",
                    enabled = true,
                    contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "manual"))),
                    enterTaskId = enterTaskId,
                    exitTaskId = exitTaskId,
                    fallbackTaskId = fallbackTaskId,
                ).toEntity()
            )
            source.variableDao().insert(Variable(name = "FLAG", value = "on", isGlobal = true).toEntity())
            source.sceneDao().insert(
                Scene(
                    name = "Control panel",
                    widthDp = 240,
                    heightDp = 160,
                    elements = listOf(
                        SceneElement(
                            id = 7,
                            type = SceneElementType.BUTTON,
                            xDp = 8,
                            yDp = 10,
                            widthDp = 96,
                            heightDp = 48,
                            tapTaskId = enterTaskId,
                            longPressTaskId = exitTaskId,
                        )
                    ),
                ).toEntity()
            )

            val encoded = OpenTaskerBundleCodec.encode(
                OpenTaskerBundleRepository(source).exportBundle(
                    appVersion = "test",
                    exportedAtEpochMs = 123L,
                )
            )
            target.taskDao().insert(Task(name = "Existing task").toEntity())

            val report = OpenTaskerBundleRepository(target).importBundle(OpenTaskerBundleCodec.decode(encoded))

            assertEquals(3, report.insertedTasks)
            assertEquals(1, report.insertedProfiles)
            assertEquals(1, report.insertedVariables)
            assertEquals(1, report.insertedScenes)

            val importedTasks = target.taskDao().getAll().map { it.toDomain() }
            val importedTaskIds = importedTasks.associate { it.name to it.id }
            val importedProfile = target.profileDao().getAll().single().toDomain()
            assertFalse(importedProfile.enabled)
            assertTrue(importedProfile.requiresRiskAcknowledgement)
            assertEquals(importedTaskIds.getValue("Log task"), importedProfile.enterTaskId)
            assertEquals(importedTaskIds.getValue("Exit task"), importedProfile.exitTaskId)
            assertEquals(importedTaskIds.getValue("Fallback task"), importedProfile.fallbackTaskId)
            assertNotEquals(enterTaskId, importedProfile.enterTaskId)

            val importedParent = importedTasks.single { it.name == "Log task" }
            assertEquals(
                importedTaskIds.getValue("Exit task").toString(),
                importedParent.actions[1].args["id"],
            )
            assertEquals(
                importedTaskIds.getValue("Exit task").toString(),
                importedParent.actions[2].args["button1_task_id"],
            )

            val importedVariable = target.variableDao().get("FLAG")?.toDomain()
            assertEquals("on", importedVariable?.value)

            val importedScene = target.sceneDao().getAll().single().toDomain()
            val importedElement = importedScene.elements.single()
            assertEquals(importedTaskIds.getValue("Log task"), importedElement.tapTaskId)
            assertEquals(importedTaskIds.getValue("Exit task"), importedElement.longPressTaskId)
        } finally {
            source.close()
            target.close()
        }
    }

    @Test
    fun variableConflictsRequirePolicyAndReplacingSecretNeverDeclassifiesIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        suspend fun newTarget(secret: Boolean = false): AppDatabase {
            val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            VariableRepository(target.variableDao()).upsert(
                Variable(name = "COUNT", value = "existing", isGlobal = true, isSecret = secret),
            )
            return target
        }

        val incoming = OpenTaskerBundle(
            appVersion = "test",
            exportedAtEpochMs = 123L,
            variables = listOf(Variable(name = "COUNT", value = "incoming", isGlobal = true)),
        )
        val preserveTarget = newTarget()
        val renameTarget = newTarget()
        val replaceSecretTarget = newTarget(secret = true)
        try {
            val preserveRepository = OpenTaskerBundleRepository(preserveTarget)
            val preservePlan = preserveRepository.planImport(incoming)
            assertEquals("COUNT_imported", preservePlan.variableConflicts.single().suggestedRename)
            preserveRepository.importBundle(
                incoming,
                mapOf("COUNT" to VariableConflictResolution(VariableConflictAction.PRESERVE_EXISTING)),
            )
            assertEquals("existing", preserveTarget.variableDao().get("COUNT")?.toDomain()?.value)

            OpenTaskerBundleRepository(renameTarget).importBundle(
                incoming,
                mapOf(
                    "COUNT" to VariableConflictResolution(
                        VariableConflictAction.RENAME_IMPORTED,
                        renamedTo = "COUNT_imported",
                    ),
                ),
            )
            assertEquals("existing", renameTarget.variableDao().get("COUNT")?.toDomain()?.value)
            assertEquals("incoming", renameTarget.variableDao().get("COUNT_imported")?.toDomain()?.value)

            val secretRepository = VariableRepository(replaceSecretTarget.variableDao())
            OpenTaskerBundleRepository(replaceSecretTarget, secretRepository).importBundle(
                incoming,
                mapOf("COUNT" to VariableConflictResolution(VariableConflictAction.REPLACE_EXISTING)),
            )
            val runtime = secretRepository.runtimeGlobals()
            assertEquals("incoming", runtime.values["COUNT"])
            assertTrue("COUNT" in runtime.secretNames)
            assertTrue(secretRepository.ordinaryExport().variables.none { it.name == "COUNT" })
        } finally {
            preserveTarget.close()
            renameTarget.close()
            replaceSecretTarget.close()
        }
    }

    @Test
    fun invalidConflictResolutionRollsBackEveryRoomWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            target.variableDao().insert(Variable("COUNT", "existing", isGlobal = true).toEntity())
            target.variableDao().insert(Variable("COUNT_imported", "occupied", isGlobal = true).toEntity())
            val beforeTasks = target.taskDao().getAll()
            val bundle = OpenTaskerBundle(
                appVersion = "test",
                exportedAtEpochMs = 123L,
                tasks = listOf(Task(id = 7, name = "Must roll back", actions = listOf(ActionSpec(type = "log")))),
                variables = listOf(Variable("COUNT", "incoming", isGlobal = true)),
            )

            val failure = runCatching {
                OpenTaskerBundleRepository(target).importBundle(
                    bundle,
                    mapOf(
                        "COUNT" to VariableConflictResolution(
                            VariableConflictAction.RENAME_IMPORTED,
                            renamedTo = "COUNT_imported",
                        ),
                    ),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(beforeTasks, target.taskDao().getAll())
            assertEquals("existing", target.variableDao().get("COUNT")?.toDomain()?.value)
            assertEquals("occupied", target.variableDao().get("COUNT_imported")?.toDomain()?.value)
        } finally {
            target.close()
        }
    }

    @Test
    fun unsupportedFutureAndInvalidMigratedBundlesPerformZeroRoomWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            target.taskDao().insert(Task(name = "Existing", actions = listOf(ActionSpec(type = "log"))).toEntity())
            val before = target.taskDao().getAll()
            val repository = OpenTaskerBundleRepository(target)

            val futureFailure = runCatching {
                repository.importBundle(
                    OpenTaskerBundle(
                        schemaVersion = 999,
                        appVersion = "future",
                        exportedAtEpochMs = 0,
                        tasks = listOf(Task(id = 7, name = "Future", actions = listOf(ActionSpec(type = "log")))),
                    ),
                )
            }.exceptionOrNull()
            val invalidMigrated = OpenTaskerBundleCodec.decode(
                """{"schemaVersion":1,"appVersion":"old","exportedAtEpochMs":0,"tasks":[{"id":1,"name":"Invalid","actions":[]}]}""",
            )
            val migrationValidationFailure = runCatching {
                repository.importBundle(invalidMigrated)
            }.exceptionOrNull()

            assertTrue(futureFailure is IllegalArgumentException)
            assertTrue(migrationValidationFailure is IllegalArgumentException)
            assertEquals(before, target.taskDao().getAll())
        } finally {
            target.close()
        }
    }
}
