package com.opentasker.core.band

import android.content.Context
import androidx.core.content.edit
import com.opentasker.ui.charts.NightScore

/**
 * 白い熊's own weights for the night score — see [NightScore]. Only the ones moved off their default
 * are stored, so a later change to a default reaches every weight 白い熊 never touched.
 *
 * SharedPreferences for the reason the ratings and the bedtimes use it: a few authored bytes, no
 * schema, and listed by name in the settings backup.
 */
object NightScoreWeights {

    private const val PREFS = "night_score_weights"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): Map<NightScore.Part, Int> {
        val p = prefs(context)
        return NightScore.Part.entries.associateWith { part ->
            p.getInt(part.name, part.default).coerceIn(0, NightScore.MAX_WEIGHT)
        }
    }

    /** Replace the whole set; a weight equal to its default is removed rather than stored. */
    fun set(context: Context, weights: Map<NightScore.Part, Int>) {
        prefs(context).edit {
            for (part in NightScore.Part.entries) {
                val w = weights[part] ?: part.default
                if (w == part.default) remove(part.name) else putInt(part.name, w)
            }
        }
    }
}
