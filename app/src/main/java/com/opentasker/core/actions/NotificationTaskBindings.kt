package com.opentasker.core.actions

data class NotificationTaskCandidate(
    val id: Long,
    val name: String,
)

sealed interface NotificationTaskReference {
    data class Id(val taskId: Long) : NotificationTaskReference
    data class LegacyName(val taskName: String) : NotificationTaskReference
    data class Invalid(val rawValue: String) : NotificationTaskReference
}

sealed interface NotificationTaskResolution {
    data class Bound(
        val task: NotificationTaskCandidate,
        val migratedFromLegacyName: Boolean,
    ) : NotificationTaskResolution

    data class Missing(val reference: NotificationTaskReference) : NotificationTaskResolution
    data class Ambiguous(val taskName: String, val matchCount: Int) : NotificationTaskResolution
    data class Invalid(val rawValue: String) : NotificationTaskResolution
}

/** Stable notification-button bindings with a fail-closed compatibility path for legacy names. */
object NotificationTaskBindings {
    const val BUTTON_COUNT = 3

    fun taskIdKey(buttonIndex: Int): String = "button${buttonIndex}_task_id"

    fun legacyTaskNameKey(buttonIndex: Int): String = "button${buttonIndex}_task"

    fun parse(args: Map<String, String>, buttonIndex: Int): NotificationTaskReference? {
        if (buttonIndex !in 1..BUTTON_COUNT) return NotificationTaskReference.Invalid(buttonIndex.toString())

        val idKey = taskIdKey(buttonIndex)
        if (idKey in args) {
            val rawId = args[idKey].orEmpty().trim()
            val taskId = rawId.toLongOrNull()
            return if (taskId != null && taskId > 0) {
                NotificationTaskReference.Id(taskId)
            } else {
                NotificationTaskReference.Invalid(rawId)
            }
        }

        val legacyKey = legacyTaskNameKey(buttonIndex)
        if (legacyKey !in args) return null
        val taskName = args[legacyKey].orEmpty().trim()
        return if (taskName.isNotEmpty()) {
            NotificationTaskReference.LegacyName(taskName)
        } else {
            NotificationTaskReference.Invalid(taskName)
        }
    }

    fun resolve(
        reference: NotificationTaskReference,
        candidates: List<NotificationTaskCandidate>,
    ): NotificationTaskResolution = when (reference) {
        is NotificationTaskReference.Id -> {
            val task = candidates.firstOrNull { it.id == reference.taskId }
            if (task == null) {
                NotificationTaskResolution.Missing(reference)
            } else {
                NotificationTaskResolution.Bound(task, migratedFromLegacyName = false)
            }
        }

        is NotificationTaskReference.LegacyName -> {
            val matches = candidates.filter { it.name == reference.taskName }
            when (matches.size) {
                0 -> NotificationTaskResolution.Missing(reference)
                1 -> NotificationTaskResolution.Bound(matches.single(), migratedFromLegacyName = true)
                else -> NotificationTaskResolution.Ambiguous(reference.taskName, matches.size)
            }
        }

        is NotificationTaskReference.Invalid -> NotificationTaskResolution.Invalid(reference.rawValue)
    }

    fun failureMessage(resolution: NotificationTaskResolution): String = when (resolution) {
        is NotificationTaskResolution.Bound -> error("Bound task references do not have a failure message")
        is NotificationTaskResolution.Missing -> when (val reference = resolution.reference) {
            is NotificationTaskReference.Id -> "Task ID ${reference.taskId} no longer exists"
            is NotificationTaskReference.LegacyName -> "Task '${reference.taskName}' was not found"
            is NotificationTaskReference.Invalid -> "Task binding '${reference.rawValue}' is invalid"
        }
        is NotificationTaskResolution.Ambiguous ->
            "Task name '${resolution.taskName}' matches ${resolution.matchCount} tasks; reselect by ID"
        is NotificationTaskResolution.Invalid -> "Task binding '${resolution.rawValue}' is invalid"
    }
}
