package com.opentasker.core.scenes

import com.opentasker.core.external.AutomationTargetContract
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import kotlin.math.roundToInt

data class SceneOverlayElementLayout(
    val element: SceneElement,
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
)

data class SceneOverlayLayoutPlan(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val elements: List<SceneOverlayElementLayout>,
)

/**
 * Projects authored scene-space bounds into one bounded overlay content area. The editor uses the
 * same [SceneCanvasProjector], so preview and runtime geometry share one scaling contract.
 */
object SceneOverlayLayoutPlanner {
    fun plan(
        scene: Scene,
        density: Float,
        maxContentWidthPx: Int,
        maxContentHeightPx: Int,
    ): SceneOverlayLayoutPlan {
        val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val requestedWidth = scene.widthDp.coerceAtLeast(1) * safeDensity
        val requestedHeight = scene.heightDp.coerceAtLeast(1) * safeDensity
        val widthLimit = maxContentWidthPx.coerceAtLeast(1).toFloat()
        val heightLimit = maxContentHeightPx.coerceAtLeast(1).toFloat()
        val scale = minOf(1f, widthLimit / requestedWidth, heightLimit / requestedHeight)
        val contentWidth = (requestedWidth * scale).roundToInt().coerceAtLeast(1)
        val contentHeight = (requestedHeight * scale).roundToInt().coerceAtLeast(1)
        val elements = SceneCanvasProjector.project(
            scene = scene,
            canvasWidth = contentWidth.toFloat(),
            canvasHeight = contentHeight.toFloat(),
        ).map { projection ->
            SceneOverlayElementLayout(
                element = projection.element,
                xPx = projection.x.roundToInt(),
                yPx = projection.y.roundToInt(),
                widthPx = projection.width.roundToInt().coerceAtLeast(1),
                heightPx = projection.height.roundToInt().coerceAtLeast(1),
            )
        }
        return SceneOverlayLayoutPlan(contentWidth, contentHeight, elements)
    }
}

data class SceneSliderConfig(
    val label: String,
    val min: Int,
    val max: Int,
    val value: Int,
    val variable: String,
)

object SceneElementConfigResolver {
    fun slider(element: SceneElement): SceneSliderConfig {
        val min = element.config["min"]?.toIntOrNull() ?: 0
        val max = (element.config["max"]?.toIntOrNull() ?: 100).coerceAtLeast(min)
        val value = (
            element.config["value"]?.toIntOrNull()
                ?: element.config["progress"]?.toIntOrNull()
                ?: min
            ).coerceIn(min, max)
        return SceneSliderConfig(
            label = element.config["label"].orEmpty(),
            min = min,
            max = max,
            value = value,
            variable = element.config["variable"].orEmpty(),
        )
    }
}

/**
 * Maps a scene slider's live value onto the variable a bound task receives. A slider with a bound
 * task fires [SceneElement.tapTaskId] when the user releases it, exposing the current progress as a
 * variable so the task can act on it — otherwise the slider is display-only.
 */
object SceneSliderBinding {
    const val DEFAULT_VARIABLE = "value"

    /** The configured variable name if valid, else the [DEFAULT_VARIABLE] fallback. */
    fun variableName(config: SceneSliderConfig): String =
        config.variable.trim().takeIf(AutomationTargetContract::isValidVariableName) ?: DEFAULT_VARIABLE

    /** Broadcast variable extras carrying [progress] under the slider's variable name. */
    fun taskVariables(config: SceneSliderConfig, progress: Int): Map<String, String> =
        mapOf(AutomationTargetContract.variableExtraName(variableName(config)) to progress.toString())
}
