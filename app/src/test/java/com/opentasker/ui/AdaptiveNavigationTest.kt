package com.opentasker.ui

import com.opentasker.ui.screens.usesNavigationRail
import java.nio.file.Files
import java.nio.file.Path
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
        // Scanned across the screens package: the shell owns the saved state and the chrome owns
        // the destination rows, and which file holds which is not the contract being asserted.
        val screensRoot = repoRoot.resolve("app/src/main/java/com/opentasker/ui/screens")
        val source = Files.list(screensRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .toList()
                .joinToString(separator = System.lineSeparator()) { it.readText() }
        }

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
        assertTrue(source.contains("clickable(role = Role.Tab"))
        assertTrue(source.contains("stateDescription = if (selected)"))
        assertTrue(source.contains("heightIn(min = 56.dp)"))
    }
}
