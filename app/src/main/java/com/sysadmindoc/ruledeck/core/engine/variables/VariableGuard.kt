package com.sysadmindoc.ruledeck.core.engine.variables

import android.content.Context

/**
 * Variables that must not be changed from outside — per phone, in two strengths (白い熊, 2026-11-24).
 *
 * The 携帯 relay keeps what decides everything in ordinary variables: whose Jami account may send
 * commands (`%KtOwner`), whether this phone is the Master or a relay (`%KtRole`), and how far it has
 * read (`%KtSeq0`, `%KtSeq1`, `%KtJDone`). A bundle carries variables and an import overwrites what
 * is there — so a bundle could hand the command channel to another account, and even an honest
 * re-import of an old export rewound the cursors and made the relay run old commands again.
 *
 * - [Level.IMPORT] — "imports cannot change it": no bundle, no restore (a restore IS a bundle import)
 *   and no bridge delete touches it. Tasks still write it. For the cursors.
 * - [Level.LOCKED] — "tasks cannot change it either": only 白い熊's hand in the Variables tab does.
 *   For the owner and the role. A write of the value it already holds is let through as the no-op
 *   it is, so a setup task that sets the same value again does not fail.
 *
 * Names are matched without their project scope: a protected name is protected wherever it lives.
 * Device-local — the file is not exported, so nothing imported can lift a protection. The bridge may
 * ADD protections (signed, like every bridge command) and never remove one: tightening from the PC is
 * harmless, loosening is done on the phone.
 */
object VariableGuard {
    enum class Level { NONE, IMPORT, LOCKED }

    private const val PREFS_FILE = "jiyusagyoban_variable_guard"
    private const val KEY_IMPORT = "import_protected"
    private const val KEY_LOCKED = "locked"

    @Volatile private var importProtected: Set<String> = emptySet()
    @Volatile private var locked: Set<String> = emptySet()
    @Volatile private var appContext: Context? = null

    /** Once, before the variable store warms — nothing may be written before the marks are known. */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val p = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        importProtected = p.getStringSet(KEY_IMPORT, emptySet()).orEmpty().toSet()
        locked = p.getStringSet(KEY_LOCKED, emptySet()).orEmpty().toSet()
    }

    /** `%Name`, ` Name ` and the base of a path such as `Name.field[0]` all mean `Name`. */
    fun clean(name: String): String = name.trim().removePrefix("%").takeWhile { it != '.' && it != '[' }

    fun level(name: String): Level = clean(name).let {
        when (it) {
            in locked -> Level.LOCKED
            in importProtected -> Level.IMPORT
            else -> Level.NONE
        }
    }

    /** No import, restore or bridge delete may touch [name]. */
    fun refusesImport(name: String): Boolean = level(name) != Level.NONE

    /** No task may change [name] from [current] to [new]. Writing the value it holds is not a change. */
    fun refusesTaskWrite(name: String, current: String?, new: String?): Boolean =
        level(name) == Level.LOCKED && current != new

    fun lockedMessage(name: String): String =
        "%${clean(name)} is locked on this phone — only the Variables tab can change it"

    /** By hand, on the phone: any level, up or down. */
    fun setLevel(name: String, level: Level) {
        val n = clean(name)
        if (n.isEmpty()) return
        store(
            import = if (level == Level.IMPORT) importProtected + n else importProtected - n,
            lock = if (level == Level.LOCKED) locked + n else locked - n,
        )
    }

    /** From the bridge: [level] is applied only where it is STRONGER than what [name] has. */
    fun raise(name: String, level: Level): Boolean {
        if (level.ordinal <= level(name).ordinal) return false
        setLevel(name, level)
        return true
    }

    @Synchronized
    private fun store(import: Set<String>, lock: Set<String>) {
        importProtected = import
        locked = lock
        appContext?.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)?.edit()
            ?.putStringSet(KEY_IMPORT, import)?.putStringSet(KEY_LOCKED, lock)?.commit()
    }

    /** For tests. */
    internal fun setForTest(import: Set<String>, lock: Set<String>) {
        importProtected = import
        locked = lock
    }
}
