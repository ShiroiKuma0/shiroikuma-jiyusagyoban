package com.opentasker.core.support

import java.net.URLEncoder

/**
 * The public project links Settings shows, and the bug-report URL it opens.
 *
 * Issue #14 arrived as screenshots rather than a diagnostics bundle because nothing in the app
 * pointed at the tracker or said what a useful report contains. The report URL carries the build
 * and device already filled in, so the parts a maintainer always has to ask for are there before
 * anyone types a word.
 */
object ProjectLinks {
    const val REPOSITORY_URL = "https://github.com/SysAdminDoc/OpenTasker"
    const val RELEASES_URL = "$REPOSITORY_URL/releases"
    const val ISSUES_URL = "$REPOSITORY_URL/issues"
    const val LICENSE_URL = "$REPOSITORY_URL/blob/master/LICENSE"

    private const val NEW_ISSUE_URL = "$ISSUES_URL/new"

    /** Build.MODEL and friends are vendor strings; keep one odd device from bloating the URL. */
    private const val MAX_FIELD_LENGTH = 80

    /**
     * A new-issue URL whose body already names the build and device.
     *
     * Nothing here is personal: it is the same header the diagnostic report starts with. The
     * prompts below the environment block exist because "it does not work" reports cost a round
     * trip every time.
     */
    fun reportProblemUrl(
        appVersion: String,
        versionCode: Int,
        distribution: String,
        androidRelease: String,
        sdkInt: Int,
        device: String,
    ): String {
        val body = buildString {
            appendLine("### What happened")
            appendLine()
            appendLine()
            appendLine("### What you expected instead")
            appendLine()
            appendLine()
            appendLine("### Steps to reproduce")
            appendLine()
            appendLine()
            appendLine("### Environment")
            appendLine()
            appendLine("- OpenTasker: ${bounded(appVersion)} ($versionCode, ${bounded(distribution)})")
            appendLine("- Android: ${bounded(androidRelease)} (API $sdkInt)")
            appendLine("- Device: ${bounded(device)}")
            appendLine()
            append("Settings has a Diagnostics screen with a Copy button; its report is redacted and ")
            append("says a great deal more than this block does.")
        }
        return "$NEW_ISSUE_URL?body=${encode(body)}"
    }

    private fun bounded(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.length <= MAX_FIELD_LENGTH) trimmed else trimmed.take(MAX_FIELD_LENGTH)
    }

    // URLEncoder is form encoding, so it renders a space as "+". That is correct in a query value,
    // which is where this lands.
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
