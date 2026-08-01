package com.opentasker.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppRepositoryTest {
    private val apps = listOf(
        InstalledApp("com.spotify.music", "Spotify"),
        InstalledApp("com.android.chrome", "Chrome"),
        InstalledApp("org.example.notes", "Notes"),
    )

    @Test
    fun searchMatchesLabelsAndPackagesAndSortsDeterministically() {
        assertEquals(listOf("Chrome"), InstalledAppSearch.filter(apps, "chrome").map(InstalledApp::label))
        assertEquals(listOf("Spotify"), InstalledAppSearch.filter(apps, "spotify.music").map(InstalledApp::label))
        assertEquals(listOf("Chrome", "Notes", "Spotify"), InstalledAppSearch.filter(apps, "").map(InstalledApp::label))
    }

    @Test
    fun packagePolicySupportsManualFallbackWithoutAcceptingMalformedTargets() {
        assertTrue(PackageNamePolicy.isValid("com.example.app"))
        assertTrue(PackageNamePolicy.isValid("io.example_2.plugin"))
        assertFalse(PackageNamePolicy.isValid("example"))
        assertFalse(PackageNamePolicy.isValid("com.example.bad-name"))
        assertFalse(PackageNamePolicy.isValid("com.example. app"))
    }
}
