package com.opentasker.core.scenes

import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task

data class SceneIssue(
    val severity: SceneIssueSeverity,
    val message: String,
)

enum class SceneIssueSeverity {
    WARNING,
    ERROR,
}

object SceneValidator {
    fun validate(scene: Scene, tasks: List<Task>): List<SceneIssue> {
        val taskIds = tasks.map { it.id }.toSet()
        return buildList {
            if (scene.widthDp <= 0 || scene.heightDp <= 0) {
                add(SceneIssue(SceneIssueSeverity.ERROR, "Scene dimensions must be positive."))
            }
            if (scene.elements.isEmpty()) {
                add(SceneIssue(SceneIssueSeverity.WARNING, "Scene has no elements."))
            }
            scene.elements.forEach { element ->
                addAll(validateElement(scene, element, taskIds))
            }
        }
    }

    private fun validateElement(
        scene: Scene,
        element: SceneElement,
        taskIds: Set<Long>,
    ): List<SceneIssue> = buildList {
        val label = "${element.type.name.lowercase()} element ${element.id}"
        if (element.widthDp <= 0 || element.heightDp <= 0) {
            add(SceneIssue(SceneIssueSeverity.ERROR, "$label has non-positive size."))
        }
        if (element.xDp < 0 || element.yDp < 0) {
            add(SceneIssue(SceneIssueSeverity.ERROR, "$label starts outside the scene."))
        }
        if (element.xDp + element.widthDp > scene.widthDp || element.yDp + element.heightDp > scene.heightDp) {
            add(SceneIssue(SceneIssueSeverity.WARNING, "$label extends beyond scene bounds."))
        }
        element.tapTaskId?.let { taskId ->
            if (taskId !in taskIds) {
                add(SceneIssue(SceneIssueSeverity.ERROR, "$label references missing tap task $taskId."))
            }
        }
        element.longPressTaskId?.let { taskId ->
            if (taskId !in taskIds) {
                add(SceneIssue(SceneIssueSeverity.ERROR, "$label references missing long-press task $taskId."))
            }
        }
        SceneElementConfigValidator.validate(element).forEach { issue ->
            add(SceneIssue(SceneIssueSeverity.ERROR, "$label: $issue"))
        }
    }
}

/** Shared config rules used by the editor, import boundary, scene diagnostics, and overlay. */
object SceneElementConfigValidator {
    fun validate(element: SceneElement): List<String> = validate(element.type, element.config)

    fun validate(type: SceneElementType, config: Map<String, String>): List<String> = buildList {
        when (type) {
            SceneElementType.SLIDER -> {
                val min = config["min"]?.toIntOrNull()
                val max = config["max"]?.toIntOrNull()
                val value = (config["value"] ?: config["progress"])?.toIntOrNull()
                if (min == null) add("slider minimum must be an integer")
                if (max == null) add("slider maximum must be an integer")
                if (value == null) add("slider value must be an integer")
                if (min != null && max != null && min > max) add("slider minimum must not exceed maximum")
                if (min != null && max != null && value != null && value !in min..max) {
                    add("slider value must be between minimum and maximum")
                }
            }
            SceneElementType.IMAGE -> {
                if (!SceneImageLoader.isSupportedSource(config["source"].orEmpty())) {
                    add("image source must be a supported content or resource URI")
                }
                val decorative = config["decorative"].equals("true", ignoreCase = true)
                if (!decorative && config["content_description"].orEmpty().isBlank()) {
                    add("image must be marked decorative or have a content description")
                }
            }
            else -> Unit
        }
    }

    fun isValid(element: SceneElement): Boolean = validate(element).isEmpty()
}
