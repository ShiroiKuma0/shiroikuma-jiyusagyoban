package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import org.junit.Assert.assertEquals
import org.junit.Test

class SceneCanvasProjectorTest {
    @Test
    fun projectedHeightPreservesSceneRatioWithinBounds() {
        val scene = Scene(id = 1, name = "Panel", widthDp = 320, heightDp = 240)

        val height = SceneCanvasProjector.projectedHeight(scene, canvasWidth = 160f, minHeight = 64f, maxHeight = 400f)

        assertEquals(120f, height, 0.001f)
    }

    @Test
    fun projectedHeightClampsExtremeAspectRatios() {
        val scene = Scene(id = 1, name = "Tall", widthDp = 100, heightDp = 1200)

        val height = SceneCanvasProjector.projectedHeight(scene, canvasWidth = 200f, minHeight = 64f, maxHeight = 280f)

        assertEquals(280f, height, 0.001f)
    }

    @Test
    fun projectScalesElementBoundsToCanvas() {
        val element = SceneElement(
            id = 2,
            type = SceneElementType.BUTTON,
            xDp = 80,
            yDp = 60,
            widthDp = 160,
            heightDp = 48,
        )
        val scene = Scene(id = 1, name = "Panel", widthDp = 320, heightDp = 240, elements = listOf(element))

        val projection = SceneCanvasProjector.project(scene, canvasWidth = 160f, canvasHeight = 120f).single()

        assertEquals(40f, projection.x, 0.001f)
        assertEquals(30f, projection.y, 0.001f)
        assertEquals(80f, projection.width, 0.001f)
        assertEquals(24f, projection.height, 0.001f)
        assertEquals(element, projection.element)
    }
}
