package com.opentasker.core.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckProtocolTest {
    @Test
    fun stableNewerReleaseIsReportedWithCanonicalLink() {
        val result = UpdateCheckProtocol.parseLatestRelease(
            release(tag = "v0.2.85"),
            currentVersion = "0.2.84",
        )

        assertEquals(
            UpdateCheckResult.Available(
                version = "0.2.85",
                url = "https://github.com/SysAdminDoc/OpenTasker/releases/tag/v0.2.85",
            ),
            result,
        )
    }

    @Test
    fun sameOlderAndNumericallyLargerVersionsCompareCorrectly() {
        assertEquals(
            UpdateCheckResult.NoUpdate,
            UpdateCheckProtocol.parseLatestRelease(release("v0.2.84"), "0.2.84"),
        )
        assertEquals(
            UpdateCheckResult.NoUpdate,
            UpdateCheckProtocol.parseLatestRelease(release("v0.2.83"), "0.2.84"),
        )
        assertTrue(
            UpdateCheckProtocol.parseLatestRelease(release("v0.10.0"), "0.9.99") is
                UpdateCheckResult.Available,
        )
    }

    @Test
    fun prereleaseDraftAndMalformedResponsesNeverReportAnUpdate() {
        assertEquals(
            UpdateCheckResult.NoUpdate,
            UpdateCheckProtocol.parseLatestRelease(release("v0.2.85", prerelease = true), "0.2.84"),
        )
        assertEquals(
            UpdateCheckResult.NoUpdate,
            UpdateCheckProtocol.parseLatestRelease(release("v0.2.85", draft = true), "0.2.84"),
        )
        assertTrue(
            UpdateCheckProtocol.parseLatestRelease("{\"tag_name\":\"v0.2.85\"}", "0.2.84") is
                UpdateCheckResult.Invalid,
        )
        assertTrue(
            UpdateCheckProtocol.parseLatestRelease(release("v0.2.85", url = "https://example.com"), "0.2.84") is
                UpdateCheckResult.Invalid,
        )
        assertTrue(
            UpdateCheckProtocol.parseLatestRelease("{\"tag_name\":true}", "0.2.84") is
                UpdateCheckResult.Invalid,
        )
    }

    @Test
    fun requestIsHttpsOnlyBoundedAndDoesNotCarryIdentityOrCredentials() {
        val request = UpdateCheckProtocol.requestArguments()
        assertEquals("GET", request["method"])
        assertTrue(request.getValue("url").startsWith("https://"))
        assertEquals("none", request["redirects"])
        assertEquals(UpdateCheckProtocol.MAX_RESPONSE_BYTES.toString(), request["max_response_bytes"])
        assertEquals(UpdateCheckProtocol.REQUEST_TIMEOUT_SECONDS.toString(), request["timeout_sec"])
        assertTrue(request.keys.none { it.equals("authorization", ignoreCase = true) })
        assertTrue(request.values.none { it.contains("User-Agent", ignoreCase = true) })
    }

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        url: String = UpdateCheckProtocol.RELEASES_URL_PREFIX + tag,
    ): String = """
        {
          "tag_name": "$tag",
          "html_url": "$url",
          "draft": $draft,
          "prerelease": $prerelease
        }
    """.trimIndent()
}
