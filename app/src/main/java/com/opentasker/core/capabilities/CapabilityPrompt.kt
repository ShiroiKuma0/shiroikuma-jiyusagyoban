package com.opentasker.core.capabilities

import android.os.SystemClock

/**
 * Stops the permission pre-flight dialog from re-popping on top of the System settings page it just
 * sent the user to.
 *
 * The pre-flight blocks a task and raises a modal whenever a required permission is missing. That is
 * right once — but a per-minute profile re-fires within the minute, so the modal used to come straight
 * back over Settings, and it read as "the button did nothing". Tapping "Open … settings" now starts a
 * quiet window for that requirement: the task still refuses to run and still logs why, it just does not
 * raise the dialog again until the user has had time to grant it.
 *
 * Process-local and elapsed-time based on purpose: it is a UI courtesy, not state worth persisting, and
 * it must not survive a reboot as a silent block.
 */
object CapabilityPrompt {

    /** Long enough to find the toggle on an EMUI settings page, short enough not to hide a real block. */
    private const val QUIET_MS = 3 * 60_000L

    private val quietUntil = HashMap<CapabilityRequirement, Long>()

    /** The user has been sent to the settings page for [req] — hold the dialog back for a while. */
    fun markSentToSettings(req: CapabilityRequirement) {
        synchronized(quietUntil) { quietUntil[req] = SystemClock.elapsedRealtime() + QUIET_MS }
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
