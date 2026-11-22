package com.opentasker.core.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Android only offers an app as the digital assistant when its voice interaction service names a
 * session service and a recognizer, and only the system may bind the assistant services.
 */
class AssistantManifestContractTest {
    private fun xml(path: String): Element {
        val file = listOf(File(path), File("app/$path")).first { it.isFile }
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun services(): Map<String, Element> {
        val nodes = xml("src/main/AndroidManifest.xml").getElementsByTagName("service")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("android:name") }
    }

    @Test
    fun onlyTheSystemCanBindTheAssistantServices() {
        val services = services()
        listOf(
            OpenTaskerVoiceInteractionService::class.java.name,
            OpenTaskerVoiceSessionService::class.java.name,
        ).forEach { name ->
            assertNotNull(name, services[name])
            assertEquals(name, "android.permission.BIND_VOICE_INTERACTION", services.getValue(name).getAttribute("android:permission"))
        }
        val interaction = services.getValue(OpenTaskerVoiceInteractionService::class.java.name)
        val meta = interaction.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.voice_interaction", meta.getAttribute("android:name"))
        assertEquals("@xml/voice_interaction_service", meta.getAttribute("android:resource"))
    }

    @Test
    fun theInteractionServiceNamesItsSessionAndRecognizerAndSupportsAssist() {
        val info = xml("src/main/res/xml/voice_interaction_service.xml")
        assertEquals(OpenTaskerVoiceSessionService::class.java.name, info.getAttribute("android:sessionService"))
        assertEquals(OpenTaskerRecognitionService::class.java.name, info.getAttribute("android:recognitionService"))
        assertEquals("true", info.getAttribute("android:supportsAssist"))
        assertEquals("false", info.getAttribute("android:supportsLaunchVoiceAssistFromKeyguard"))
        assertNotNull(services()[OpenTaskerRecognitionService::class.java.name])
    }

}
