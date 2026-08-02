package com.opentasker.ui

import com.opentasker.ui.screens.parsePreflightEventVariables
import org.junit.Assert.assertEquals
import org.junit.Test

class PreflightReviewDialogTest {
    @Test
    fun parsesBoundedKeyValueEventVariablesAndKeepsEqualsInValues() {
        assertEquals(
            mapOf("mode" to "on", "payload" to "a=b=c"),
            parsePreflightEventVariables(" mode = on\npayload=a=b=c\ninvalid\n=missing-key"),
        )
    }
}
