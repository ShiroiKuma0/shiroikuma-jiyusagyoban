package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType

object SceneElementDrafts {
    val editableTypes = listOf(
        SceneElementType.BUTTON,
        SceneElementType.TEXT,
        SceneElementType.SLIDER,
        SceneElementType.CHECKBOX,
        SceneElementType.TOGGLE,
        SceneElementType.IMAGE,
    )

    fun nextElementId(scene: Scene): Long = (scene.elements.maxOfOrNull { it.id } ?: 0L) + 1L

    fun defaultElement(scene: Scene, type: SceneElementType = SceneElementType.BUTTON): SceneElement {
        val (preferredWidth, preferredHeight) = defaultSize(type)
        val width = boundedSize(preferredWidth, scene.widthDp)
        val height = boundedSize(preferredHeight, scene.heightDp)
        val x = ((scene.widthDp - width) / 2).coerceAtLeast(0)
        val y = ((scene.heightDp - height) / 2).coerceAtLeast(0)
        return SceneElement(
            id = nextElementId(scene),
            type = type,
            xDp = x,
            yDp = y,
            widthDp = width,
            heightDp = height,
            config = defaultConfig(type),
        )
    }

    fun defaultConfig(type: SceneElementType): Map<String, String> = when (type) {
        SceneElementType.TEXT -> mapOf("text" to "Text")
        SceneElementType.BUTTON -> mapOf("label" to "Button")
        SceneElementType.SLIDER -> mapOf(
            "label" to "Slider",
            "min" to "0",
            "max" to "100",
            "value" to "50",
        )
        SceneElementType.CHECKBOX -> mapOf("label" to "Checkbox", "value" to "false")
        SceneElementType.TOGGLE -> mapOf("label" to "Toggle", "value" to "false")
        SceneElementType.IMAGE -> mapOf("source" to "Image")
        else -> emptyMap()
    }

    private fun defaultSize(type: SceneElementType): Pair<Int, Int> = when (type) {
        SceneElementType.TEXT -> 160 to 40
        SceneElementType.BUTTON -> 160 to 48
        SceneElementType.SLIDER -> 220 to 56
        SceneElementType.CHECKBOX -> 200 to 48
        SceneElementType.TOGGLE -> 200 to 48
        SceneElementType.IMAGE -> 180 to 120
        else -> 160 to 48
    }

    private fun boundedSize(preferred: Int, sceneSize: Int): Int {
        val max = sceneSize.takeIf { it > 0 } ?: preferred
        return preferred.coerceAtMost(max).coerceAtLeast(1)
    }
}
