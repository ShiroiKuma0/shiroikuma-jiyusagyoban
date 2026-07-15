package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import kotlin.math.roundToInt

/** Pure scene mutations shared by editor gestures and behavior tests. */
object SceneEditorMutations {
    fun replaceElement(scene: Scene, index: Int, element: SceneElement): Scene {
        if (index !in scene.elements.indices) return scene
        return scene.copy(
            elements = scene.elements.mapIndexed { candidateIndex, existing ->
                if (candidateIndex == index) element else existing
            },
        )
    }

    /** Moves the selection as one rigid group, clamping one shared delta at the scene edges. */
    fun moveSelected(
        scene: Scene,
        selectedIndices: Set<Int>,
        requestedDeltaX: Int,
        requestedDeltaY: Int,
    ): Scene {
        val selected = scene.elements.filterIndexed { index, _ -> index in selectedIndices }
        if (selected.isEmpty()) return scene

        val minDeltaX = selected.maxOf { -it.xDp }
        val maxDeltaX = selected.minOf { scene.widthDp - it.xDp - it.widthDp }
        val minDeltaY = selected.maxOf { -it.yDp }
        val maxDeltaY = selected.minOf { scene.heightDp - it.yDp - it.heightDp }
        val deltaX = requestedDeltaX.coerceIn(minDeltaX.coerceAtMost(maxDeltaX), maxDeltaX)
        val deltaY = requestedDeltaY.coerceIn(minDeltaY.coerceAtMost(maxDeltaY), maxDeltaY)

        return scene.copy(
            elements = scene.elements.mapIndexed { index, element ->
                if (index in selectedIndices) {
                    element.copy(xDp = element.xDp + deltaX, yDp = element.yDp + deltaY)
                } else {
                    element
                }
            },
        )
    }

    fun resizeElementFromCanvasDelta(
        scene: Scene,
        element: SceneElement,
        deltaCanvasX: Float,
        deltaCanvasY: Float,
        canvasWidth: Float,
        canvasHeight: Float,
        minimumSizeDp: Int,
    ): SceneElement {
        val safeCanvasWidth = canvasWidth.takeIf { it.isFinite() && it > 0f } ?: 1f
        val safeCanvasHeight = canvasHeight.takeIf { it.isFinite() && it > 0f } ?: 1f
        val widthDelta = (deltaCanvasX * scene.widthDp.coerceAtLeast(1) / safeCanvasWidth).roundToInt()
        val heightDelta = (deltaCanvasY * scene.heightDp.coerceAtLeast(1) / safeCanvasHeight).roundToInt()
        val maximumWidth = (scene.widthDp - element.xDp).coerceAtLeast(1)
        val maximumHeight = (scene.heightDp - element.yDp).coerceAtLeast(1)
        val minimumWidth = minimumSizeDp.coerceIn(1, maximumWidth)
        val minimumHeight = minimumSizeDp.coerceIn(1, maximumHeight)
        return element.copy(
            widthDp = (element.widthDp + widthDelta).coerceIn(minimumWidth, maximumWidth),
            heightDp = (element.heightDp + heightDelta).coerceIn(minimumHeight, maximumHeight),
        )
    }
}
