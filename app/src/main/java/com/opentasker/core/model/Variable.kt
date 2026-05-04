package com.opentasker.core.model

import kotlinx.serialization.Serializable

/**
 * Variables are %name slots, expanded at action runtime.
 *
 * Globals are uppercase (%MYVAR) and persist; locals are lowercase (%myvar) and live
 * only for the duration of a task invocation. This matches Tasker's convention.
 */
@Serializable
data class Variable(
    val name: String,
    val value: String,
    val isGlobal: Boolean,
)
