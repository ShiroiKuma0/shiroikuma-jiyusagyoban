package com.opentasker.core.sharing

import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileShareLibraryTest {
    @Test
    fun manifestSummarizesBundleAndSafetyFindings() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.28",
            exportedAtEpochMs = 123L,
            profiles = listOf(
                Profile(
                    id = 1,
                    name = "Shared",
                    enterTaskId = 1,
                    contexts = listOf(ContextSpec(ContextType.EVENT, config = mapOf("event" to "nfc"))),
                )
            ),
            tasks = listOf(
                Task(
                    id = 1,
                    name = "Shared task",
                    actions = listOf(
                        ActionSpec(type = "notify.show"),
                        ActionSpec(type = "script.termux.run"),
                        ActionSpec(type = "log"),
                    ),
                )
            ),
        )

        val manifest = ProfileShareLibrary.buildManifest(
            ProfileShareDraft(
                slug = "nfc-checkin",
                title = "NFC Check-in",
                summary = "Log and notify from an NFC tag.",
                author = "OpenTasker",
                screenshots = listOf("nfc-checkin.png"),
                bundle = bundle,
            )
        )

        assertEquals("nfc-checkin", manifest.slug)
        assertEquals(1, manifest.profileCount)
        assertEquals(1, manifest.taskCount)
        assertEquals(3, manifest.actionCount)
        assertEquals(1, manifest.contextCount)
        assertEquals(1, manifest.screenshotCount)
        assertEquals(ShareTrustLevel.CommunityUnverified, manifest.trustLevel)
        assertTrue(manifest.hasBlockingFindings)

        val requirements = manifest.capabilityRequirements.associateBy { it.actionId }
        assertEquals(CapabilityLevel.RequiresSetup, requirements.getValue("notify.show").level)
        assertEquals(CapabilityLevel.Unsupported, requirements.getValue("script.termux.run").level)
        assertTrue(manifest.findings.any { it.message.contains("script.termux.run") && it.severity == ShareFindingSeverity.Blocker })
    }

    @Test
    fun safeBundleWithoutUnsupportedActionsHasNoBlockers() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.28",
            exportedAtEpochMs = 123L,
            profiles = listOf(Profile(id = 1, name = "Safe", enterTaskId = 1, contexts = listOf(ContextSpec(ContextType.TIME)))),
            tasks = listOf(Task(id = 1, name = "Safe task", actions = listOf(ActionSpec(type = "log")))),
        )

        val manifest = ProfileShareLibrary.buildManifest(
            ProfileShareDraft(
                slug = "safe-log",
                title = "Safe Log",
                summary = "A local-only log pattern.",
                screenshots = listOf("safe-log.png"),
                bundle = bundle,
            )
        )

        assertFalse(manifest.hasBlockingFindings)
        assertTrue(manifest.capabilityRequirements.isEmpty())
    }

    @Test
    fun invalidSlugIsRejected() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.28",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
        )

        val error = runCatching {
            ProfileShareLibrary.buildManifest(
                ProfileShareDraft(
                    slug = "Bad Slug",
                    title = "Bad",
                    summary = "Invalid slug.",
                    bundle = bundle,
                )
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun submissionMarkdownIncludesCountsAndSafetyFindings() {
        val bundle = OpenTaskerBundleCodec.build(
            appVersion = "0.2.28",
            exportedAtEpochMs = 123L,
            profiles = emptyList(),
            tasks = emptyList(),
        )
        val manifest = ProfileShareLibrary.buildManifest(
            ProfileShareDraft(
                slug = "empty-draft",
                title = "Empty Draft",
                summary = "A placeholder share draft.",
                bundle = bundle,
            )
        )

        val markdown = ProfileShareLibrary.submissionMarkdown(manifest)

        assertTrue(markdown.contains("GitHub Discussions"))
        assertTrue(markdown.contains("https://github.com/SysAdminDoc/OpenTasker/discussions"))
        assertTrue(markdown.contains("Profiles: 0"))
        assertTrue(markdown.contains("No screenshots"))
    }
}
