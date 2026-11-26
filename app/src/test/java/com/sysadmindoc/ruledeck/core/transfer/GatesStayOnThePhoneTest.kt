package com.sysadmindoc.ruledeck.core.transfer

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gates — the adb bridge's switch and key, the root and Termux command switches, the automation
 * token — must never travel in an export: a restored backup or an imported ZIP would otherwise open
 * them. `SettingsBackup.PREF_FILES` is an explicit list, so this reads the source and fails the
 * build the day somebody adds one of their files to it.
 */
class GatesStayOnThePhoneTest {
    private val gateFiles = listOf("jiyusagyoban_bridge", "jiyusagyoban_commands", "jiyusagyoban_automation", "jiyusagyoban_shortcut_gate", "jiyusagyoban_variable_guard", "jiyusagyoban_self_seal", "jiyusagyoban_provider_owners")

    @Test
    fun noGateFileIsExported() {
        val backup = sourceFile("app/src/main/java/com/sysadmindoc/ruledeck/core/transfer/SettingsBackup.kt").readText()
        gateFiles.forEach { assertFalse("$it is named in SettingsBackup — a gate would travel in an export", "\"$it\"" in backup) }
    }

    @Test
    fun theGateFilesAreTheOnesTheGatesUse() {
        val sources = listOf(
            "app/src/main/java/com/sysadmindoc/ruledeck/core/transfer/BridgeAuth.kt",
            "app/src/main/java/com/sysadmindoc/ruledeck/core/actions/CommandSwitches.kt",
            "app/src/main/java/com/sysadmindoc/ruledeck/core/transfer/AutomationAuth.kt",
            "app/src/main/java/com/sysadmindoc/ruledeck/widget/ShortcutGate.kt",
            "app/src/main/java/com/sysadmindoc/ruledeck/core/engine/variables/VariableGuard.kt",
            "app/src/main/java/com/sysadmindoc/ruledeck/core/transfer/SelfSeal.kt",
            "app/src/main/java/com/sysadmindoc/ruledeck/core/actions/ProviderOwnerPins.kt",
        ).joinToString("\n") { sourceFile(it).readText() }
        gateFiles.forEach { assertTrue("no gate stores its settings in $it any more — update this test", "\"$it\"" in sources) }
    }

    @Test
    fun runShellHasNoModeThatRunsAsTheApp() {
        // A command as the app's own user could rewrite the files above; the mode was removed.
        val shell = sourceFile("app/src/main/java/com/sysadmindoc/ruledeck/core/actions/ShellActions.kt").readText()
        assertFalse("LocalShell is called without root", Regex("""LocalShell\.exec\([^)]*root = (false|mode)""").containsMatchIn(shell))
    }

    @Test
    fun theBroadcastDoorForSisterTasksStaysClosed() {
        // It knew its caller only by a PendingIntent — a bearer object (audit V-06). `run_task` on the
        // automation provider took its place.
        val manifest = sourceFile("app/src/main/AndroidManifest.xml").readText()
        assertFalse("RUN_TASK_WITH_TARGET is declared again", "action.RUN_TASK_WITH_TARGET\"" in manifest)
    }

    private fun sourceFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, path)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("could not find $path from ${File("").absolutePath}")
    }
}
