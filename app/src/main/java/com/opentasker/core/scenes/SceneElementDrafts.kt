package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType

object SceneElementDrafts {
    val editableTypes = listOf(
        SceneElementType.BUTTON,
        SceneElementType.TEXT,
        SceneElementType.EDIT_TEXT,
        SceneElementType.SLIDER,
        SceneElementType.NUMBER_PICKER,
        SceneElementType.CHECKBOX,
        SceneElementType.TOGGLE,
        SceneElementType.SPINNER,
        SceneElementType.IMAGE,
        SceneElementType.PROGRESS,
        SceneElementType.WEB,
        SceneElementType.RECTANGLE,
        SceneElementType.OVAL,
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
        SceneElementType.NUMBER_PICKER -> mapOf(
            "label" to "Number",
            "min" to "0",
            "max" to "100",
            "step" to "1",
            "value" to "0",
        )
        SceneElementType.EDIT_TEXT -> mapOf("label" to "Text field", "value" to "")
        SceneElementType.CHECKBOX -> mapOf("label" to "Checkbox", "value" to "false")
        SceneElementType.TOGGLE -> mapOf("label" to "Toggle", "value" to "false")
        SceneElementType.SPINNER -> mapOf("label" to "Spinner", "options" to "A, B, C", "value" to "A")
        SceneElementType.IMAGE -> mapOf("source" to "Image")
        SceneElementType.PROGRESS -> mapOf("value" to "50", "fillColor" to "#FFFFC107")
        SceneElementType.WEB -> mapOf("html" to "<!DOCTYPE html><html><body style=\"margin:0;background:transparent;color:#FFFFFF00;font-family:sans-serif\"><h2>WebView</h2></body></html>")
        // Shapes: blank fill (transparent), a thin theme-yellow outline so they're visible by default.
        SceneElementType.RECTANGLE -> mapOf("borderWidth" to "1")
        SceneElementType.OVAL -> mapOf("borderWidth" to "1")
        else -> emptyMap()
    }

    private fun defaultSize(type: SceneElementType): Pair<Int, Int> = when (type) {
        SceneElementType.TEXT -> 160 to 40
        SceneElementType.BUTTON -> 160 to 48
        SceneElementType.EDIT_TEXT -> 220 to 64
        SceneElementType.SLIDER -> 220 to 56
        SceneElementType.NUMBER_PICKER -> 200 to 64
        SceneElementType.CHECKBOX -> 200 to 48
        SceneElementType.TOGGLE -> 200 to 48
        SceneElementType.SPINNER -> 200 to 48
        SceneElementType.IMAGE -> 180 to 120
        SceneElementType.PROGRESS -> 220 to 12
        SceneElementType.WEB -> 240 to 160
        SceneElementType.RECTANGLE -> 140 to 70
        SceneElementType.OVAL -> 90 to 90
        else -> 160 to 48
    }

    private fun boundedSize(preferred: Int, sceneSize: Int): Int {
        val max = sceneSize.takeIf { it > 0 } ?: preferred
        return preferred.coerceAtMost(max).coerceAtLeast(1)
    }
}
