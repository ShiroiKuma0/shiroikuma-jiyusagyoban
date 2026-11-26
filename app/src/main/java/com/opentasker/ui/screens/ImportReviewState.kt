package com.opentasker.ui.screens

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.transfer.MacroDroidImportPlanner
import com.opentasker.core.transfer.MacroDroidImportReport
import com.opentasker.core.transfer.OpenTaskerBundle

// Fork: TaskerImportReviewState and its two builders stay in ActiveAutomationViewModel.kt, where
// they carry a plain-string title for the fork's dialogs.

/** A picked `.emdr`, waiting for the password it was exported with. */
internal class MacroDroidPasswordPrompt(val bytes: ByteArray, val wrongPassword: Boolean = false) {
    fun copy(wrongPassword: Boolean) = MacroDroidPasswordPrompt(bytes, wrongPassword)
}
