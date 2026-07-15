package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneOverlayLayoutPlannerTest {
    @Test
    fun planPreservesAuthoredBoundsAndOverlapAtDeviceDensity() {
        val button = SceneElement(1, SceneElementType.BUTTON, 20, 30, 100, 40)
        val text = SceneElement(2, SceneElementType.TEXT, 50, 45, 80, 30)
        val image = SceneElement(3, SceneElementType.IMAGE, 160, 20, 120, 90)
        val slider = SceneElement(4, SceneElementType.SLIDER, 40, 160, 220, 56)
        val scene = Scene(1, "Panel", 320, 240, listOf(button, text, image, slider))

        val plan = SceneOverlayLayoutPlanner.plan(scene, density = 2f, 1_000, 1_000)

        assertEquals(640, plan.contentWidthPx)
        assertEquals(480, plan.contentHeightPx)
        assertEquals(SceneOverlayElementLayout(button, 40, 60, 200, 80), plan.elements[0])
        assertEquals(SceneOverlayElementLayout(text, 100, 90, 160, 60), plan.elements[1])
        assertEquals(SceneOverlayElementLayout(image, 320, 40, 240, 180), plan.elements[2])
        assertEquals(SceneOverlayElementLayout(slider, 80, 320, 440, 112), plan.elements[3])
        assertTrue(plan.elements[0].xPx + plan.elements[0].widthPx > plan.elements[1].xPx)
        assertTrue(plan.elements[0].yPx + plan.elements[0].heightPx > plan.elements[1].yPx)
    }

    @Test
    fun planScalesOversizedSceneToAvailableWindowWithoutChangingAspectRatio() {
        val scene = Scene(1, "Large", 1_000, 500)

        val plan = SceneOverlayLayoutPlanner.plan(scene, density = 2f, 800, 600)

        assertEquals(800, plan.contentWidthPx)
        assertEquals(400, plan.contentHeightPx)
    }

    @Test
    fun sliderResolverMigratesLegacyProgressAndPrefersCurrentValue() {
        val legacy = SceneElement(
            id = 1,
            type = SceneElementType.SLIDER,
            xDp = 0,
            yDp = 0,
            widthDp = 100,
            heightDp = 40,
            config = mapOf("min" to "10", "max" to "20", "progress" to "17"),
        )
        val current = legacy.copy(config = legacy.config + ("value" to "14"))

        assertEquals(17, SceneElementConfigResolver.slider(legacy).value)
        assertEquals(14, SceneElementConfigResolver.slider(current).value)
    }

    @Test
    fun imageSourcePolicyAllowsOnlyBoundedPersistableLocalUris() {
        assertTrue(SceneImageLoader.isSupportedSource("content://media/picker/image/1"))
        assertTrue(SceneImageLoader.isSupportedSource("android.resource://com.opentasker.app/drawable/icon"))
        assertFalse(SceneImageLoader.isSupportedSource("https://example.com/image.png"))
        assertFalse(SceneImageLoader.isSupportedSource("file:///sdcard/image.png"))
        assertFalse(SceneImageLoader.isSupportedSource("content://" + "x".repeat(SceneImageLoader.MAX_SOURCE_CHARS)))
    }

    @Test
    fun imageSamplingBoundsDecodedDimensionsAndMemory() {
        val sample = SceneImageLoader.computeInSampleSize(
            sourceWidth = 8_000,
            sourceHeight = 6_000,
            targetWidth = 500,
            targetHeight = 400,
        )

        assertEquals(16, sample)
        val pixels = (8_000 / sample).toLong() * (6_000 / sample).toLong()
        assertTrue(pixels <= SceneImageLoader.MAX_DECODE_PIXELS)
    }
}
