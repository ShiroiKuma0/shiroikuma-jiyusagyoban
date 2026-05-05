package com.opentasker.core.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AutomationTargetContractTest {
    @Test
    fun validatesVariableNamesForExternalExtras() {
        assertTrue(AutomationTargetContract.isValidVariableName("User"))
        assertTrue(AutomationTargetContract.isValidVariableName("task_value_1"))
        assertFalse(AutomationTargetContract.isValidVariableName("1bad"))
        assertFalse(AutomationTargetContract.isValidVariableName("bad-name"))
        assertFalse(AutomationTargetContract.isValidVariableName(""))
    }

    @Test
    fun buildsDocumentedVariableExtraNames() {
        assertEquals(
            "com.opentasker.var.User",
            AutomationTargetContract.variableExtraName("User"),
        )
    }

    @Test
    fun rejectsInvalidVariableExtraNames() {
        val error = runCatching {
            AutomationTargetContract.variableExtraName("bad-name")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun automationPermissionIsSignatureScoped() {
        val manifest = loadMainManifest()
        val permissions = manifest.getElementsByTagName("permission")
        val automationPermission = (0 until permissions.length)
            .asSequence()
            .map { permissions.item(it) }
            .first { it.attributes.getNamedItem("android:name").nodeValue == AutomationTargetContract.PERMISSION }

        assertEquals(
            "signature",
            automationPermission.attributes.getNamedItem("android:protectionLevel").nodeValue,
        )
    }

    @Test
    fun automationTargetReceiverRequiresAutomationPermission() {
        val manifest = loadMainManifest()
        val receivers = manifest.getElementsByTagName("receiver")
        val targetReceiver = (0 until receivers.length)
            .asSequence()
            .map { receivers.item(it) }
            .first {
                it.attributes.getNamedItem("android:name").nodeValue ==
                    "com.opentasker.core.external.AutomationTargetReceiver"
            }

        assertEquals(
            "true",
            targetReceiver.attributes.getNamedItem("android:exported").nodeValue,
        )
        assertEquals(
            AutomationTargetContract.PERMISSION,
            targetReceiver.attributes.getNamedItem("android:permission").nodeValue,
        )
    }

    private fun loadMainManifest() =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                listOf(
                    File("src/main/AndroidManifest.xml"),
                    File("app/src/main/AndroidManifest.xml"),
                ).first { it.exists() }
            )
            .documentElement
}
