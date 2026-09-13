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
     *
     * This is the bubble's IDENTITY — which app it is for, what its icon and label show. It is not
     * what the bubble freezes; see [packagesOf].
     */
    fun packageOf(actions: List<ActionSpec>, expand: (String) -> String): String? =
        packagesOf(actions, expand).firstOrNull()

    /**
     * **Every** package the task thaws, launch target first — what tapping the bubble must re-freeze.
     *
     * ## Why one package was never enough
     *
     * A launcher task may thaw a companion app before the one it opens, and two of 白い熊's do:
     *
     * ```
     * RaiPay -- [1707][7107]       unfreeze cz.rb.app.smartphonebanking → unfreeze cz.raiffeisen.mobilepay → launch mobilepay
     * ČSOB Smart -- [1707][7107]   unfreeze cz.csob.smartklic          → unfreeze cz.csob.smart          → launch smart
     * ```
     *
     * Taking only the first package re-froze one of the two and left the companion thawed — silently,
     * because a bubble disappearing looks exactly like a bubble that did its job. The set to freeze is
     * not a thing to configure: it is **what the launch thawed**, so it is read off the launch task and
     * can never drift from it.
     *
     * Launch target first so [packageOf] keeps returning what it always did, and so a caller printing
     * the list leads with the app 白い熊 actually opened.
     */
    fun packagesOf(actions: List<ActionSpec>, expand: (String) -> String): List<String> {
        fun usable(raw: String?): String? = raw?.let(expand)?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains('%') }

        val launched = usable(actions.firstOrNull { it.type == "app.launch" }?.args?.get("package"))
        val thawed = actions.filter { it.type == "app.unfreeze" }.mapNotNull { usable(it.args["package"]) }
        // LinkedHashSet: order is the contract above, and a task that both thaws and launches the
        // same package (every generated launcher does) must not name it twice.
        return (listOfNotNull(launched) + thawed).toCollection(LinkedHashSet()).toList()
    }
}
