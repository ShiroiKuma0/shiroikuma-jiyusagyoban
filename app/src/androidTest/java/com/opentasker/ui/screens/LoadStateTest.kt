package com.opentasker.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Screens tell "still reading" apart from "nothing there" and from "couldn't read" (A-360). */
class LoadStateTest {
    @get:Rule
    val composeTestRule = createAccessibilityComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun theInspectorNeverFlashesItsEmptyStateWhileProfilesLoad() {
        runBlocking { db.profileDao().insert(Profile(name = "Commute", enterTaskId = 1).toEntity()) }
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            OpenTaskerTheme {
                ContextInspectorScreen(db = db, contentPadding = PaddingValues(0.dp), onUndoableMessage = { _, _ -> })
            }
        }

        // The empty state replaces the whole list, so checking every frame catches even a one-frame flash.
        var loaded = false
        for (frame in 0 until MAX_FRAMES) {
            composeTestRule.onNodeWithText(UNAVAILABLE_TITLE).assertDoesNotExist()
            if (composeTestRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()) {
                loaded = true
                break
            }
            composeTestRule.mainClock.advanceTimeByFrame()
            Thread.sleep(FRAME_WAIT_MS)
        }
        if (!loaded) fail("The Inspector never finished loading")

        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("Commute", substring = true))
        composeTestRule.onNodeWithText(UNAVAILABLE_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText("No profiles").assertDoesNotExist()
    }

    @Test
    fun withNoProfilesTheInspectorSaysSoOnceLoaded() {
        composeTestRule.setContent {
            OpenTaskerTheme {
                ContextInspectorScreen(db = db, contentPadding = PaddingValues(0.dp), onUndoableMessage = { _, _ -> })
            }
        }

        composeTestRule.waitUntil(LOAD_TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("No profiles"))
        composeTestRule.onNodeWithText("No profiles").assertIsDisplayed()
        composeTestRule.onNodeWithText(UNAVAILABLE_TITLE).assertDoesNotExist()
    }

    @Test
    fun aFailedDiagnosticsReadSaysSoAndRetryAsksAgain() {
        var state by mutableStateOf(DiagnosticsUiState(loadFailed = true))
        var refreshes = 0
        composeTestRule.setContent {
            OpenTaskerTheme {
                DiagnosticsScreen(
                    state = state,
                    contentPadding = PaddingValues(0.dp),
                    onRefresh = { refreshes++ },
                    onShare = {},
                    onCopy = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Diagnostics couldn't be read").assertIsDisplayed()
        composeTestRule.onNodeWithText("Couldn't check engine health").assertIsDisplayed()
        // Nothing was read, so nothing may claim a result: no placeholders and no "no crashes".
        composeTestRule.onAllNodesWithText("Loading…").assertCountEquals(0)
        composeTestRule.onNodeWithText("No captured crashes.").assertDoesNotExist()

        composeTestRule.onNodeWithText("Retry").performClick()
        assertEquals(1, refreshes)

        state = DiagnosticsUiState(loadedAtMillis = 1L)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Diagnostics couldn't be read").assertDoesNotExist()
        composeTestRule.onNodeWithText("Checking engine health…").assertIsDisplayed()
    }

    private companion object {
        const val UNAVAILABLE_TITLE = "Context inspector unavailable"
        const val MAX_FRAMES = 400
        const val FRAME_WAIT_MS = 10L
        const val LOAD_TIMEOUT_MS = 10_000L
    }
}
