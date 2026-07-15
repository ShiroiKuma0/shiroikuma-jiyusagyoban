package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Project is a top-level basket grouping profiles, tasks, and scenes for one area of activity
 * (Tasker-style). Membership is by a nullable `projectId` on each [Profile] / [Task] / [Scene];
 * a null id means the item is "Unfiled". Projects are purely organizational — the automation
 * engine ignores them.
 */
@Serializable
data class Project(
    val id: Long = 0,
    val name: String,
    val color: Int? = null,     // optional ARGB swatch for the project tab
    val sortOrder: Int = 0,     // manual ordering of the project switcher
    val description: String = "",
)

/** Which items the lists show: everything, only Unfiled, or one project. */
sealed interface ProjectFilter {
    data object All : ProjectFilter
    data object Unfiled : ProjectFilter
    data class Of(val projectId: Long) : ProjectFilter
}
