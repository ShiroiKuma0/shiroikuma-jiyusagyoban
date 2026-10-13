package com.opentasker.core.dialog

import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** The result of a task-driven dialog. */
sealed class DialogOutcome {
    /** The user confirmed. [value] is the input/selected text; [index] is the picked list index or -1. */
    data class Confirmed(val value: String, val index: Int = -1) : DialogOutcome()

    /** The user dismissed, the dialog timed out, or it couldn't be shown. */
    data object Cancelled : DialogOutcome()
}

/**
 * Hands a dialog result back from [DialogActivity] to the suspended action that launched it. The
 * action registers a [CompletableDeferred] under a request id, starts the activity with that id, and
 * awaits; the activity completes (or cancels) the deferred when the user responds.
 */
object DialogBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<DialogOutcome>>()

    fun register(id: String): CompletableDeferred<DialogOutcome> =
        CompletableDeferred<DialogOutcome>().also { pending[id] = it }

    /**
     * The dialog's extras, for a dialog started through the shell (`am start` via Shizuku, so it can
     * open in a Huawei window mode): the command line carries only the request id, and the activity
     * collects the rest here. Kept in-process, so nothing but the id ever crosses the shell.
     */
    private val specs = ConcurrentHashMap<String, Bundle>()

    fun putSpec(id: String, extras: Bundle) {
        specs[id] = extras
    }

    fun takeSpec(id: String): Bundle? = specs.remove(id)

    fun complete(id: String, outcome: DialogOutcome) {
        specs.remove(id)
        pending.remove(id)?.complete(outcome)
    }

    fun cancel(id: String) {
        specs.remove(id)
        pending.remove(id)?.complete(DialogOutcome.Cancelled)
    }
}
