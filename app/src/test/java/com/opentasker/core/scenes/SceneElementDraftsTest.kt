package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneElementDraftsTest {
    @Test
    fun nextElementIdUsesHighestExistingId() {
        val scene = Scene(
            id = 1,
            name = "Panel",
            widthDp = 320,
            heightDp = 240,
            elements = listOf(
                SceneElement(id = 4, type = SceneElementType.TEXT, xDp = 0, yDp = 0, widthDp = 20, heightDp = 20),
                SceneElement(id = 9, type = SceneElementType.BUTTON, xDp = 0, yDp = 0, widthDp = 20, heightDp = 20),
            ),
        )

        assertEquals(10L, SceneElementDrafts.nextElementId(scene))
    }

    @Test
    fun defaultElementStaysInsideSceneBounds() {
        val scene = Scene(id = 1, name = "Small", widthDp = 120, heightDp = 80)

        val element = SceneElementDrafts.defaultElement(scene, SceneElementType.IMAGE)

        assertTrue(element.widthDp in 1..scene.widthDp)
        assertTrue(element.heightDp in 1..scene.heightDp)
        assertTrue(element.xDp >= 0)
        assertTrue(element.yDp >= 0)
        assertTrue(element.xDp + element.widthDp <= scene.widthDp)
        assertTrue(element.yDp + element.heightDp <= scene.heightDp)
    }

    @Test
    fun defaultSliderIncludesBoundedValueConfig() {
        val scene = Scene(id = 1, name = "Panel", widthDp = 320, heightDp = 240)

        val element = SceneElementDrafts.defaultElement(scene, SceneElementType.SLIDER)

        assertEquals(SceneElementType.SLIDER, element.type)
        assertEquals("Slider", element.config["label"])
        assertEquals("0", element.config["min"])
        assertEquals("100", element.config["max"])
        assertEquals("50", element.config["value"])
    }


    // Dropped in the 0.2.81 upstream sync: upstream's scene-image draft validation (an image draft
    // starts with an empty source and must decode before save) is not adopted — the fork keeps its own
    // SceneElementDrafts, where a new image element starts with a placeholder source.

    @Test
    fun sliderConfigRequiresValueWithinBounds() {
        val invalid = SceneElement(
            type = SceneElementType.SLIDER,
            xDp = 0,
            yDp = 0,
            widthDp = 100,
            heightDp = 40,
            config = mapOf("min" to "10", "max" to "20", "value" to "21"),
        )

        assertTrue(SceneElementConfigValidator.validate(invalid).any { "between" in it })
    }
}
