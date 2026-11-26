package com.sysadmindoc.ruledeck.core.actions

import android.content.Context

/**
 * Whether tasks on THIS phone may run commands with more power than the app has: as root
 * (`shell.run` in root mode) and inside Termux (`shell.termux`). Both off by default (白い熊, 2026-11-24).
 *
 * The adb bridge decides who may put a task on the phone and start it; these decide what a task may
 * do once it runs. A task arrives by other roads too — a bundle imported by hand, a restored backup,
 * the shortcut entry, a command over Jami — and without these switches any of them would be root on
 * a Slave or Termux (with its ssh key) on the Master at once. Each phone is given only what it uses:
 * root on the Slaves, Termux on the Master.
 *
 * - **Device-local.** The prefs file is not in `SettingsBackup.PREF_FILES`: no import, restore or
 *   bundle can switch them on. Only a tap on this phone can.
 * - **`commit()`**, as for the other gates: a lost write must not leave a switch in the wrong state.
 * - **Nothing a task runs may write this file** — which is why `shell.run` lost its plain mode (a
 *   command as the app's own user could have switched root on by itself). Shizuku runs as the shell
 *   and Termux as Termux; neither can touch the app's private files.
 * - It does not judge the command: with a switch on, any task on this phone may run any such command.
 *   That is the per-phone trust 白い熊 chose over per-command approval, so that a Slave can still be
 *   repaired from away.
 *
 * `/install`'s own root route (`package.install`) is NOT behind the root switch: it installs only
 * the file it was sent, and updates from away rely on it.
 */
object CommandSwitches {
    private const val PREFS_FILE = "jiyusagyoban_commands"
    private const val KEY_ROOT = "allow_root_commands"
    private const val KEY_TERMUX = "allow_termux_commands"

    const val ROOT_REFUSED =
        "commands as root are switched off on this phone — UI page → Export / Import → “Allow tasks to run commands as root”"
    const val TERMUX_REFUSED =
        "commands in Termux are switched off on this phone — UI page → Export / Import → “Allow tasks to run commands in Termux”"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun rootAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_ROOT, false)
    fun setRootAllowed(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_ROOT, value).commit() }

    fun termuxAllowed(context: Context): Boolean = prefs(context).getBoolean(KEY_TERMUX, false)
    fun setTermuxAllowed(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_TERMUX, value).commit() }
}
