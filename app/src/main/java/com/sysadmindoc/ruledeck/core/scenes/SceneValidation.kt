package com.sysadmindoc.ruledeck.core.scenes

import com.sysadmindoc.ruledeck.core.model.Scene
import com.sysadmindoc.ruledeck.core.model.SceneElement
import com.sysadmindoc.ruledeck.core.model.SceneElementType
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.i18n.tr

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
                add(SceneIssue(SceneIssueSeverity.ERROR, tr("Scene dimensions must be positive.")))
            }
            if (scene.elements.isEmpty()) {
                add(SceneIssue(SceneIssueSeverity.WARNING, tr("Scene has no elements.")))
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
        val label = tr("%1\$s element %2\$s", element.type.name.lowercase(), element.id)
        if (element.widthDp <= 0 || element.heightDp <= 0) {
            add(SceneIssue(SceneIssueSeverity.ERROR, tr("%1\$s has non-positive size.", label)))
        }
        if (element.xDp < 0 || element.yDp < 0) {
            add(SceneIssue(SceneIssueSeverity.ERROR, tr("%1\$s starts outside the scene.", label)))
        }
        if (element.xDp + element.widthDp > scene.widthDp || element.yDp + element.heightDp > scene.heightDp) {
            add(SceneIssue(SceneIssueSeverity.WARNING, tr("%1\$s extends beyond scene bounds.", label)))
        }
        element.tapTaskId?.let { taskId ->
            if (taskId !in taskIds) {
                add(SceneIssue(SceneIssueSeverity.ERROR, tr("%1\$s references missing tap task %2\$s.", label, taskId)))
            }
        }
        element.longPressTaskId?.let { taskId ->
            if (taskId !in taskIds) {
                add(SceneIssue(SceneIssueSeverity.ERROR, tr("%1\$s references missing long-press task %2\$s.", label, taskId)))
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
                if (min == null) add(tr("slider minimum must be an integer"))
                if (max == null) add(tr("slider maximum must be an integer"))
                if (value == null) add(tr("slider value must be an integer"))
                if (min != null && max != null && min > max) add(tr("slider minimum must not exceed maximum"))
                if (min != null && max != null && value != null && value !in min..max) {
                    add(tr("slider value must be between minimum and maximum"))
                }
            }
            SceneElementType.IMAGE -> {
                if (!SceneImageLoader.isSupportedSource(config["source"].orEmpty())) {
                    add(tr("image source must be a supported content or resource URI"))
                }
                val decorative = config["decorative"].equals("true", ignoreCase = true)
                if (!decorative && config["content_description"].orEmpty().isBlank()) {
                    add(tr("image must be marked decorative or have a content description"))
                }
            }
            else -> Unit
        }
    }

    fun isValid(element: SceneElement): Boolean = validate(element).isEmpty()
}
