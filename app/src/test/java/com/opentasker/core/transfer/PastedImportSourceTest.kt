package com.opentasker.core.transfer

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PastedImportSourceTest {
    private val byteOrderMark = "\uFEFF"

    private val taskerXml = """
        <TaskerData sr="" dvi="1" tv="6.3.13">
          <Task sr="task1">
            <nme>Pasted task</nme>
            <Action sr="act0" ve="7">
              <code>523</code>
              <Str sr="arg0" ve="3">Title</Str>
              <Str sr="arg1" ve="3">Body</Str>
            </Action>
          </Task>
        </TaskerData>
    """.trimIndent()

    @Test
    fun aTaskerXmlPasteIsRecognisedAndAJsonPasteIsUnchanged() {
        assertEquals(PastedImportKind.TASKER_XML, PastedImportSource.classify(taskerXml))
        assertEquals(
            PastedImportKind.TASKER_XML,
            PastedImportSource.classify(byteOrderMark + "  \n" + taskerXml),
        )
        assertEquals(
            PastedImportKind.OPEN_TASKER_JSON,
            PastedImportSource.classify("""{"schemaVersion":1,"tasks":[]}"""),
        )
        assertEquals(PastedImportKind.OPEN_TASKER_JSON, PastedImportSource.classify("   "))
    }

    @Test
    fun aPastedTaskerTaskReachesTheSamePlannerAsTheDocumentPicker() {
        val body = PastedImportSource.requireTaskerXmlWithinBudget(taskerXml)
        val report = TaskerXmlImporter.parse(rawXml = body, appVersion = "0.0.0-test")
        val preview = TaskerImportPlanner.preview(report)

        assertEquals(1, preview.sourceTaskCount)
        assertEquals(1, preview.importTaskCount)
        assertEquals(1, preview.mappedActionCount)
        assertTrue("A pasted Tasker task must be importable", preview.canImport)

        val confirmed = TaskerImportPlanner.confirmedBundle(report)
        assertTrue(
            "Imported profiles stay disabled whichever way the XML arrived",
            confirmed.profiles.none { it.enabled },
        )
    }

    @Test
    fun anOversizePasteIsRefusedBeforeAnythingIsParsed() {
        val oversize = "<TaskerData>" + "x".repeat(PastedImportSource.TASKER_XML_TEXT_MAX_BYTES)

        assertThrows(IllegalArgumentException::class.java) {
            PastedImportSource.requireTaskerXmlWithinBudget(oversize)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PastedImportSource.requireTaskerXmlWithinBudget("   ")
        }
    }

    @Test
    fun aDoctypeInAPasteStillAbortsBeforeRoom() {
        val doctype = "<!DOCTYPE TaskerData [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" + taskerXml

        assertThrows(Exception::class.java) {
            TaskerXmlImporter.parse(
                rawXml = PastedImportSource.requireTaskerXmlWithinBudget(doctype),
                appVersion = "0.0.0-test",
            )
        }
    }

    @Test
    fun theDocumentPickerRoutesBothFormatsThroughOneEntryPoint() {
        // Upstream 0.2.88 puts the format sniff behind its paste dialog. The fork's shell has no
        // paste-import dialog — PastedImportSource is reachable only from the document picker here —
        // so the contract is asserted where the fork actually routes: one picker entry point that
        // sends XML to the Tasker planner and anything else to the MacroDroid one.
        val viewModel = ProductionSources.read("com/opentasker/ui/screens/ActiveAutomationViewModel.kt")

        assertTrue("One entry point must read both formats", "readBoundedTaskerOrMacroDroid(" in viewModel)
        assertTrue("Tasker XML must reach its planner", "TaskerImportPlanner.preview(report)" in viewModel)
        assertTrue("MacroDroid must reach its planner", "MacroDroidImportPlanner.preview(report)" in viewModel)
    }
}
