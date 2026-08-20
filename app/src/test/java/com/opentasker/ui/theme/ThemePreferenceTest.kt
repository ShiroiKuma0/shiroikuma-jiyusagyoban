package com.opentasker.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferenceTest {
    @Test
    fun storageStringsRoundTripEveryThemeMode() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromString(mode.toStorageString()))
        }
    }

    @Test
    fun unknownStorageValuesFallBackToAmoled() {
        assertEquals(ThemeMode.Amoled, ThemeMode.fromString(null))
        assertEquals(ThemeMode.Amoled, ThemeMode.fromString("unknown"))
    }
}
