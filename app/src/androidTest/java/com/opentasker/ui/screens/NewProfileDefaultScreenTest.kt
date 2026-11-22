package com.opentasker.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.FallbackTaskSettings
import com.opentasker.core.storage.toEntity
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * A-365 through the whole screen: with "New profiles start on" set, the Profiles screen's New
 * profile button opens an editor that starts on. NewProfileDefaultComposeTest covers the dialog
 * on its own; this one covers the screen handing it the setting.
 */
class NewProfileDefaultScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private var settingBefore = false
    private var onboardingDoneBefore = false
    // The view model clears a stored fallback task that isn't in its database, so the app's own is put back.
    private val fallbackTask = FallbackTaskSettings(context)
    private var fallbackTaskIdBefore: Long? = null

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fallbackTaskIdBefore = fallbackTask.loadTaskId()
        runBlocking {
            settingBefore = ProfileDefaultsPreference.newProfilesEnabled(context).first()
            onboardingDoneBefore = OnboardingPreference.hasCompleted(context).first()
            ProfileDefaultsPreference.setNewProfilesEnabled(context, true)
            // The first-run flow would cover the screen.
            OnboardingPreference.markCompleted(context)
            db.taskDao().insert(Task(name = "Lights", actions = listOf(ActionSpec(type = "torch.set"))).toEntity())
        }
    }

    @After
    fun tearDown() {
        db.close()
        fallbackTask.saveTaskId(fallbackTaskIdBefore)
        runBlocking {
            ProfileDefaultsPreference.setNewProfilesEnabled(context, settingBefore)
            // Leave the device as it was found, so a fresh install still gets the first-run flow.
            if (!onboardingDoneBefore) OnboardingPreference.clearCompleted(context)
        }
    }

    @Test
    fun theNewProfileButtonOpensAnEditorThatStartsOn() {
        composeTestRule.setContent { OpenTaskerTheme { ActiveAutomationUi(db) } }
        val newProfile = hasContentDescription("New profile") and hasClickAction()
        composeTestRule.waitUntil(TIMEOUT_MS) { composeTestRule.onAllNodes(newProfile).fetchSemanticsNodes().isNotEmpty() }

        composeTestRule.onAllNodes(newProfile).onFirst().performClick()

        val enableSwitch = hasText("Enable after saving") and isToggleable()
        composeTestRule.waitUntil(TIMEOUT_MS) { composeTestRule.onAllNodes(enableSwitch).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNode(enableSwitch).performScrollTo().assertIsOn()
    }

    @Test
    fun theViewModelLoadsTheSettingWithNoScreenWatching() {
        val store = ViewModelStore()
        try {
            val viewModel = ViewModelProvider(store, ActiveAutomationViewModelFactory(db, context))[ActiveAutomationViewModel::class.java]
            // A rotated screen reads the value on its first frame, before it collects anything, so
            // the value has to arrive with nobody collecting. Read without collecting.
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!viewModel.newProfilesEnabled.value && System.currentTimeMillis() < deadline) Thread.sleep(50)

            assertEquals(true, viewModel.newProfilesEnabled.value)
        } finally {
            store.clear()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
