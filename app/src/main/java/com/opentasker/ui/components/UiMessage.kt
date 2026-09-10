package com.opentasker.ui.components

/**
 * One transient message for the floating bar, and optionally a way to take it back.
 *
 * The channel used to carry a bare `String`, which is why every destructive action in this app
 * announced itself and then left you with nothing to do about it. 白い熊 deleted a 58-action task by
 * mistake on 2026-09-10 and the only route back was a workspace mirror that happened to be current;
 * had it been a day stale the restore would have quietly reinstated an older task and reported
 * success. [undoToken] is what closes that gap.
 *
 * A token rather than a lambda because this crosses a `Channel` into the composition: the view model
 * keeps the work, the bar keeps a name for it, and the bar cannot accidentally hold a reference to a
 * view model that has since gone.
 */
data class UiMessage(
    val text: String,
    /** Non-null = the bar offers Undo, and pressing it calls back with exactly this token. */
    val undoToken: String? = null,
)
