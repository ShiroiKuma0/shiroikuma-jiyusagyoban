package com.opentasker.core.assistant

import com.opentasker.core.actions.AirplaneRoute
import com.opentasker.core.actions.airplaneRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discussion #10: airplane mode for someone who can't turn on developer options for Shizuku. */
class AssistantAirplaneTest {
    @Test
    fun shizukuStaysFirstAndTheAssistantCoversAPhoneWithoutIt() {
        assertEquals(AirplaneRoute.SHIZUKU, airplaneRoute(shizukuReady = true, assistantActive = true))
        assertEquals(AirplaneRoute.SHIZUKU, airplaneRoute(shizukuReady = true, assistantActive = false))
        assertEquals(AirplaneRoute.ASSISTANT, airplaneRoute(shizukuReady = false, assistantActive = true))
        // Neither: Shizuku's own failure says what's missing, and the action adds the assistant hint.
        assertEquals(AirplaneRoute.SHIZUKU, airplaneRoute(shizukuReady = false, assistantActive = false))
    }

    @Test
    fun theSettingReachingItsTargetIsSuccess() = runBlocking {
        var reads = 0
        val result = AssistantAirplane.set(
            enabled = true,
            readEnabled = { ++reads > 2 },
            openSession = { true },
            timeoutMs = 5_000,
        )
        assertNull(result)
    }

    @Test
    fun noAssistantServiceOrARefusedSessionFailsAtOnce() = runBlocking {
        assertEquals(AssistantAirplane.NOT_STARTED, AssistantAirplane.set(true, { false }, { null }, timeoutMs = 5_000))
        assertEquals(AssistantAirplane.SESSION_REFUSED, AssistantAirplane.set(true, { false }, { false }, timeoutMs = 5_000))
    }

    @Test
    fun settingsRefusingEndsTheWaitWithItsReason() = runBlocking {
        val started = System.nanoTime()
        val result = AssistantAirplane.set(
            enabled = false,
            readEnabled = { true },
            openSession = { AssistantAirplane.answer("Settings refused"); true },
            timeoutMs = 5_000,
        )
        assertEquals("Settings refused", result)
        assertTrue("waited for the timeout", System.nanoTime() - started < 2_000_000_000L)
    }

    @Test
    fun aFinishedRequestStillWaitsForTheSettingToMove() = runBlocking {
        var reads = 0
        val result = AssistantAirplane.set(
            enabled = true,
            readEnabled = { ++reads > 3 },
            openSession = { AssistantAirplane.answer(null); true },
            timeoutMs = 5_000,
        )
        assertNull(result)
    }

    @Test
    fun aSessionThatNeverAnswersGivesUp() = runBlocking {
        val result = AssistantAirplane.set(true, { false }, { true }, timeoutMs = 300)
        assertTrue(result, result!!.startsWith("Settings didn't switch airplane mode"))
    }

    @Test
    fun anAnswerWithNoRequestWaitingIsIgnored() = runBlocking {
        AssistantAirplane.answer("stale")
        assertNull(AssistantAirplane.set(true, { true }, { true }, timeoutMs = 300))
    }
}
