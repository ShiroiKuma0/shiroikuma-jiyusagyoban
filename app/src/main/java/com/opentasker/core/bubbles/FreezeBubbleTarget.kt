package com.opentasker.core.bubbles

import com.opentasker.core.model.ActionSpec

/**
 * The one rule for "which app does this freeze-enabled task act on".
 *
 * Two callers need it and they must not drift: the engine, which queues a bubble when such a task
 * runs, and the `tasks.freezebubbles` picker, which lists the same apps so a bubble can be ticked
 * off again. A second copy of this rule is a picker that offers apps no bubble ever appears for, or
 * — worse — one that hides an app whose bubble keeps coming back.
 */
object FreezeBubbleTarget {

    /**
     * The package a task launches, or `null` when it names none usable.
     *
     * `app.launch` wins over `app.unfreeze` because the generated launcher tasks carry both and the
     * launch is the one that decides what ends up on screen. [expand] resolves `%vars` against
     * whatever store the caller has; a package still carrying a `%` afterwards is deliberately
     * dropped — a bubble (or a picker tile) for the literal text `%App` is worse than none.
     */
    fun packageOf(actions: List<ActionSpec>, expand: (String) -> String): String? {
        val raw = actions.firstOrNull { it.type == "app.launch" }?.args?.get("package")
            ?: actions.firstOrNull { it.type == "app.unfreeze" }?.args?.get("package")
            ?: return null
        val pkg = expand(raw).trim()
        return pkg.takeIf { it.isNotEmpty() && !it.contains('%') }
    }
}
