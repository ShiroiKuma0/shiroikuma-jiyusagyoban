package com.opentasker.core.capabilities

import android.os.SystemClock

/**
 * Stops the permission pre-flight dialog from re-popping once the user has already been told.
 *
 * The pre-flight blocks a task and raises a modal whenever a required permission is missing. That is
 * right **once**. It is wrong on every subsequent firing, and 白い熊's workspace fires tasks by the
 * second — so on 2026-08-08, with the accessibility service enabled-but-crashed, the modal came back
 * over and over and two tasks sat in it for 120 s each.
 *
 * So a dialog that has been SHOWN quiets its requirements immediately: the task still refuses to run
 * and still logs why, it just does not raise the modal again for a while. Tapping "Open … settings"
 * *shortens* the window rather than lengthening it — the user is actively fixing it and should get an
 * honest answer as soon as they come back.
 *
 * Process-local and elapsed-time based on purpose: it is a UI courtesy, not state worth persisting,
 * and it must not survive a reboot as a silent block.
 */
object CapabilityPrompt {

    /**
     * After the dialog has simply been acknowledged.
     *
     * Long enough that a per-second task cannot turn a real block into a wall of modals, short enough
     * that a permission left ungranted is raised again while the user still remembers the task.
     */
    private const val QUIET_AFTER_SHOWN_MS = 10 * 60_000L

    /**
     * After the user was sent to the settings page.
     *
     * Deliberately SHORTER than [QUIET_AFTER_SHOWN_MS]: they went to grant it, so the next attempt
     * should tell them the truth about whether it worked rather than staying quiet for ten minutes.
     */
    private const val QUIET_AFTER_SETTINGS_MS = 3 * 60_000L

    private val quietUntil = HashMap<CapabilityRequirement, Long>()

    /** The dialog naming [req] has been put on screen — do not raise it again just yet. */
    fun markShown(req: CapabilityRequirement) = quiet(req, QUIET_AFTER_SHOWN_MS)

    /** The user has been sent to the settings page for [req] — re-check sooner than a plain OK. */
    fun markSentToSettings(req: CapabilityRequirement) = quiet(req, QUIET_AFTER_SETTINGS_MS)

    private fun quiet(req: CapabilityRequirement, forMs: Long) {
        synchronized(quietUntil) { quietUntil[req] = SystemClock.elapsedRealtime() + forMs }
    }

    /** True while [req]'s dialog is being held back. The task is still blocked — only the modal is not shown. */
    fun isQuiet(req: CapabilityRequirement): Boolean = synchronized(quietUntil) {
        val until = quietUntil[req] ?: return false
        if (SystemClock.elapsedRealtime() >= until) {
            quietUntil.remove(req)
            false
        } else {
            true
        }
    }

    /** Every missing requirement is in its quiet window, so raising the dialog would only be noise. */
    fun allQuiet(reqs: List<CapabilityRequirement>): Boolean = reqs.isNotEmpty() && reqs.all { isQuiet(it) }

    /** Granting is checked live elsewhere; this just drops the courtesy once the user is back. */
    fun clear(req: CapabilityRequirement) {
        synchronized(quietUntil) { quietUntil.remove(req) }
    }
}
