package com.opentasker.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.app.R
import com.opentasker.core.engine.EventTaskVariables
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A-282: a share context's editor says what its task can read, one line per variable. */
class EventVariablesComposeTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private fun showEditor(event: String) {
        composeTestRule.setContent {
            OpenTaskerTheme {
                ContextConfigDialog(
                    state = ContextEditState(
                        profile = Profile(id = 3, name = "Shared", enterTaskId = 7),
                        type = ContextType.EVENT,
                        index = 0,
                        existing = ContextSpec(ContextType.EVENT, mapOf("event" to event)),
                    ),
                    onDismiss = {},
                    onSave = {},
                )
            }
        }
    }

    @Test
    fun theShareEditorListsEachVariableWithItsMeaningOnceOpened() {
        showEditor("share")
        val share = EventTaskVariables.declaredFor("share")
        assertEquals(7, share.size)
        composeTestRule.onAllNodesWithText("share_text").assertCountEquals(0)

        // The form is a lazy list and the section sits below the fields, so bring it into the list first.
        composeTestRule.onNode(hasScrollToKeyAction()).performScrollToKey("event-variables")
        composeTestRule.onNodeWithText(resources.getString(R.string.context_event_variables_title)).performClick()

        for (variable in share) {
            composeTestRule.onNodeWithText(variable.name).performScrollTo().assertIsDisplayed()
            composeTestRule.onNodeWithText(resources.getString(variable.meaningRes)).assertIsDisplayed()
        }
        composeTestRule.performAccessibilityChecks()
    }

    @Test
    fun anEventThatHandsATaskNothingShowsNoList() {
        showEditor("usb")

        composeTestRule.onAllNodesWithText(resources.getString(R.string.context_event_variables_title)).assertCountEquals(0)
    }
}
