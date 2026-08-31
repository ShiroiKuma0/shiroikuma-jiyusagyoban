package com.opentasker.core.storage

import android.content.Context
import androidx.core.content.edit
import com.opentasker.core.model.ProjectFilter

/** Persists the currently-selected project filter so it survives restarts. */
class ProjectSelectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ProjectFilter = when (val raw = prefs.getString(KEY, ALL) ?: ALL) {
        ALL -> ProjectFilter.All
        UNFILED -> ProjectFilter.Unfiled
        else -> raw.toLongOrNull()?.let { ProjectFilter.Of(it) } ?: ProjectFilter.All
    }

    fun save(filter: ProjectFilter) {
        prefs.edit {
            putString(
                KEY,
                when (filter) {
                    ProjectFilter.All -> ALL
                    ProjectFilter.Unfiled -> UNFILED
                    is ProjectFilter.Of -> filter.projectId.toString()
                },
            )
        }
    }

    private companion object {
        const val PREFS = "project_selection"
        const val KEY = "filter"
        const val ALL = "all"
        const val UNFILED = "unfiled"
    }
}
