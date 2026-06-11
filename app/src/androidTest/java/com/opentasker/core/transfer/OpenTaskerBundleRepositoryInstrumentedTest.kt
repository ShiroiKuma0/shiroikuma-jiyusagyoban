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
import com.opentasker.core.storage.toEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenTaskerBundleRepositoryInstrumentedTest {
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
            val enterTaskId = source.taskDao().insert(
                Task(
                    name = "Log task",
                    actions = listOf(ActionSpec(type = "log", args = mapOf("message" to "hello"))),
                ).toEntity()
            )
            val exitTaskId = source.taskDao().insert(
                Task(
                    name = "Exit task",
                    actions = listOf(ActionSpec(type = "notify.toast", args = mapOf("message" to "bye"))),
                ).toEntity()
            )
            source.profileDao().insert(
                Profile(
                    name = "Enabled profile",
                    enabled = true,
                    contexts = listOf(ContextSpec(ContextType.EVENT, mapOf("event" to "manual"))),
                    enterTaskId = enterTaskId,
                    exitTaskId = exitTaskId,
                ).toEntity()
            )
            source.variableDao().insert(Variable(name = "%flag", value = "on", isGlobal = true).toEntity())
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

            assertEquals(2, report.insertedTasks)
            assertEquals(1, report.insertedProfiles)
            assertEquals(1, report.insertedVariables)
            assertEquals(1, report.insertedScenes)

            val importedTasks = target.taskDao().getAll().map { it.toDomain() }
            val importedTaskIds = importedTasks.associate { it.name to it.id }
            val importedProfile = target.profileDao().getAll().single().toDomain()
            assertFalse(importedProfile.enabled)
            assertEquals(importedTaskIds.getValue("Log task"), importedProfile.enterTaskId)
            assertEquals(importedTaskIds.getValue("Exit task"), importedProfile.exitTaskId)
            assertNotEquals(enterTaskId, importedProfile.enterTaskId)

            val importedVariable = target.variableDao().get("%flag")?.toDomain()
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
}
