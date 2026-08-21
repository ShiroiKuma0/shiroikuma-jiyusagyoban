package com.opentasker.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDuplicationContractTest {
    @Test
    fun workspaceListsExposeDuplicateActionsForAllThreeEntityKinds() {
        val lists = repoFile("src/main/java/com/opentasker/ui/screens/ActiveAutomationLists.kt").readText()
        val scenes = repoFile("src/main/java/com/opentasker/ui/screens/SceneLibraryCards.kt").readText()
        val screen = repoFile("src/main/java/com/opentasker/ui/screens/SceneLibraryScreen.kt").readText()

        assertTrue("Profile rows must expose a duplicate action", "onDuplicateProfile" in lists)
        assertTrue("Task rows must expose a duplicate action", "onDuplicateTask" in lists)
        assertTrue("Scene rows must expose a duplicate action", "a11y_duplicate_scene" in scenes)
        assertTrue("Scene screen must forward the duplicate action", "onDuplicateScene" in screen)
        assertTrue("Rows should keep the action behind an overflow menu", "DropdownMenuItem" in lists)
    }

    @Test
    fun viewModelUsesCreationHistoryAndFailClosedTaskUndo() {
        // Scanned across the screens package: duplication writes and the undo transaction that
        // reverses them are allowed to live in separate files.
        val source = screensSources()

        assertTrue("Duplication must record a creation snapshot", "private suspend fun recordCreation" in source)
        assertTrue("Creation undo must have an explicit snapshot marker", "snapshot.previousJson.isBlank()" in source)
        assertTrue("Task duplicate undo must remove the created task", "db.taskDao().delete(current)" in source)
        assertTrue("Task duplicate undo must guard newly-created references", "AutomationReferenceIndex.referencesTo" in source)
        assertTrue("Task copies must remap self-bindings", "remapDuplicateSelfReferences" in source)
        assertTrue("Redo must restore a deleted duplicate", "history.markRedone(snapshot.id)" in source)
    }

    private fun repoFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    private fun screensSources(): String {
        val root = listOf(
            File("src/main/java/com/opentasker/ui/screens"),
            File("app/src/main/java/com/opentasker/ui/screens"),
        ).first { it.isDirectory }
        return root.listFiles { file -> file.name.endsWith(".kt") }
            .orEmpty()
            .joinToString(separator = System.lineSeparator()) { it.readText() }
    }
}
