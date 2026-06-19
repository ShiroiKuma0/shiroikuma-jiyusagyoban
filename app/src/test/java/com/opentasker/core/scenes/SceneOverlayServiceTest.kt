package com.opentasker.core.scenes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SceneOverlayServiceTest {

    @Test
    fun channelIdIsStable() {
        assertEquals("opentasker.scenes", SceneOverlayService.CHANNEL_ID)
    }

    @Test
    fun channelNameIsHumanReadable() {
        assertEquals("Scene overlays", SceneOverlayService.CHANNEL_NAME)
    }

    @Test
    fun notificationIdDoesNotCollideWithEngine() {
        // AutomationService uses 1001; scene overlay must differ
        assertEquals(1002, SceneOverlayService.NOTIFICATION_ID)
    }

    @Test
    fun intentExtraKeysAreNamespaced() {
        assertTrue(
            "EXTRA_SCENE_ID should be namespaced under com.opentasker",
            SceneOverlayService.EXTRA_SCENE_ID.startsWith("com.opentasker."),
        )
        assertTrue(
            "EXTRA_SCENE_JSON should be namespaced under com.opentasker",
            SceneOverlayService.EXTRA_SCENE_JSON.startsWith("com.opentasker."),
        )
    }

    @Test
    fun serviceIsDeclaredInManifestNotExported() {
        val manifest = loadMainManifest()
        val services = manifest.getElementsByTagName("service")
        val overlayService = (0 until services.length)
            .asSequence()
            .map { services.item(it) }
            .firstOrNull {
                it.attributes.getNamedItem("android:name")?.nodeValue ==
                    "com.opentasker.core.scenes.SceneOverlayService"
            }

        requireNotNull(overlayService) { "SceneOverlayService not found in AndroidManifest.xml" }

        assertEquals(
            "SceneOverlayService must not be exported",
            "false",
            overlayService.attributes.getNamedItem("android:exported").nodeValue,
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
