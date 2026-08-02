package com.opentasker.core.contexts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationComponentMatcherTest {
    @Test
    fun matchesExactAndGlobPatterns() {
        assertTrue(ApplicationComponentMatcher.matches("com.example.PlayerActivity", "com.example.PlayerActivity"))
        assertTrue(ApplicationComponentMatcher.matches("com.example.*Activity", "com.example.PlayerActivity"))
        assertTrue(ApplicationComponentMatcher.matches("com.example.????erActivity", "com.example.PlayerActivity"))
        assertFalse(ApplicationComponentMatcher.matches("com.example.PlayerActivity", "com.example.OtherActivity"))
    }

    @Test
    fun rejectsMissingOrUnsafePatterns() {
        assertFalse(ApplicationComponentMatcher.matches("com.example.*", ""))
        assertFalse(ApplicationComponentMatcher.isValidPattern("com.example/PlayerActivity"))
        assertFalse(ApplicationComponentMatcher.isValidPattern("x".repeat(ApplicationComponentMatcher.MAX_PATTERN_LENGTH + 1)))
    }
}
