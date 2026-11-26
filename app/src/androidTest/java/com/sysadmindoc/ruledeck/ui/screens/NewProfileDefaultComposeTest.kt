package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.sysadmindoc.ruledeck.core.model.Profile
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * New profiles start off unless Settings says they start on, and the setting never changes an
 * existing profile's own state (A-365).
 */
class NewProfileDefaultComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val task = Task(id = 3, name = "Lights")

    private fun showEditor(profile: Profile?, newProfileEnabled: Boolean, onSave: (Boolean) -> Unit = {}) {
        composeTestRule.setContent {
            RuleDeckTheme {
                ProfileEditorDialog(
                    profile = profile,
                    tasks = listOf(task),
                    onDismiss = {},
                    onSave = { _, enabled, _, _, _, _, _, _, _, _, _, _, _, _, _ -> onSave(enabled) },
                    newProfileEnabled = newProfileEnabled,
                )
            }
        }
    }

    private fun enableSwitch() = composeTestRule.onNode(hasText(ENABLE_AFTER_SAVE) and isToggleable()).performScrollTo()

    @Test
    fun aNewProfileStartsOffByDefault() {
        showEditor(profile = null, newProfileEnabled = false)

        enableSwitch().assertIsOff()
    }

    @Test
    fun aNewProfileStartsOnAndSavesOnWhenSettingsSaySo() {
        var saved: Boolean? = null
        showEditor(profile = null, newProfileEnabled = true) { saved = it }

        enableSwitch().assertIsOn()
        // The name is the editor's first text field, as in CriticalFlowComposeTest.
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("At work")
        composeTestRule.onNodeWithText("Save").performClick()

        assertEquals(true, saved)
    }

    @Test
    fun aChoiceMadeInTheEditorSurvivesARotationWhileTheSettingLoads() {
        var setting by mutableStateOf(true)
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RuleDeckTheme {
                ProfileEditorDialog(
                    profile = null,
                    tasks = listOf(task),
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    newProfileEnabled = setting,
                )
            }
        }
        enableSwitch().assertIsOn().performClick()
        enableSwitch().assertIsOff()

        // A recreated screen reads the setting as off until the stored value loads.
        setting = false
        restorationTester.emulateSavedInstanceStateRestore()
        setting = true
        composeTestRule.waitForIdle()

        enableSwitch().assertIsOff()
    }

    @Test
    fun aChoiceTurnedOnSurvivesARotation() {
        // The case above can't tell a restored choice from the setting's first value, both off.
        // Here the choice differs from the setting the whole time, so only restoring keeps it.
        val restorationTester = StateRestorationTester(composeTestRule)
        restorationTester.setContent {
            RuleDeckTheme {
                ProfileEditorDialog(
                    profile = null,
                    tasks = listOf(task),
                    onDismiss = {},
                    onSave = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
                    newProfileEnabled = false,
                )
            }
        }
        enableSwitch().assertIsOff().performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        enableSwitch().assertIsOn()
    }

    @Test
    fun anExistingProfileKeepsItsOwnState() {
        showEditor(profile = Profile(id = 7, name = "Work", enterTaskId = task.id, enabled = false), newProfileEnabled = true)

        enableSwitch().assertIsOff()
    }

    @Test
    fun theSettingsRowSwitchesTheDefault() {
        var enabled by mutableStateOf(false)
        composeTestRule.setContent {
            RuleDeckTheme {
                NewProfilesEnabledSetupCard(enabled = enabled, onEnabledChange = { enabled = it })
            }
        }

        composeTestRule.onNode(hasText("New profiles start on") and isToggleable()).assertIsOff().performClick()

        assertEquals(true, enabled)
        composeTestRule.onNode(hasText("New profiles start switched on") and isToggleable()).assertIsOn()
    }

    private companion object {
        const val ENABLE_AFTER_SAVE = "Turn on after saving"
    }
}
