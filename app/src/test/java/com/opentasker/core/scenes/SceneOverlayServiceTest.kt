package com.opentasker.core.scenes

import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SceneOverlayServiceTest {

    @Test
    fun sliderValueMapsToConfiguredVariableExtra() {
        val config = SceneElementConfigResolver.slider(
            SceneElement(
                type = SceneElementType.SLIDER,
                xDp = 0, yDp = 0, widthDp = 100, heightDp = 40,
                config = mapOf("min" to "0", "max" to "100", "variable" to "Brightness"),
            ),
        )
        val extras = SceneSliderBinding.taskVariables(config, progress = 42)
        assertEquals(mapOf(AutomationTargetContract.variableExtraName("Brightness") to "42"), extras)
    }

    @Test
    fun sliderWithoutValidVariableFallsBackToDefault() {
        val config = SceneElementConfigResolver.slider(
            SceneElement(
                type = SceneElementType.SLIDER,
                xDp = 0, yDp = 0, widthDp = 100, heightDp = 40,
                config = mapOf("variable" to "1bad name"),
            ),
        )
        assertEquals(SceneSliderBinding.DEFAULT_VARIABLE, SceneSliderBinding.variableName(config))
        assertEquals(
            mapOf(AutomationTargetContract.variableExtraName("value") to "7"),
            SceneSliderBinding.taskVariables(config, progress = 7),
        )
    }

    @Test
    fun taskFiringOverlayControlsFilterObscuredTouches() {
        // Tapjacking guard: the BUTTON and the task-bound SLIDER both fire tasks, so both must
        // drop touches delivered while obscured by a malicious overlay.
        val source = loadMainSource("com/opentasker/core/scenes/SceneOverlayService.kt")
        val guards = source.split("filterTouchesWhenObscured = true").size - 1
        assertTrue(
            "Every task-firing overlay control must set filterTouchesWhenObscured (found $guards)",
            guards >= 2,
        )
    }

    @Test
    fun channelIdIsStable() {
        assertEquals("opentasker.scenes", SceneOverlayService.CHANNEL_ID)
    }

    @Test
    fun channelNameIsResourceBacked() {
        val source = loadMainSource("com/opentasker/core/scenes/SceneOverlayService.kt")
        assertTrue(source.contains("getString(R.string.scene_overlay_channel_name)"))
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

    private fun loadMainSource(relativePath: String): String =
        listOf(
            File("src/main/java/$relativePath"),
            File("app/src/main/java/$relativePath"),
        ).first { it.exists() }.readText()
}
