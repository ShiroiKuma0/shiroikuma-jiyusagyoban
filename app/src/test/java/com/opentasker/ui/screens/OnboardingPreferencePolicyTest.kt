package com.opentasker.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPreferencePolicyTest {
    @Test
    fun backOrOutsideDismissalDoesNotCompleteOnboarding() {
        // Tapping outside the dialog is not a decision to skip. Treating it as completion left a
        // fresh install with onboarding permanently finished and no route back to it.
        assertFalse(shouldCompleteOnboarding(OnboardingExit.Dismissed))
    }

    @Test
    fun deliberateSkipOrInstallCompletesOnboarding() {
        assertTrue(shouldCompleteOnboarding(OnboardingExit.Skipped))
        assertTrue(shouldCompleteOnboarding(OnboardingExit.InstalledTemplate))
    }

    @Test
    fun recreatedSessionResumesUnlessASelectedTemplateStepWasRestored() {
        assertTrue(shouldLaunchOnboarding(completed = false, selectedTemplateId = null))
        assertFalse(shouldLaunchOnboarding(completed = false, selectedTemplateId = "starter"))
        assertFalse(shouldLaunchOnboarding(completed = true, selectedTemplateId = null))
    }
}
