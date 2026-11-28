package com.sysadmindoc.ruledeck.core.shizuku

import android.content.Context
import com.sysadmindoc.ruledeck.core.actions.CommandSwitches
import java.io.File

/**
 * Whether this phone can do through root what the app otherwise does through Shizuku.
 *
 * A rooted phone needs no Shizuku (白い熊, 2026-11-28 — both Slaves had theirs removed that day, and
 * every task holding a `Run Shell` step was refused by the pre-run check for want of it). “Rooted”
 * means two things at once, and both are 白い熊's own decisions:
 *
 * - an `su` program is on this phone, and
 * - the switch “Allow tasks to run commands as root” is on ([CommandSwitches.rootAllowed]) — the
 *   per-phone gate for everything a task runs as root. Off, nothing here goes near `su`.
 *
 * Whether the root manager really grants the app is not asked here: asking means running `su`, which
 * can put the manager's prompt on the screen from a mere status check. A refusal shows when a command
 * runs, in that command's own error.
 */
object RootAccess {
    @Volatile private var app: Context? = null
    @Volatile private var suFound: Boolean = false

    /** Once, when the app starts. Before it, and in a unit test, the phone counts as not rooted. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    /** An `su` program exists. A hit is remembered; a miss is looked for again (root can be added). */
    fun suPresent(): Boolean {
        if (suFound) return true
        val dirs = (System.getenv("PATH").orEmpty().split(':') + SU_DIRS).filter { it.isNotBlank() }.distinct()
        val found = dirs.any { dir -> runCatching { File(dir, "su").exists() }.getOrDefault(false) }
        if (found) suFound = true
        return found
    }

    /** Rooted, and tasks may use it: commands meant for Shizuku may go through `su` instead. */
    fun usable(): Boolean {
        val context = app ?: return false
        return CommandSwitches.rootAllowed(context) && suPresent()
    }

    private val SU_DIRS = listOf("/system/bin", "/system/xbin", "/product/bin", "/sbin", "/debug_ramdisk", "/su/bin")
}
