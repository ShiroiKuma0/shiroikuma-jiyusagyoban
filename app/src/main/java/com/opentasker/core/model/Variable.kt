package com.opentasker.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Variables are %name slots, expanded at action runtime.
 *
 * Globals contain at least one uppercase letter (%MyVar or %MYVAR) and persist; all-lowercase
 * names (%myvar) are local to a task invocation. This matches Tasker's convention.
 */
@Serializable
data class Variable(
    val name: String,
    val value: String,
    val isGlobal: Boolean,
    val isSecret: Boolean = false,
    @Transient val secretAvailable: Boolean = true,
    val projectId: Long = DEFAULT_PROJECT_ID,
)
