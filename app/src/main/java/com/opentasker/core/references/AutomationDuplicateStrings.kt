package com.opentasker.core.references

import android.content.res.Resources
import com.opentasker.app.R

/** Localizable names generated when an automation is duplicated from the workspace UI. */
interface AutomationDuplicateStrings {
    fun untitled(): String

    fun copySuffix(copyNumber: Int): String

    companion object {
        fun from(resources: Resources): AutomationDuplicateStrings = ResourceAutomationDuplicateStrings(resources)

        val English: AutomationDuplicateStrings = EnglishAutomationDuplicateStrings
    }
}

private class ResourceAutomationDuplicateStrings(
    private val resources: Resources,
) : AutomationDuplicateStrings {
    override fun untitled(): String = resources.getString(R.string.automation_duplicate_untitled)

    override fun copySuffix(copyNumber: Int): String = if (copyNumber == 1) {
        resources.getString(R.string.automation_duplicate_copy_suffix)
    } else {
        resources.getString(R.string.automation_duplicate_copy_number_suffix, copyNumber)
    }
}

private object EnglishAutomationDuplicateStrings : AutomationDuplicateStrings {
    override fun untitled(): String = "Untitled"

    override fun copySuffix(copyNumber: Int): String = if (copyNumber == 1) {
        " (copy)"
    } else {
        " (copy $copyNumber)"
    }
}
