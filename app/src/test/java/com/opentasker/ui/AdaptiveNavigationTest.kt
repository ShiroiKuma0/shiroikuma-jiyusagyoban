package com.opentasker.ui

import com.opentasker.ui.screens.usesNavigationRail
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.readText

class AdaptiveNavigationTest {
    @Test
    fun compactWidthsKeepBottomNavigation() {
        assertFalse(usesNavigationRail(599))
    }

    @Test
    fun mediumAndExpandedWidthsUseRailWithAllDestinationsAvailable() {
        assertTrue(usesNavigationRail(600))
        assertTrue(usesNavigationRail(840))
    }

    @Test
    fun largeFontUsesRailEarlierToKeepNavigationLabelsDiscoverable() {
        assertFalse(usesNavigationRail(559, fontScale = 1.29f))
        assertTrue(usesNavigationRail(560, fontScale = 1.3f))
        assertTrue(usesNavigationRail(599, fontScale = 2f))
    }

    @Test
    fun resizeAndFoldStateUsesSaveableStateAndAccessibleNavigationSemantics() {
        val repoRoot = listOf(Path.of("."), Path.of(".."))
            .first { Files.exists(it.resolve("README.md")) && Files.exists(it.resolve("app/build.gradle.kts")) }
            .toAbsolutePath()
            .normalize()
        // Anchored on the declarations rather than on filenames: the shell owns the saved state and
        // the chrome owns the destination rows, but scanning the whole package let an unrelated
        // dialog satisfy assertions the shell is supposed to carry.
        val screensRoot = repoRoot.resolve("app/src/main/java/com/opentasker/ui/screens")
        val screens = Files.list(screensRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .toList()
                .associate { it.fileName.toString() to it.readText() }
        }

        fun owner(declaration: String): String {
            val owners = screens.filterValues { it.contains(declaration) }.keys
            assertEquals("Expected exactly one screens file to declare `$declaration`, found $owners", 1, owners.size)
            return owners.single()
        }

        val source = screens.getValue(owner("fun ActiveAutomationUi("))
        val navigationItem = screens.getValue(owner("fun OpenTaskerNavigationItem("))

        assertTrue(source.contains("LocalConfiguration.current"))
        listOf(
            "screenOrdinal",
            "selectedProjectId",
            "taskDialogId",
            "profileDialogId",
            "contextEditProfileId",
            "bundleTextImportDraft",
        ).forEach { stateKey ->
            assertTrue("$stateKey must survive resize/fold recreation", source.contains("$stateKey by rememberSaveable"))
        }
        assertTrue(navigationItem.contains("clickable(role = Role.Tab"))
        assertTrue(navigationItem.contains("stateDescription = if (selected)"))
        assertTrue(navigationItem.contains("heightIn(min = 56.dp)"))
    }
}
