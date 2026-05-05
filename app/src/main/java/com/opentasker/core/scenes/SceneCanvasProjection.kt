package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement

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
}
