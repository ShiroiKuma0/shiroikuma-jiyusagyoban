package com.sysadmindoc.ruledeck.ui.screens

import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {
    private val moduleRoot: Path = listOf(Path.of("."), Path.of("app")).first { it.resolve("src").toFile().exists() }

    @Test
    fun backFromEverySecondaryDestinationReturnsToProfiles() {
        assertFalse(shouldNavigateBackToProfiles(RuleDeckScreen.Profiles))
        RuleDeckScreen.entries
            .filterNot { it == RuleDeckScreen.Profiles }
            .forEach { screen -> assertTrue(shouldNavigateBackToProfiles(screen)) }
    }

    @Test
    fun settingsIsAReachablePrimaryDestinationAndPreservesNavigationState() {
        val source = moduleRoot.resolve("src/main/java/com/sysadmindoc/ruledeck/ui/screens/ActiveAutomationUi.kt").readText()

        assertTrue("RuleDeckScreen.Settings" in source)
        assertTrue("var screenOrdinal by rememberSaveable" in source)
        assertTrue("settingsOnly = settingsOnly" in source)
        assertTrue("RuleDeckScreen.Settings -> permissionScreen(true)" in source)
    }
}
