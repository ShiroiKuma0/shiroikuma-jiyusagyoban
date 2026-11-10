package com.opentasker.ui.charts.huawei

/**
 * The steps a band sync goes through, in order — what every “whole run” bar counts against: the
 * sync dialog on the board and the progress row on the 健康 dashboard (白い熊, 2026-11-08: one bar
 * for the step, a second for the whole run, the same in both places).
 *
 * Only the record reading has an item count of its own (the watch says how many records a window
 * holds before they are fetched); sleep, the dictionary and the ECG do not, so the overall bar moves
 * a step at a time there and smoothly only through the records. A band that is not a watch skips
 * the last two steps, and its bar jumps to the end — a jump forward, never a false promise.
 */
internal object SyncSteps {
    val SYNC = listOf(
        "starting", "connecting", "handshake", "device", "satellite",
        "counting", "reading", "writing", "sleep", "dictionary", "ecg",
    )
    val WORKOUTS = listOf("starting", "connecting", "handshake", "device", "listing", "workouts")
    val STEP_OF = com.opentasker.ui.charts.Loc("step %d / %d", "段階 %d / %d")

    /** The run's place in [steps], 0–1, and the step it is on (1-based); null for a phase not in it. */
    fun overall(steps: List<String>, phase: String, percent: Int?): Pair<Float, Int>? {
        val at = steps.indexOf(phase).takeIf { it >= 0 } ?: return null
        val within = if (phase == "reading" || phase == "workouts") (percent ?: 0) / 100f else 0f
        return ((at + within) / steps.size).coerceIn(0f, 1f) to (at + 1)
    }
}
