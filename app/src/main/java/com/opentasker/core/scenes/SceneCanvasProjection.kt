package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import kotlin.math.roundToInt

data class SceneCanvasElementProjection(
    val element: SceneElement,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

object SceneCanvasProjector {
    fun projectedHeight(
        scene: Scene,
        canvasWidth: Float,
        minHeight: Float,
        maxHeight: Float,
    ): Float {
        val safeWidth = scene.widthDp.takeIf { it > 0 } ?: 1
        val safeHeight = scene.heightDp.takeIf { it > 0 } ?: 1
        return (canvasWidth * safeHeight / safeWidth).coerceIn(minHeight, maxHeight)
    }

    fun project(
        scene: Scene,
        canvasWidth: Float,
        canvasHeight: Float,
    ): List<SceneCanvasElementProjection> {
        val safeWidth = scene.widthDp.takeIf { it > 0 } ?: 1
        val safeHeight = scene.heightDp.takeIf { it > 0 } ?: 1
        return scene.elements.map { element ->
            SceneCanvasElementProjection(
                element = element,
                x = element.xDp * canvasWidth / safeWidth,
                y = element.yDp * canvasHeight / safeHeight,
                width = element.widthDp * canvasWidth / safeWidth,
                height = element.heightDp * canvasHeight / safeHeight,
            )
        }
    }

    /**
     * Convert a dragged canvas size (in canvas dp) back to scene dp, keeping the element's
     * top-left fixed: the result is clamped to [minSizeDp] and to whatever still fits the panel
     * from the element's current x/y.
     */
    fun sceneSizeForCanvasSize(
        scene: Scene,
        element: SceneElement,
        canvasW: Float,
        canvasH: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        minSizeDp: Int = 8,
    ): Pair<Int, Int> {
        val safeWidth = scene.widthDp.takeIf { it > 0 } ?: 1
        val safeHeight = scene.heightDp.takeIf { it > 0 } ?: 1
        val safeCanvasWidth = canvasWidth.takeIf { it > 0f } ?: 1f
        val safeCanvasHeight = canvasHeight.takeIf { it > 0f } ?: 1f
        val maxW = (safeWidth - element.xDp).coerceAtLeast(minSizeDp)
        val maxH = (safeHeight - element.yDp).coerceAtLeast(minSizeDp)
        return Pair(
            (canvasW * safeWidth / safeCanvasWidth).roundToInt().coerceIn(minSizeDp, maxW),
            (canvasH * safeHeight / safeCanvasHeight).roundToInt().coerceIn(minSizeDp, maxH),
        )
    }

    fun scenePositionForCanvasOffset(
        scene: Scene,
        element: SceneElement,
        canvasX: Float,
        canvasY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
    ): Pair<Int, Int> {
        val safeWidth = scene.widthDp.takeIf { it > 0 } ?: 1
        val safeHeight = scene.heightDp.takeIf { it > 0 } ?: 1
        val safeCanvasWidth = canvasWidth.takeIf { it > 0f } ?: 1f
        val safeCanvasHeight = canvasHeight.takeIf { it > 0f } ?: 1f
        val maxX = (safeWidth - element.widthDp).coerceAtLeast(0)
        val maxY = (safeHeight - element.heightDp).coerceAtLeast(0)
        return Pair(
            (canvasX * safeWidth / safeCanvasWidth).roundToInt().coerceIn(0, maxX),
            (canvasY * safeHeight / safeCanvasHeight).roundToInt().coerceIn(0, maxY),
        )
    }
}
