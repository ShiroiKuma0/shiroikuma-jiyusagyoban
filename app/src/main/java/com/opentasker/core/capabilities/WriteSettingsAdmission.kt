package com.opentasker.core.capabilities

import com.opentasker.core.model.ActionSpec

/**
 * `brightness.set` and `screen.timeout` fail at runtime without WRITE_SETTINGS. Install still
 * creates those templates disabled, but enabling the profile or tapping Run used to dispatch
 * anyway and log a Failure that looked like an engine bug (issue #12).
 */
object WriteSettingsAdmission {
    val ACTION_IDS: Set<String> = setOf("brightness.set", "screen.timeout")

    fun requiredBy(actions: List<ActionSpec>): Boolean =
        actions.any { it.type in ACTION_IDS }

    fun blocked(actions: List<ActionSpec>, canWriteSettings: Boolean): Boolean =
        requiredBy(actions) && !canWriteSettings
}
