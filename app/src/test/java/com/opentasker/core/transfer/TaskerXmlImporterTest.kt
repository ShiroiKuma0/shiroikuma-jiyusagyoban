package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.ContextType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerXmlImporterTest {
    /**
     * The fork dropped `Variable.isGlobal`: scope is no longer carried on the model at all, it is
     * derived from the name's first character by `VariableStore` (uppercase = persisted, lowercase =
     * task-local). So the importer no longer classifies a Tasker variable on the way in — it imports
     * every one into the super bucket (`projectId = 0`) with its name intact and lets the read side
     * decide. This asserts that, which is the behaviour that now exists.
     */
    @Test
    fun taskerVariablesImportIntoTheSuperBucketWithNamesAndValuesIntact() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Variable><nme>%myValue</nme><val>global</val></Variable>
                    <Variable><nme>%local</nme><val>local</val></Variable>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 123L,
        )

        assertEquals(2, report.bundle.variables.size)
        val mixed = report.bundle.variables.single { it.name == "%myValue" }
        val lower = report.bundle.variables.single { it.name == "%local" }
        assertEquals("global", mixed.value)
        assertEquals("local", lower.value)
        assertEquals(0L, mixed.projectId)
        assertEquals(0L, lower.projectId)
    }

    @Test
    fun parsesTasksProfilesVariablesAndMigrationReport() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task10">
                        <id>10</id>
                        <nme>Alert task</nme>
                        <Action sr="act0">
                            <code>523</code>
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
    fun waitFieldsAreReadByArgIndexRegardlessOfOmittedZeroFields() {
        fun waitMillisFor(intFields: String): String {
            val report = TaskerXmlImporter.parse(
                rawXml = """
                    <TaskerData>
                        <Task sr="task1">
                            <id>1</id>
                            <nme>Delay</nme>
                            <Action><code>30</code>$intFields</Action>
                        </Task>
                    </TaskerData>
                """.trimIndent(),
                appVersion = "0.2.21",
                importedAtEpochMs = 123L,
            )
            return report.bundle.tasks.single().actions.single().args.getValue("millis")
        }

        // A lone milliseconds field (arg0) must stay milliseconds, not be mis-scaled to seconds.
        assertEquals("500", waitMillisFor("""<Int sr="arg0" val="500"/>"""))
        // A lone seconds field (arg1) with the zero ms field omitted must scale to seconds.
        assertEquals("3000", waitMillisFor("""<Int sr="arg1" val="3"/>"""))
        // Minutes (arg2) and hours (arg3) keep their own units.
        assertEquals("120000", waitMillisFor("""<Int sr="arg2" val="2"/>"""))
        assertEquals("3600000", waitMillisFor("""<Int sr="arg3" val="1"/>"""))
        // Combined fields sum across units.
        assertEquals(
            "61500",
            waitMillisFor("""<Int sr="arg0" val="500"/><Int sr="arg1" val="1"/><Int sr="arg2" val="1"/>"""),
        )
    }

    @Test
    fun mapsSafeSettingsMediaNotificationVariableAndFlowBatch() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <TaskerData>
                    <Task sr="task1">
                        <id>1</id><nme>Safe batch</nme>
                        <Action><code>523</code><Str sr="arg0">Title</Str><Str sr="arg1">Body</Str></Action>
                        <Action><code>547</code><Str sr="arg0">%MODE</Str><Str sr="arg1">quiet</Str></Action>
                        <Action><code>559</code><Str sr="arg0">Hello</Str></Action>
                        <Action><code>61</code><Int sr="arg0" val="250"/></Action>
                        <Action><code>307</code><Int sr="arg0" val="4"/></Action>
                        <Action><code>511</code><Str sr="arg0">on</Str></Action>
                        <Action><code>192</code><Str sr="arg0">content://media/song</Str></Action>
                        <Action><code>449</code></Action>
                        <Action><code>451</code></Action>
                        <Action><code>453</code></Action>
                        <Action><code>37</code><Str sr="arg0">%MODE = quiet</Str></Action>
                        <Action><code>43</code></Action><Action><code>38</code></Action>
                        <Action><code>39</code><Str sr="arg0">%ITEMS</Str><Str sr="arg1">item</Str></Action>
                        <Action><code>40</code></Action><Action><code>137</code></Action>
                    </Task>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 123L,
        )

        val actions = report.bundle.tasks.single().actions
        assertEquals(16, actions.size)
        assertEquals(listOf("notify.show", "var.set", "tts.speak", "vibrate", "volume.set", "torch.set"), actions.take(6).map { it.type })
        assertEquals("4", actions[4].args["level"])
        assertEquals("music", actions[4].args["stream"])
        assertEquals("content://media/song", actions[6].args["path"])
        assertEquals("%MODE = quiet", actions[10].args["condition"])
        assertEquals(16, report.mappedActions.size)
        assertTrue(report.unsupportedActions.isEmpty())
        assertTrue(report.lossyWarnings.any { it.contains("volume") })
    }

    @Test
    fun stripsBenignDoctypeDeclarationsAndImports() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE TaskerData>
                <TaskerData>
                    <Variable><nme>%FOO</nme><val>bar</val></Variable>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 123L,
        )

        assertEquals("bar", report.bundle.variables.single { it.name == "%FOO" }.value)
    }

    @Test
    fun stripsBenignDoctypeWithEmptyInternalSubset() {
        val report = TaskerXmlImporter.parse(
            rawXml = """
                <!DOCTYPE TaskerData [
                ]>
                <TaskerData>
                    <Variable><nme>%FOO</nme><val>bar</val></Variable>
                </TaskerData>
            """.trimIndent(),
            appVersion = "test",
            importedAtEpochMs = 123L,
        )

        assertEquals("bar", report.bundle.variables.single { it.name == "%FOO" }.value)
    }

    @Test
    fun rejectsDoctypeWithExternalDtdReference() {
        val error = runCatching {
            TaskerXmlImporter.parse(
                rawXml = """
                    <!DOCTYPE TaskerData SYSTEM "http://example.com/tasker.dtd">
                    <TaskerData>
                        <Variable><nme>%FOO</nme><val>bar</val></Variable>
                    </TaskerData>
                """.trimIndent(),
                appVersion = "test",
                importedAtEpochMs = 123L,
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message.orEmpty().contains("DOCTYPE"))
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
        assertTrue(error!!.message.orEmpty().contains("XML characters"))
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
        assertTrue(confirmedBundle.profiles.single().requiresRiskAcknowledgement)
        assertTrue(confirmedBundle.metadata.warnings.any { it.contains("disabled by default") })
    }
}
