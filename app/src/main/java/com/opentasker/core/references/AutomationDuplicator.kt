package com.opentasker.core.references

import com.opentasker.core.model.ContextExpressionNode
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.validation.InputValidation
import java.util.Locale

/** Pure copy policy shared by the workspace duplicate actions and their tests. */
object AutomationDuplicator {
    fun copyName(original: String, existingNames: Collection<String>): String {
        val cleanOriginal = original.trim().ifBlank { "Untitled" }
        val occupied = existingNames.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        var copyNumber = 1
        while (true) {
            val suffix = if (copyNumber == 1) " (copy)" else " (copy $copyNumber)"
            val prefixLength = (InputValidation.MAX_NAME_LENGTH - suffix.length).coerceAtLeast(1)
            val candidate = cleanOriginal.take(prefixLength).trimEnd() + suffix
            if (candidate.lowercase(Locale.ROOT) !in occupied) return candidate
            copyNumber++
        }
    }

    fun taskPayload(source: Task, name: String): Task {
        var nextActionId = source.actions.maxOfOrNull { it.id.coerceAtLeast(0L) } ?: 0L
        val actions = source.actions.map { action ->
            nextActionId = nextFreshId(nextActionId)
            action.copy(id = nextActionId, args = action.args.toMap())
        }
        return source.copy(id = 0L, name = name, actions = actions)
    }

    fun profilePayload(source: Profile, name: String): Profile = source.copy(
        id = 0L,
        name = name,
        enabled = false,
        requiresRiskAcknowledgement = false,
        lifetimeConsumed = false,
        contexts = source.contexts.map { context -> context.copy(config = context.config.toMap()) },
        contextExpression = source.contextExpression?.deepCopy(),
    )

    fun scenePayload(source: Scene, name: String): Scene {
        var nextElementId = source.elements.maxOfOrNull { it.id.coerceAtLeast(0L) } ?: 0L
        val elements = source.elements.map { element ->
            nextElementId = nextFreshId(nextElementId)
            element.copy(id = nextElementId, config = element.config.toMap())
        }
        return source.copy(id = 0L, name = name, elements = elements)
    }

    private fun nextFreshId(previous: Long): Long = if (previous == Long.MAX_VALUE) 1L else previous + 1L

    private fun ContextExpressionNode.deepCopy(): ContextExpressionNode = copy(
        children = children.map { it.deepCopy() },
    )
}
