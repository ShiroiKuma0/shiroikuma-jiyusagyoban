package com.opentasker.core.contexts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackStateEventsTest {
    @Test
    fun packageFilterMatchesOnePackageInTheBoundedSessionList() {
        assertTrue(MediaPlaybackStateEvents.packageMatches("com.example.player,com.example.radio", "com.example.radio"))
        assertFalse(MediaPlaybackStateEvents.packageMatches("com.example.player", "com.example.radio"))
    }

    @Test
    fun stateMatcherSupportsPlaybackLevelAndPackageFilters() {
        val state = mapOf(
            "media_active" to "true",
            "media_package" to "com.example.player,com.example.radio",
        )

        assertTrue(stateMatches("media_active=true", state))
        assertTrue(stateMatches("media_package=com.example.radio", state))
        assertFalse(stateMatches("media_active=false", state))
    }
}
