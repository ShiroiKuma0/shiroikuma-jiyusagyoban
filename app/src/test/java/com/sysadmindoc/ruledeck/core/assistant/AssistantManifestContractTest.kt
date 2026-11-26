package com.sysadmindoc.ruledeck.core.assistant

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
            RuleDeckVoiceInteractionService::class.java.name,
            RuleDeckVoiceSessionService::class.java.name,
        ).forEach { name ->
            assertNotNull(name, services[name])
            assertEquals(name, "android.permission.BIND_VOICE_INTERACTION", services.getValue(name).getAttribute("android:permission"))
        }
        val interaction = services.getValue(RuleDeckVoiceInteractionService::class.java.name)
        val meta = interaction.getElementsByTagName("meta-data").item(0) as Element
        assertEquals("android.voice_interaction", meta.getAttribute("android:name"))
        assertEquals("@xml/voice_interaction_service", meta.getAttribute("android:resource"))
    }

    @Test
    fun theInteractionServiceNamesItsSessionAndRecognizerAndSupportsAssist() {
        val info = xml("src/main/res/xml/voice_interaction_service.xml")
        assertEquals(RuleDeckVoiceSessionService::class.java.name, info.getAttribute("android:sessionService"))
        assertEquals(RuleDeckRecognitionService::class.java.name, info.getAttribute("android:recognitionService"))
        assertEquals("true", info.getAttribute("android:supportsAssist"))
        assertEquals("false", info.getAttribute("android:supportsLaunchVoiceAssistFromKeyguard"))
        assertNotNull(services()[RuleDeckRecognitionService::class.java.name])
    }

    @Test
    fun theRecognizerCantListenBecauseTheAppCantRecord() {
        val permissions = xml("src/main/AndroidManifest.xml").getElementsByTagName("uses-permission")
        val names = (0 until permissions.length).map { (permissions.item(it) as Element).getAttribute("android:name") }
        assertFalse(names.contains("android.permission.RECORD_AUDIO"))
    }
}
