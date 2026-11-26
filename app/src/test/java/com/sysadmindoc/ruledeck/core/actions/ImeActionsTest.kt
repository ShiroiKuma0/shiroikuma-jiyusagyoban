package com.sysadmindoc.ruledeck.core.actions

import android.content.ContextWrapper
import com.sysadmindoc.ruledeck.core.engine.ActionContext
import com.sysadmindoc.ruledeck.core.engine.ActionResult
import com.sysadmindoc.ruledeck.core.engine.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeActionsTest {
    @Test
    fun resolvesAnEnabledImeByComponentOrUniquePackage() {
        val enabled = listOf(
            "com.example.work/.WorkIme",
            "com.example.personal/.PersonalIme",
        )

        assertEquals("com.example.work/.WorkIme", resolveImeTarget("com.example.work/.WorkIme", enabled))
        assertEquals("com.example.personal/.PersonalIme", resolveImeTarget("com.example.personal", enabled))
    }

    @Test
    fun rejectsMissingOrAmbiguousImeTargets() {
        val enabled = listOf(
            "com.example.one/.FirstIme",
            "com.example.one/.SecondIme",
        )

        assertNull(resolveImeTarget("com.example.missing", enabled))
        assertNull(resolveImeTarget("com.example.one", enabled))
    }

    /**
     * The README promises `ime.set` opens Android's picker and then fails with a user-selection
     * message rather than pretending the switch happened. A Success here would tell a task the
     * keyboard changed when Android only showed a chooser.
     */
    @Test
    fun aSwitchOpensThePickerAndStillFailsBecauseTheUserHasToChoose() {
        var pickerOpened = 0
        val result = switchKeyboard(
            requested = "com.example.work",
            enabledIds = ENABLED,
            currentIme = { "com.example.personal/.PersonalIme" },
            openPicker = { pickerOpened++ },
            log = {},
        )

        assertEquals("the picker is the only thing an ordinary app may open", 1, pickerOpened)
        assertTrue("a switch the user hasn't made must not read as done: $result", result is ActionResult.Failure)
        val message = (result as ActionResult.Failure).message
        assertTrue(message, message.contains("requires user selection"))
        assertTrue("the message names the keyboard the task asked for: $message", message.contains("com.example.work/.WorkIme"))
    }

    @Test
    fun theKeyboardAlreadyInUseSucceedsWithoutOpeningThePicker() {
        var pickerOpened = 0
        val logs = mutableListOf<String>()
        val result = switchKeyboard(
            requested = "com.example.work/.WorkIme",
            enabledIds = ENABLED,
            currentIme = { "com.example.work/.WorkIme" },
            openPicker = { pickerOpened++ },
            log = logs::add,
        )

        assertEquals(ActionResult.Success, result)
        assertEquals("nothing to choose, so no picker over the user's screen", 0, pickerOpened)
        assertEquals(listOf("IME already selected: com.example.work/.WorkIme"), logs)
    }

    @Test
    fun aKeyboardThatIsNotEnabledIsRefusedBeforeAnythingIsReadOrOpened() {
        var currentRead = 0
        var pickerOpened = 0
        val result = switchKeyboard(
            requested = "com.example.missing",
            enabledIds = ENABLED,
            currentIme = { currentRead++; "" },
            openPicker = { pickerOpened++ },
            log = {},
        )

        assertEquals(ActionResult.Failure("IME is not enabled or is ambiguous: com.example.missing"), result)
        assertEquals(0, currentRead)
        assertEquals(0, pickerOpened)
    }

    @Test
    fun aPickerAndroidWontOpenIsReportedWithItsReason() {
        val refusal = IllegalStateException("no window token")
        val result = switchKeyboard(
            requested = "com.example.work",
            enabledIds = ENABLED,
            currentIme = { "" },
            openPicker = { throw refusal },
            log = {},
        )

        assertTrue(result is ActionResult.Failure)
        assertEquals("could not open the IME picker: no window token", (result as ActionResult.Failure).message)
        assertSame(refusal, result.cause)
    }

    @Test
    fun aMissingKeyboardIdIsRefusedBeforeAskingAndroidForAnything() = runBlocking {
        // A context with no services at all: reaching any of them would throw on the JVM.
        val result = ImeSetAction().run(ActionContext(ContextWrapper(null), VariableStore()), mapOf("ime_id" to "  "))

        assertEquals(ActionResult.Failure("missing ime_id"), result)
    }

    private companion object {
        val ENABLED = listOf(
            "com.example.work/.WorkIme",
            "com.example.personal/.PersonalIme",
        )
    }
}
