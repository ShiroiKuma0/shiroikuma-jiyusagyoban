package com.opentasker.core.actions

import com.opentasker.core.band.TrainingSessions
import com.opentasker.core.engine.Action
import com.opentasker.core.engine.ActionCategory
import com.opentasker.core.engine.ActionContext
import com.opentasker.core.engine.ActionResult

/**
 * `Mark Training Session` — bookend a workout the band cannot see on its own.
 *
 * Strength work leaves almost no trace in this hardware: 白い熊's real 20-minute lifting session on
 * 2026-08-09 produced three spot heart-rate readings (82, 93, 90 bpm — the 71st, 95th and 91st
 * percentile of an ordinary waking day), two periodic readings *below* resting, 17–36 steps a minute,
 * and no temperature change whatsoever. Automatic detection from that is not possible honestly; see
 * [TrainingSessions] for the numbers and the failed attempt.
 *
 * Marking it costs one tap, and this is an automation app — so bind this to a launcher shortcut, a
 * widget, or a Profile, and the 回復 card's load figure starts counting what actually happened.
 *
 * ## Modes
 *
 * - `start` — opens a session at the current instant.
 * - `end` — closes it. A session left open longer than four hours is discarded rather than closed:
 *   that is a forgotten tap, and a six-hour "workout" would corrupt the week's load far worse than
 *   losing one session.
 * - `log` with `minutes` — records a complete session that ended just now, for the case where 白い熊
 *   remembers afterwards. One tap instead of two.
 * - `toggle` — start if nothing is open, end if something is. The one to bind to a single button.
 */
class BandSessionAction : Action {
    override val id = "band.session"
    override val category = ActionCategory.SYSTEM

    override suspend fun run(ctx: ActionContext, args: Map<String, String>): ActionResult {
        val app = ctx.app
        val now = System.currentTimeMillis()
        val label = args["label"]?.trim().orEmpty()
        val store = args["store"]?.trim().orEmpty()
        val minutes = args["minutes"]?.trim()?.toIntOrNull()
        val mode = args["mode"]?.trim()?.lowercase()?.ifEmpty { null }
            ?: if (minutes != null) "log" else "toggle"

        // Success carries no message in this engine, so the outcome goes to the variable 白い熊
        // named — which is what a task would read anyway.
        fun report(message: String): ActionResult {
            if (store.isNotEmpty()) ctx.variables.set(store, message)
            return ActionResult.Success
        }

        fun discarded(): String =
            "nothing recorded — either no session was open, or it ran under " +
                "${TrainingSessions.MIN_SESSION_MINUTES} min or over ${TrainingSessions.MAX_OPEN_MINUTES} min"

        return when (mode) {
            "start" -> {
                TrainingSessions.start(app, now, label)
                report("session started")
            }
            "end" -> {
                val session = TrainingSessions.end(app, now)
                    ?: return report(discarded())
                report("session recorded: ${session.minutes} min")
            }
            "log" -> {
                val length = minutes
                    ?: return ActionResult.Failure("log needs minutes")
                if (length < TrainingSessions.MIN_SESSION_MINUTES || length > TrainingSessions.MAX_OPEN_MINUTES) {
                    return ActionResult.Failure(
                        "minutes must be ${TrainingSessions.MIN_SESSION_MINUTES}..${TrainingSessions.MAX_OPEN_MINUTES}",
                    )
                }
                val session = TrainingSessions.log(
                    app,
                    TrainingSessions.Session(now - length * 60_000L, now, label),
                )
                report("session recorded: ${session.minutes} min")
            }
            "toggle" -> {
                if (TrainingSessions.openStart(app) != null) {
                    val session = TrainingSessions.end(app, now)
                        ?: return report(discarded())
                    report("session recorded: ${session.minutes} min")
                } else {
                    TrainingSessions.start(app, now, label)
                    report("session started")
                }
            }
            "pick" -> {
                // Opens the chart with a span picker. The Action cannot know when the session was —
                // only 白い熊 can, by looking at the shape — so this hands over rather than guessing.
                com.opentasker.ui.charts.BandChartsActivity.open(
                    app,
                    metric = com.opentasker.ui.charts.MetricSpecs.KEY_MARK_SESSION,
                    spanMinutes = null,
                )
                report("opened the session picker")
            }
            "clear" -> {
                TrainingSessions.clear(app)
                report("all marked sessions cleared")
            }
            else -> ActionResult.Failure("mode must be start, end, log, toggle, pick or clear")
        }
    }
}
