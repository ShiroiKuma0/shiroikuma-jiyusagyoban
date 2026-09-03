package com.opentasker.core.support

import com.opentasker.ProductionSources
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectLinksTest {

    @Test
    fun `every published link points at this repository over https`() {
        listOf(
            ProjectLinks.REPOSITORY_URL,
            ProjectLinks.RELEASES_URL,
            ProjectLinks.ISSUES_URL,
            ProjectLinks.LICENSE_URL,
        ).forEach { url ->
            val uri = URI(url)
            assertEquals("$url must be https", "https", uri.scheme)
            assertEquals("$url must stay on github.com", "github.com", uri.host)
            assertTrue("$url must stay under this repository", uri.path.startsWith("/SysAdminDoc/OpenTasker"))
        }
    }

    @Test
    fun `the report url carries the build and device in its body`() {
        val url = ProjectLinks.reportProblemUrl(
            appVersion = "0.2.93",
            versionCode = 95,
            distribution = "standard",
            androidRelease = "16",
            sdkInt = 36,
            device = "Google Pixel 7",
        )

        val uri = URI(url)
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/SysAdminDoc/OpenTasker/issues/new", uri.path)

        val body = decodedBody(url)
        assertTrue(body, "- OpenTasker: 0.2.93 (95, standard)" in body)
        assertTrue(body, "- Android: 16 (API 36)" in body)
        assertTrue(body, "- Device: Google Pixel 7" in body)
    }

    @Test
    fun `an oversized vendor string cannot bloat the url`() {
        val url = ProjectLinks.reportProblemUrl(
            appVersion = "0.2.93",
            versionCode = 95,
            distribution = "standard",
            androidRelease = "16",
            sdkInt = 36,
            device = "X".repeat(4_000),
        )

        val body = decodedBody(url)
        val deviceLine = body.lineSequence().first { line -> line.startsWith("- Device: ") }
        assertEquals("- Device: " + "X".repeat(80), deviceLine)
    }

    @Test
    fun `body characters that would break the query are encoded`() {
        val url = ProjectLinks.reportProblemUrl(
            appVersion = "0.2.93",
            versionCode = 95,
            distribution = "standard",
            androidRelease = "16",
            sdkInt = 36,
            // A separator, a fragment marker, and a newline all end the query if they survive raw.
            device = "Odd&Vendor#1 Model",
        )

        val raw = url.substringAfter("?body=")
        assertTrue(raw, '&' !in raw)
        assertTrue(raw, '#' !in raw)
        assertTrue(raw, '\n' !in raw)
        assertTrue(decodedBody(url), "- Device: Odd&Vendor#1 Model" in decodedBody(url))
    }

    /**
     * A link nothing opens is the defect this item existed to fix, so pin the route as well as the
     * URL builder. Compose rendering itself needs a device and is not asserted here.
     */
    @Test
    fun `settings reaches the about card and the about card reaches these links`() {
        val settings = ProductionSources.read("com/opentasker/ui/screens/PermissionOnboardingScreen.kt")

        // Bounded at the sibling branch. Reading to end of file swept in AboutCard's own
        // declaration, so the assertion passed even with the call site deleted: exactly the
        // "link nothing opens" failure it is here to catch.
        val settingsOnlyBranch = settings
            .substringAfter("if (settingsOnly) {")
            .substringBefore("if (!settingsOnly)")
        assertTrue("Settings must list the About card", "AboutCard(" in settingsOnlyBranch)
        assertTrue(
            "About must offer the repository, releases and licence links",
            listOf(
                "ProjectLinks.REPOSITORY_URL",
                "ProjectLinks.RELEASES_URL",
                "ProjectLinks.LICENSE_URL",
            ).all { link -> link in settings },
        )
        assertTrue(
            "Report a problem must carry the real build and device, not placeholders",
            listOf(
                "ProjectLinks.reportProblemUrl(",
                "appVersion = BuildConfig.VERSION_NAME",
                "versionCode = BuildConfig.VERSION_CODE",
                "androidRelease = Build.VERSION.RELEASE",
                "sdkInt = Build.VERSION.SDK_INT",
            ).all { fragment -> fragment in settings },
        )
    }

    @Test
    fun `the diagnostics copy button reaches the clipboard`() {
        val screen = ProductionSources.read("com/opentasker/ui/screens/DiagnosticsScreen.kt")
        val shell = ProductionSources.read("com/opentasker/ui/screens/ActiveAutomationUi.kt")
        val viewModel = ProductionSources.read("com/opentasker/ui/screens/ActiveAutomationViewModel.kt")

        assertTrue("Diagnostics must show a copy control", "IconButton(onClick = onCopy)" in screen)
        assertTrue("the copy control must be wired", "onCopy = viewModel::copyDiagnosticReport" in shell)

        val body = viewModel.substringAfter("fun copyDiagnosticReport()").substringBefore("fun shareDiagnosticReport()")
        assertTrue("copy must build the same redacted report Share sends", "DiagnosticExport.buildReport(" in body)
        assertTrue("copy must reach the clipboard", "setPrimaryClip(" in body)
        assertTrue("copy must confirm to the user", "R.string.ui_message_diagnostics_copied" in body)
    }

    private fun decodedBody(url: String): String =
        URLDecoder.decode(url.substringAfter("?body="), Charsets.UTF_8.name())
}
