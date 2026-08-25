package com.opentasker.core.engine

/**
 * Classifies a free-form run-log source string into a stable typed source key plus a
 * human-readable label, so the Run Log can filter by trigger surface without regex parsing.
 *
 * The typed key is persisted in `RunLogEntity.source`; the label (e.g. the profile name or tile
 * slot) is persisted in `RunLogEntity.sourceLabel`. Older rows written before the typed columns
 * existed keep a null key and fall back to the parsed free-form message.
 */
object RunLogSource {
    const val PROFILE = "profile"
    const val EXTERNAL_INTENT = "external_intent"
    const val QUICK_SETTINGS_TILE = "quick_settings_tile"
    const val MANUAL_RUN = "manual_run"
    const val NOTIFICATION_ACTION = "notification_action"
    const val WIDGET = "widget"
    const val SHORTCUT = "shortcut"
    const val LOCALE_PLUGIN = "locale_plugin"
    const val SCENE_OVERLAY = "scene_overlay"
    const val APP_FUNCTION = "app_function"
    const val SCHEDULER = "scheduler"
    const val OTHER = "other"

    data class Classified(val key: String, val label: String?)

    fun classify(source: String): Classified {
        val trimmed = source.trim()
        return when {
            trimmed.startsWith("Profile:", ignoreCase = true) ->
                Classified(PROFILE, trimmed.afterColon())
            trimmed.startsWith("Quick Settings Tile:", ignoreCase = true) ->
                Classified(QUICK_SETTINGS_TILE, trimmed.afterColon())
            trimmed.equals("External intent", ignoreCase = true) ->
                Classified(EXTERNAL_INTENT, null)
            trimmed.equals("Manual run", ignoreCase = true) ->
                Classified(MANUAL_RUN, null)
            trimmed.equals("Notification action", ignoreCase = true) ->
                Classified(NOTIFICATION_ACTION, null)
            trimmed.equals("Widget", ignoreCase = true) ->
                Classified(WIDGET, null)
            trimmed.equals("Shortcut", ignoreCase = true) ->
                Classified(SHORTCUT, null)
            trimmed.equals("Locale plugin", ignoreCase = true) ->
                Classified(LOCALE_PLUGIN, null)
            trimmed.equals("Scene overlay", ignoreCase = true) ->
                Classified(SCENE_OVERLAY, null)
            trimmed.equals("App function", ignoreCase = true) ->
                Classified(APP_FUNCTION, null)
            trimmed.equals("Scheduled trigger", ignoreCase = true) ->
                Classified(SCHEDULER, "Missed trigger")
            trimmed.isBlank() ->
                Classified(OTHER, null)
            else ->
                Classified(OTHER, trimmed)
        }
    }

    /** Human-readable label for a typed key, for filter chips and accessibility. */
    fun displayName(key: String?): String = when (key) {
        PROFILE -> "Profile"
        EXTERNAL_INTENT -> "External intent"
        QUICK_SETTINGS_TILE -> "Quick Settings tile"
        MANUAL_RUN -> "Manual run"
        NOTIFICATION_ACTION -> "Notification action"
        WIDGET -> "Widget"
        SHORTCUT -> "Shortcut"
        LOCALE_PLUGIN -> "Locale plugin"
        SCENE_OVERLAY -> "Scene overlay"
        APP_FUNCTION -> "App function"
        SCHEDULER -> "Scheduler"
        OTHER -> "Other"
        else -> "Unknown"
    }

    private fun String.afterColon(): String? =
        substringAfter(':').trim().takeIf { it.isNotBlank() }
}
