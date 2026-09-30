package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.opentasker.core.capabilities.AutomationLintReport
import com.opentasker.core.contexts.CompanionAssociation
import com.opentasker.core.model.AutomationInvariant
import com.opentasker.core.model.InvariantStatePredicate
import com.opentasker.core.model.Task
import com.opentasker.core.plugins.locale.LocaleGrant
import com.opentasker.ui.theme.OpenTaskerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The three removals that used to vanish without a word now say what happened (A-332). */
class DestructiveActionFeedbackTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    @Test
    fun deletingAWatchedSettingOffersUndoThatPutsItBack() {
        val watched = AutomationInvariant(
            id = 7L,
            name = "Quiet nights",
            guard = InvariantStatePredicate(key = "dnd", value = "on"),
            forbiddenWriteKey = "volume:ring",
        )
        var invariants by mutableStateOf(listOf(watched))
        composeTestRule.setContent {
            WithUndoSnackbar { undoable ->
                AutomationInvariantPanel(
                    invariants = invariants,
                    report = AutomationLintReport(),
                    onUpdate = { invariants = it },
                    onUndoableMessage = undoable,
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Stop watching this setting").performClick()
        composeTestRule.onNodeWithText("Stopped watching Quiet nights").assertIsDisplayed()
        assertTrue(invariants.isEmpty())

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(watched), invariants)
        composeTestRule.onNodeWithText("Quiet nights").assertIsDisplayed()
    }

    @Test
    fun revokingALocaleGrantNamesTheTaskAndUndoRestoresIt() {
        val grant = LocaleGrant(token = "grant-token", taskId = 42L)
        var grants by mutableStateOf(listOf(grant))
        val restored = mutableListOf<LocaleGrant>()
        composeTestRule.setContent {
            WithUndoSnackbar { undoable ->
                LocaleGrantManagementCard(
                    tasks = listOf(Task(id = 42L, name = "Porch lights")),
                    grants = grants,
                    onRevoke = { grants = grants - it },
                    onRestore = { restored += it; grants = grants + it },
                    onUndoableMessage = undoable,
                )
            }
        }

        composeTestRule.onNodeWithText("Revoke").performClick()
        composeTestRule.onNodeWithText("Revoked the grant for Porch lights").assertIsDisplayed()
        assertTrue(grants.isEmpty())

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(grant), restored)
    }

    @Test
    fun removingACompanionDeviceSaysWhichOneAndHowToGetItBack() {
        val messages = mutableListOf<String>()
        var removedResult = true
        composeTestRule.setContent {
            OpenTaskerTheme {
                CompanionSetupCard(
                    associations = listOf(CompanionAssociation(id = "5", label = "Pixel Buds")),
                    onRefresh = {},
                    onDisassociate = { removedResult },
                    onMessage = { messages += it },
                )
            }
        }

        composeTestRule.onNodeWithText("Revoke").performClick()
        removedResult = false
        composeTestRule.onNodeWithText("Revoke").performClick()

        assertEquals(
            listOf("Removed Pixel Buds. Use Associate a device to add it back.", "Couldn't remove Pixel Buds."),
            messages,
        )
    }

    @Composable
    private fun WithUndoSnackbar(content: @Composable (UndoableMessage) -> Unit) {
        OpenTaskerTheme {
            val host = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val undoable = remember(scope, host) { undoableMessages(scope, host, "Undo") }
            Scaffold(snackbarHost = { SnackbarHost(host) }) { padding ->
                Box(Modifier.padding(padding)) { content(undoable) }
            }
        }
    }
}
