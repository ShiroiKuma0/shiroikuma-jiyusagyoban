package com.opentasker.core.band

/**
 * When 白い熊 really woke, where the watch says otherwise — one instant per morning, the twin of
 * [NightStarts] (白い熊, 2026-11-06).
 *
 * The watch ends a night at the last stage it scored: taken off before getting up, or a doze it
 * counted as sleep, and the night's end is wrong with nothing measured to fix it.
 *
 * Keyed by the morning the WATCH ended the night on, like [NightStarts] — never by the corrected end,
 * so moving a wake time past midnight cannot move the night to another row. A later end adds the gap
 * as unstaged sleep, an earlier one trims the back off: `SleepShape.withNightEdges`.
 */
object NightEnds : NightEdges("night_ends")
