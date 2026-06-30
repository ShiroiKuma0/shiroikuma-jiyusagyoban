package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerXmlImporterTest {
    @Test
    fun parsesTasksProfilesVariablesAndMigrationReport() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task10">
                        <id>10</id>
                        <nme>Alert task</nme>
                        <Action sr="act0">
                            <code>548</code>
                            <Str sr="arg0">Build done</Str>
                            <Str sr="arg1">APK is ready</Str>
                        </Action>
                        <Action sr="act1">
                            <code>9999</code>
                        </Action>
                    </Task>
                    <Profile sr="prof20">
                        <id>20</id>
                        <nme>Work window</nme>
                        <mid0>10</mid0>
                        <Time>
                            <from>09:00</from>
                            <to>17:00</to>
                        </Time>
                    </Profile>
                    <Variable>
                        <nme>%FOO</nme>
                        <val>bar</val>
                    </Variable>
                    <Scene>
                        <nme>Popup</nme>
                    </Scene>
                </TaskerData>
            """.trimIndent(),
            appVersion = "0.2.21",
            importedAtEpochMs = 123L,
        )

        assertEquals(1, report.sourceTaskCount)
        assertEquals(1, report.sourceProfileCount)
        assertEquals(1, report.sourceVariableCount)
        assertEquals(1, report.sourceSceneCount)
        assertEquals(listOf("notify.show"), report.mappedActions.map { it.openTaskerActionId })
        assertEquals(listOf("9999"), report.unsupportedActions.map { it.taskerCode })
        assertTrue(report.lossyWarnings.any { it.contains("scenes") })

        val task = report.bundle.tasks.single()
        assertEquals("Alert task", task.name)
        assertEquals(listOf("notify.show", "tasker.unsupported"), task.actions.map { it.type })
        assertEquals("Build done", task.actions.first().args["title"])
        assertEquals("APK is ready", task.actions.first().args["text"])

        val profile = report.bundle.profiles.single()
        assertEquals("Work window", profile.name)
        assertEquals(10L, profile.enterTaskId)
        assertEquals(ContextType.TIME, profile.contexts.single().type)
        assertEquals("09:00", profile.contexts.single().config["start"])

        val variable = report.bundle.variables.single()
        assertEquals("%FOO", variable.name)
        assertEquals(0L, variable.projectId)

        val requirement = report.bundle.metadata.capabilityRequirements.single { it.actionId == "tasker.unsupported" }
        assertEquals(CapabilityLevel.Unsupported, requirement.level)
        assertTrue(report.bundle.metadata.warnings.any { it.contains("unsupported actions") })
    }

    @Test
    fun skipsProfilesWithMissingTasksOrUnsupportedContexts() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1"><id>1</id><nme>Known</nme></Task>
                    <Profile sr="prof1">
                        <nme>Missing task</nme>
                        <mid0>99</mid0>
                        <Time><fh>8</fh><fm>30</fm><th>9</th><tm>0</tm></Time>
                    </Profile>
                    <Profile sr="prof2">
                        <nme>Unsupported context</nme>
                        <mid0>1</mid0>
                        <Location><lat>1.0</lat></Location>
                    </Profile>
                </TaskerData>
            """.trimIndent(),
            appVersion = "0.2.21",
            importedAtEpochMs = 123L,
        )

        assertTrue(report.bundle.profiles.isEmpty())
        assertTrue(report.lossyWarnings.any { it.contains("entry task") })
        assertTrue(report.lossyWarnings.any { it.contains("unsupported Tasker context") })
        assertTrue(report.lossyWarnings.any { it.contains("no supported Tasker contexts") })
    }

    @Test
    fun mapsWaitActionsWithTaskerTimeParts() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id>
                        <nme>Delay</nme>
                        <Action><code>30</code><Int sr="arg0" val="0"/><Int sr="arg1" val="5"/></Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "0.2.21",
            importedAtEpochMs = 123L,
        )

        val action = report.bundle.tasks.single().actions.single()
        assertEquals("flow.wait", action.type)
        assertEquals("5000", action.args["millis"])
    }

    @Test
    fun rejectsDoctypeDeclarationsBeforeParsing() {
        val error = runCatching {
            TaskerXmlImporter.parse(
                rawXml = """
                    <!DOCTYPE TaskerData [
                        <!ENTITY xxe SYSTEM "file:///etc/passwd">
                    ]>
                    <TaskerData>
                        <Task sr="task1"><id>1</id><nme>&xxe;</nme></Task>
                    </TaskerData>
                """.trimIndent(),
                appVersion = "0.2.73",
                importedAtEpochMs = 123L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("DOCTYPE"))
    }

    @Test
    fun rejectsOversizedTaskerXmlPayloads() {
        val error = runCatching {
            TaskerXmlImporter.parse(
                rawXml = "x".repeat(4 * 1024 * 1024 + 1),
                appVersion = "0.2.73",
                importedAtEpochMs = 123L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("size limit"))
    }

    @Test
    fun buildsPreviewAndDisabledConfirmedImportBundle() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id>
                        <nme>Notify</nme>
                        <Action><code>548</code><Str sr="arg0">Ready</Str></Action>
                        <Action><code>9999</code></Action>
                    </Task>
                    <Profile sr="prof1">
                        <id>2</id>
                        <nme>Morning</nme>
                        <mid0>1</mid0>
                        <Time><from>08:00</from><to>09:00</to></Time>
                    </Profile>
                </TaskerData>
            """.trimIndent(),
            appVersion = "0.2.58",
            importedAtEpochMs = 123L,
        )

        val preview = TaskerImportPlanner.preview(report)

        assertTrue(preview.canImport)
        assertEquals(1, preview.sourceTaskCount)
        assertEquals(1, preview.sourceProfileCount)
        assertEquals(1, preview.importTaskCount)
        assertEquals(1, preview.importProfileCount)
        assertEquals(1, preview.mappedActionCount)
        assertEquals(1, preview.unsupportedActionCount)
        assertTrue(preview.capabilityWarnings.any { it.contains("tasker.unsupported") })
        assertTrue(report.bundle.profiles.single().enabled)

        val confirmedBundle = TaskerImportPlanner.confirmedBundle(report)

        assertFalse(confirmedBundle.profiles.single().enabled)
        assertTrue(confirmedBundle.metadata.warnings.any { it.contains("disabled by default") })
    }
}
