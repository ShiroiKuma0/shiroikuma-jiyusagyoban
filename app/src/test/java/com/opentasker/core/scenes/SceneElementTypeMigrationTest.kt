package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.SceneElementTypeSerializer
import com.opentasker.core.storage.StorageJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RETIRED IN PART: upstream 0.2.88 cut [SceneElementType] to the four types ITS overlay draws and
 * added three tests asserting that only those four remain, that a scene naming a removed type
 * decodes as TEXT, and that saving it writes that fallback back. The fork keeps all eighteen — its
 * own SceneActivity, SceneElementDrafts and UiEnumLabels render and offer them — so those
 * assertions would demand a one-way rewrite of every saved element of a "removed" type into a text
 * box, silently, on first read.
 *
 * What survives is the half that is still true here: a type name this build does not know decodes
 * as TEXT instead of failing the whole scene, and the decode is case-insensitive so a hand-edited
 * bundle is not quietly downgraded.
 */
class SceneElementTypeMigrationTest {
    private fun sceneJson(typeName: String): String =
        """
        {"id":7,"name":"Old scene","widthDp":320,"heightDp":240,"elements":[
          {"id":1,"type":"$typeName","xDp":10,"yDp":20,"widthDp":100,"heightDp":40,
           "config":{"checked":"true"},"tapTaskId":3,"longPressTaskId":4}
        ]}
        """.trimIndent()

    @Test
    fun theTypesTheForkKeepsStillRoundTrip() {
        listOf("EDIT_TEXT", "CHECKBOX", "TOGGLE", "NUMBER_PICKER", "SPINNER", "MAP",
               "WEB", "MENU", "VIDEO", "OVAL", "RECTANGLE", "DOODLE", "PROGRESS", "METEOR")
            .forEach { name ->
                val scene = StorageJson.decodeFromString(Scene.serializer(), sceneJson(name))
                assertEquals(
                    "$name must survive a round trip, not fall back to TEXT",
                    SceneElementType.valueOf(name),
                    scene.elements.single().type,
                )
            }
    }

    @Test
    fun aTypeThisBuildDoesNotKnowDecodesAsTextRatherThanFailingTheScene() {
        val scene = StorageJson.decodeFromString(Scene.serializer(), sceneJson("SOMETHING_A_FUTURE_BUILD_ADDED"))
        val element = scene.elements.single()
        assertEquals(SceneElementType.TEXT, element.type)
        // The element keeps everything but its type, so the scene is not lost with it.
        assertEquals(10, element.xDp)
        assertEquals(3L, element.tapTaskId)
    }

    @Test
    fun aHandEditedLowercaseTypeResolvesToTheTypeItNames() {
        assertEquals(
            SceneElementType.PROGRESS,
            StorageJson.decodeFromString(SceneElementTypeSerializer, "\"progress\""),
        )
    }
}
