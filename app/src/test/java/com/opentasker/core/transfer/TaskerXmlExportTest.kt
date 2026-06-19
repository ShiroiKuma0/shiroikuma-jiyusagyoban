package com.opentasker.core.transfer

import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerXmlExportTest {

    @Test
    fun exportsMappableActionsAndSkipsUnmappable() {
        val task = Task(
            id = 1,
            name = "Test task",
            actions = listOf(
                ActionSpec(type = "notify.show", args = mapOf("title" to "Hello", "text" to "World")),
                ActionSpec(type = "var.set", args = mapOf("name" to "%MODE", "value" to "on")),
                ActionSpec(type = "torch.set", args = mapOf("state" to "on")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertEquals(1, report.exportedTaskCount)
        assertEquals(1, report.skippedActions.size)
        assertEquals("torch.set", report.skippedActions[0].actionType)
        assertTrue(report.xml.contains("<code>548</code>"))
        assertTrue(report.xml.contains("<code>547</code>"))
        assertTrue(report.xml.contains("Hello"))
        assertTrue(report.xml.contains("%MODE"))
    }

    @Test
    fun exportsTimeContextsWithClockParts() {
        val profile = Profile(
            id = 1,
            name = "Morning",
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.TIME, mapOf("start" to "08:00", "end" to "17:30")),
            ),
        )
        val report = TaskerXmlExporter.export(listOf(profile), emptyList())

        assertEquals(1, report.exportedProfileCount)
        assertTrue(report.xml.contains("<fh>8</fh>"))
        assertTrue(report.xml.contains("<fm>0</fm>"))
        assertTrue(report.xml.contains("<th>17</th>"))
        assertTrue(report.xml.contains("<tm>30</tm>"))
    }

    @Test
    fun exportsApplicationContexts() {
        val profile = Profile(
            id = 1,
            name = "App trigger",
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.APPLICATION, mapOf("package" to "com.example.app")),
            ),
        )
        val report = TaskerXmlExporter.export(listOf(profile), emptyList())

        assertTrue(report.xml.contains("<package>com.example.app</package>"))
    }

    @Test
    fun exportsVariables() {
        val variable = Variable(name = "MODE", value = "silent", isGlobal = true)
        val report = TaskerXmlExporter.export(emptyList(), emptyList(), listOf(variable))

        assertEquals(1, report.exportedVariableCount)
        assertTrue(report.xml.contains("%MODE"))
        assertTrue(report.xml.contains("silent"))
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val task = Task(
            id = 1,
            name = "Test & <task>",
            actions = listOf(
                ActionSpec(type = "log", args = mapOf("message" to "value < 5 & done")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("Test &amp; &lt;task&gt;"))
        assertTrue(report.xml.contains("value &lt; 5 &amp; done"))
    }

    @Test
    fun warnsForUnexportableContextTypes() {
        val profile = Profile(
            id = 1,
            name = "Location",
            enterTaskId = 1,
            contexts = listOf(
                ContextSpec(ContextType.LOCATION, mapOf("lat" to "40.0", "lon" to "-74.0")),
            ),
        )
        val report = TaskerXmlExporter.export(listOf(profile), emptyList())

        assertTrue(report.warnings.any { it.contains("LOCATION") })
    }

    @Test
    fun producesValidXmlStructure() {
        val report = TaskerXmlExporter.export(
            listOf(Profile(id = 1, name = "P", enterTaskId = 1)),
            listOf(Task(id = 1, name = "T")),
        )

        assertTrue(report.xml.startsWith("<?xml"))
        assertTrue(report.xml.contains("<TaskerData"))
        assertTrue(report.xml.contains("</TaskerData>"))
    }

    @Test
    fun flowWaitConvertsMillisToSecondsAndRemainder() {
        val task = Task(
            id = 1,
            name = "Wait",
            actions = listOf(
                ActionSpec(type = "flow.wait", args = mapOf("millis" to "2500")),
            ),
        )
        val report = TaskerXmlExporter.export(emptyList(), listOf(task))

        assertTrue(report.xml.contains("<code>30</code>"))
    }
}
