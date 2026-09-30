package com.opentasker.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every dialog wears the yellow border (白い熊, 2026-09-30) — which holds only while every screen
 * calls this app's own `AlertDialog` and not Material's. A screen that imports Material's directly
 * gets a borderless dialog and no error, so this looks for exactly that import.
 */
class BorderedDialogTest {

    @Test
    fun noScreenCallsMaterialsOwnAlertDialog() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java") }
            .firstOrNull { it.isDirectory }
            ?: File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "BorderedAlertDialog.kt" }
            .filter { f -> f.readLines().any { it.trim() == "import androidx.compose.material3.AlertDialog" } }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue("these import Material's borderless AlertDialog: $offenders", offenders.isEmpty())
    }
}
