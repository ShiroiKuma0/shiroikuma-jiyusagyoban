package com.opentasker.ui.utils

import com.opentasker.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

class UiNotificationsTest {
    @Test
    fun successMessageUsesTheSuccessResource() {
        val message = UiNotifications.successMessage("saved") { resourceId, value ->
            assertEquals(R.string.success_prefix, resourceId)
            "Success: $value"
        }

        assertEquals("Success: saved", message)
    }

    @Test
    fun errorMessageUsesTheErrorResource() {
        val message = UiNotifications.errorMessage("failed") { resourceId, value ->
            assertEquals(R.string.error_prefix, resourceId)
            "Error: $value"
        }

        assertEquals("Error: failed", message)
    }

    @Test
    fun infoMessagePreservesTheCallerText() {
        assertEquals("Ready", UiNotifications.infoMessage("Ready"))
    }
}
