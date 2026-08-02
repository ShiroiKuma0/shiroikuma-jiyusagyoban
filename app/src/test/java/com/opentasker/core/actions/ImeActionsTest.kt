package com.opentasker.core.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
