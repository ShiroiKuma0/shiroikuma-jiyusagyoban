package com.opentasker.core.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Tasker importer against the XML parser Android actually ships.
 *
 * The JVM unit tests cannot cover this. They run on desktop Xerces, which accepts the Apache
 * `disallow-doctype-decl` feature URI; Android's Expat-backed factories throw
 * `SAXNotRecognizedException` for it. Treating that feature as mandatory made every on-device
 * import fail with "Disallow Doctype Decl" regardless of file content, while every JVM test
 * stayed green — issue #5. These tests fail on the pre-fix importer and pass on the fixed one,
 * which is the only place that difference is observable.
 */
@RunWith(AndroidJUnit4::class)
class TaskerXmlImportInstrumentedTest {
    private val appVersion = "instrumented-test"

    private fun taskerXml(prolog: String = ""): String =
        """<?xml version="1.0" encoding="UTF-8"?>
$prolog<TaskerData sr="" dvi="1" tv="6.1.11">
    <Task sr="task1">
        <cdate>1690000000000</cdate>
        <id>1</id>
        <nme>Morning routine</nme>
        <Action sr="act0" ve="7">
            <code>547</code>
            <Str sr="arg0" ve="3">%TestVar</Str>
            <Str sr="arg1" ve="3">hello</Str>
        </Action>
    </Task>
</TaskerData>
"""

    @Test
    fun importsPlainTaskerExportOnAndroidParser() {
        val report = TaskerXmlImporter.parse(taskerXml(), appVersion)

        assertEquals(1, report.bundle.tasks.size)
        assertEquals("Morning routine", report.bundle.tasks.first().name)
    }

    @Test
    fun importsTaskerExportCarryingBenignDoctype() {
        val report = TaskerXmlImporter.parse(taskerXml("<!DOCTYPE TaskerData>\n"), appVersion)

        assertEquals(1, report.bundle.tasks.size)
        assertEquals("Morning routine", report.bundle.tasks.first().name)
    }

    @Test
    fun importsTaskerExportWithEmptyInternalSubset() {
        val report = TaskerXmlImporter.parse(taskerXml("<!DOCTYPE TaskerData []>\n"), appVersion)

        assertEquals(1, report.bundle.tasks.size)
    }

    @Test
    fun rejectsDoctypeDeclaringEntities() {
        val hostile = taskerXml(
            "<!DOCTYPE TaskerData [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n",
        )

        try {
            TaskerXmlImporter.parse(hostile, appVersion)
            fail("An entity-declaring doctype must be refused, not parsed")
        } catch (expected: Exception) {
            // The XXE surface stays closed on-device, not only on the desktop JVM.
        }
    }

    @Test
    fun rejectsDoctypeReferencingExternalDtd() {
        val hostile = taskerXml("<!DOCTYPE TaskerData SYSTEM \"http://example.invalid/t.dtd\">\n")

        try {
            TaskerXmlImporter.parse(hostile, appVersion)
            fail("An external-DTD doctype must be refused, not parsed")
        } catch (expected: Exception) {
            // Expected.
        }
    }

    @Test
    fun parserFeatureRejectionIsNotFatal() {
        // The regression itself: an unrecognised parser feature must never abort an import.
        // If any factory in the chain reverts to a required feature, this throws with the
        // Apache feature URI in its message, exactly as users saw on-device.
        try {
            TaskerXmlImporter.parse(taskerXml(), appVersion)
        } catch (e: Exception) {
            val message = generateSequence<Throwable>(e) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" | ")
            assertTrue(
                "Import failed on a parser feature the platform does not recognise: $message",
                !message.contains("disallow-doctype-decl") && !message.contains("Disallow"),
            )
            throw e
        }
    }
}
