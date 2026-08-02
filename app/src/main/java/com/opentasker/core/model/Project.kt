package com.opentasker.core.model

import kotlinx.serialization.Serializable

const val DEFAULT_PROJECT_ID = 1L

/** A named workspace boundary for profiles, tasks, scenes, and variables. */
@Serializable
data class Project(
    val id: Long = 0,
    val name: String,
    val position: Int = 0,
)
