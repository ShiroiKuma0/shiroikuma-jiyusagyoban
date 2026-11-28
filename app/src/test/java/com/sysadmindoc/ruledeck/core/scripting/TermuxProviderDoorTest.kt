package com.sysadmindoc.ruledeck.core.scripting

import com.opentasker.core.scripting.TermuxCommandBroker
import com.opentasker.core.scripting.TermuxCommandBroker.DoorAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The contract of Termux's `run_command` provider door (2026-11-28): only “no door here” falls back
 * to the service; a refusal is shown as Termux wrote it and is never retried the old way.
 */
class TermuxProviderDoorTest {
    private fun said(line: String?) = TermuxCommandBroker.interpretDoorAnswer(Result.success(line))

    @Test
    fun acceptedMeansWaitForTheResult() {
        assertEquals(DoorAnswer.Accepted, said("OK:accepted"))
    }

    @Test
    fun aTermuxWithoutTheDoorGetsTheRequestTheOldWay() {
        assertEquals(DoorAnswer.UseService, said(null))
        assertEquals(DoorAnswer.UseService, said(""))
        assertEquals(DoorAnswer.UseService, said("ERROR:unknown method: run_command"))
        assertEquals(DoorAnswer.UseService, TermuxCommandBroker.interpretDoorAnswer(Result.failure(IllegalArgumentException("Unknown authority"))))
    }

    @Test
    fun aRefusalIsKeptVerbatimAndNeverFallsBack() {
        for (line in listOf(
            "ERROR:caller not trusted: shiroikuma.jiyusagyoban",
            "ERROR:caller certificate changed: shiroikuma.jiyusagyoban",
            "ERROR:permission not granted",
            "ERROR:allow-external-apps is off",
            "ERROR:caller unknown",
            "ERROR:no command path",
            "ERROR:cannot start: RunCommandService",
        )) assertEquals(DoorAnswer.Refused(line), said(line))
    }
}
