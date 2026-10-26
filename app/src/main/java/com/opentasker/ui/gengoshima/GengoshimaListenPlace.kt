package com.opentasker.ui.gengoshima

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a listening session was left, so the next one opens there instead of on the picker
 * (白い熊, 2026-10-24: closing and coming back used to mean choosing the islands all over again).
 *
 * It is written whenever the playing screen moves to a new item and again on 閉じる, so a session
 * EMUI kills mid-island resumes too. It is dropped when the queue plays to its end, and when
 * 「次回は続きから」 is turned off, which is how a session is started afresh.
 *
 * The queue itself is not stored: it is rebuilt from [islandIds] in their order with the same mode
 * and source setting, and play resumes at the first item of [sentenceId]. A sentence added to an
 * island meanwhile simply joins the rebuilt queue; one deleted meanwhile starts the queue over.
 *
 * Spaced rotation counts an island as reviewed only when every sentence of it was heard, and a
 * resumed island is heard across several sessions, so [heard] carries the sentences already heard
 * from one session to the next, and [reviewed] the islands already counted, so none counts twice.
 */
@Serializable
internal data class ListenPlace(
    val mode: String,
    val islandIds: List<Long>,
    val englishAfter: Boolean,
    /** The sentence to resume ON: the one being heard, or the next one if that was already said. */
    val sentenceId: Long,
    val stopAfterSentence: Boolean = false,
    val heard: List<Long> = emptyList(),
    val reviewed: List<Long> = emptyList(),
    val savedAt: Long = 0,
) {
    companion object {
        private const val PREFS = "gengoshima_listen_place"
        private const val KEY = "place"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(context: Context): ListenPlace? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }

        fun save(context: Context, place: ListenPlace) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, json.encodeToString(serializer(), place))
                .apply()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        }
    }
}
