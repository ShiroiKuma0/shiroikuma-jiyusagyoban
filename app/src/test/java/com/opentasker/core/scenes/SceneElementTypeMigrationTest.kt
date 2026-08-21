package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.storage.StorageJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneElementTypeMigrationTest {
    private val removedTypeNames = listOf(
        "EDIT_TEXT", "CHECKBOX", "TOGGLE", "NUMBER_PICKER", "SPINNER", "MAP",
        "WEB", "MENU", "VIDEO", "OVAL", "RECTANGLE", "DOODLE",
    )

    private fun sceneJson(typeName: String): String =
        """
        {"id":7,"name":"Old scene","widthDp":320,"heightDp":240,"elements":[
          {"id":1,"type":"$typeName","xDp":10,"yDp":20,"widthDp":100,"heightDp":40,
           "config":{"checked":"true"},"tapTaskId":3,"longPressTaskId":4}
        ]}
        """.trimIndent()

    @Test
    fun onlyRenderableTypesRemain() {
        assertEquals(
            listOf(
                SceneElementType.BUTTON,
                SceneElementType.TEXT,
                SceneElementType.SLIDER,
                SceneElementType.IMAGE,
            ),
            SceneElementType.entries.toList(),
        )
        assertEquals(
            "Every declared type must be one the editor can create",
            SceneElementType.entries.toList(),
            SceneElementDrafts.editableTypes,
        )
    }

    @Test
    fun aSceneSavedWithARemovedTypeStillLoads() {
        removedTypeNames.forEach { typeName ->
            val scene = StorageJson.decodeFromString<Scene>(sceneJson(typeName))
            val element = scene.elements.single()

            assertEquals("$typeName must fall back rather than fail the scene", SceneElementType.TEXT, element.type)
            assertEquals("The element must keep its position", 10, element.xDp)
            assertEquals("The element must keep its size", 100, element.widthDp)
            assertEquals("The element must keep its tap binding", 3L, element.tapTaskId)
            assertEquals("The element must keep its long-press binding", 4L, element.longPressTaskId)
        }
    }

    @Test
    fun aMigratedElementValidatesAndReSavesAsItsFallback() {
        val scene = StorageJson.decodeFromString<Scene>(sceneJson("CHECKBOX"))

        assertTrue(
            "A migrated element must not report config errors",
            SceneElementConfigValidator.validate(scene.elements.single()).isEmpty(),
        )

        val round = StorageJson.decodeFromString<Scene>(StorageJson.encodeToString(scene))
        assertEquals(SceneElementType.TEXT, round.elements.single().type)
        assertTrue("The fallback must be written on save", "\"type\":\"TEXT\"" in StorageJson.encodeToString(scene))
    }

    @Test
    fun aHandEditedLowercaseTypeStillDecodesToItsRealType() {
        // The bundle codec decodes enums case-insensitively for hand-edited documents. A strict
        // match here would silently drop "image" to the TEXT fallback and skip its validation.
        val scene = StorageJson.decodeFromString<Scene>(sceneJson("image"))

        assertEquals(SceneElementType.IMAGE, scene.elements.single().type)
        assertTrue(
            "An invalid image must still be reported rather than migrated away",
            SceneElementConfigValidator.validate(scene.elements.single()).isNotEmpty(),
        )
    }

    @Test
    fun aMigratedElementKeepsTheCaptionItWasAuthoredWith() {
        val overlay = com.opentasker.ProductionSources
            .read("com/opentasker/core/scenes/SceneOverlayService.kt")

        assertTrue(
            "A migrated element must not render as an invisible empty text view",
            "element.config[\"text\"] ?: element.config[\"label\"].orEmpty()" in overlay,
        )
    }

    @Test
    fun anUnknownTypeFromAnyFutureBuildFallsBackTheSameWay() {
        val scene = StorageJson.decodeFromString<Scene>(sceneJson("SOMETHING_NEW"))

        assertEquals(SceneElementType.TEXT, scene.elements.single().type)
    }
}
