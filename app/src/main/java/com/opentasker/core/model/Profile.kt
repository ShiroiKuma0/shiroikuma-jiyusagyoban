package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * A Profile binds one or more Contexts to a Task.
 * The profile is "active" while ALL of its contexts match.
 * Activation runs [enterTaskId]; deactivation runs [exitTaskId] (if set).
 */
@Serializable
data class Profile(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val contexts: List<ContextSpec> = emptyList(),
    val enterTaskId: Long,
    val exitTaskId: Long? = null,
    val cooldownSec: Int = 0,
)
