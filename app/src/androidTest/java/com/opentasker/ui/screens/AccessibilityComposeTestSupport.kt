package com.opentasker.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.test.ext.junit.rules.ActivityScenarioRule

@Suppress("DEPRECATION")
internal fun createAccessibilityComposeRule(): AndroidComposeTestRule<
    ActivityScenarioRule<ComponentActivity>,
    ComponentActivity,
> = createAndroidComposeRule<ComponentActivity>().also { rule ->
    rule.enableAccessibilityChecks()
}

internal fun AndroidComposeTestRule<*, *>.performAccessibilityChecks() {
    onRoot().tryPerformAccessibilityChecks()
}
