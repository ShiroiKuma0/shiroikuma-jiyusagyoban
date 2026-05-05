package com.opentasker.core.sharing

import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.transfer.CapabilityRequirement
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.OpenTaskerBundleCodec
import kotlinx.serialization.Serializable

const val PROFILE_SHARE_MANIFEST_SCHEMA_VERSION = 1

@Serializable
data class ProfileShareDraft(
    val slug: String,
    val title: String,
    val summary: String,
    val author: String = "",
    val sourceUrl: String = "",
    val screenshots: List<String> = emptyList(),
    val bundle: OpenTaskerBundle,
)

@Serializable
data class ProfileShareManifest(
    val schemaVersion: Int = PROFILE_SHARE_MANIFEST_SCHEMA_VERSION,
    val slug: String,
    val title: String,
    val summary: String,
    val author: String,
    val sourceUrl: String,
    val submissionChannel: String,
    val trustLevel: ShareTrustLevel,
    val bundleSchemaVersion: Int,
    val appVersion: String,
    val profileCount: Int,
    val taskCount: Int,
    val actionCount: Int,
    val contextCount: Int,
    val variableCount: Int,
    val sceneCount: Int,
    val screenshotCount: Int,
    val capabilityRequirements: List<CapabilityRequirement>,
    val findings: List<ShareSafetyFinding>,
) {
    val hasBlockingFindings: Boolean
        get() = findings.any { it.severity == ShareFindingSeverity.Blocker }
}

@Serializable
data class ShareSafetyFinding(
    val severity: ShareFindingSeverity,
    val message: String,
)

@Serializable
enum class ShareFindingSeverity {
    Info,
    Warning,
    Blocker,
}

@Serializable
enum class ShareTrustLevel {
    LocalDraft,
    CommunityUnverified,
}

object ProfileShareLibrary {
    const val SUBMISSION_CHANNEL = "GitHub Discussions"
    const val SUBMISSION_URL = "https://github.com/SysAdminDoc/OpenTasker/discussions"

    private val slugPattern = Regex("^[a-z0-9][a-z0-9-]{2,63}$")

    fun buildManifest(draft: ProfileShareDraft): ProfileShareManifest {
        require(slugPattern.matches(draft.slug)) {
            "Share slug must be 3-64 chars of lowercase letters, numbers, and hyphens."
        }
        require(draft.title.isNotBlank()) { "Share title is required." }
        require(draft.summary.isNotBlank()) { "Share summary is required." }

        val bundle = draft.bundle
        val capabilityRequirements = capabilityRequirements(bundle)
        val importPlan = OpenTaskerBundleCodec.validate(bundle)
        val findings = buildList {
            add(ShareSafetyFinding(ShareFindingSeverity.Info, "Community shares are unverified local drafts until a review/signing workflow exists."))
            if (draft.screenshots.isEmpty()) {
                add(ShareSafetyFinding(ShareFindingSeverity.Warning, "No screenshots are attached for reviewer inspection."))
            }
            capabilityRequirements.forEach { requirement ->
                val severity = if (requirement.level == CapabilityLevel.Unsupported) {
                    ShareFindingSeverity.Blocker
                } else {
                    ShareFindingSeverity.Warning
                }
                add(
                    ShareSafetyFinding(
                        severity = severity,
                        message = "${requirement.actionId}: ${requirement.reason}",
                    )
                )
            }
            importPlan.warnings.forEach { warning ->
                add(
                    ShareSafetyFinding(
                        severity = if (warning.startsWith("Unsupported schema version")) ShareFindingSeverity.Blocker else ShareFindingSeverity.Warning,
                        message = warning,
                    )
                )
            }
            importPlan.lossyWarnings.forEach { warning ->
                add(ShareSafetyFinding(ShareFindingSeverity.Warning, warning))
            }
        }.distinctBy { it.severity to it.message }

        return ProfileShareManifest(
            slug = draft.slug,
            title = draft.title.trim(),
            summary = draft.summary.trim(),
            author = draft.author.trim(),
            sourceUrl = draft.sourceUrl.trim(),
            submissionChannel = SUBMISSION_CHANNEL,
            trustLevel = ShareTrustLevel.CommunityUnverified,
            bundleSchemaVersion = bundle.schemaVersion,
            appVersion = bundle.appVersion,
            profileCount = bundle.profiles.size,
            taskCount = bundle.tasks.size,
            actionCount = bundle.tasks.sumOf { it.actions.size },
            contextCount = bundle.profiles.sumOf { it.contexts.size },
            variableCount = bundle.variables.size,
            sceneCount = bundle.scenes.size,
            screenshotCount = draft.screenshots.size,
            capabilityRequirements = capabilityRequirements,
            findings = findings,
        )
    }

    fun submissionMarkdown(manifest: ProfileShareManifest): String {
        val findingsText = manifest.findings.joinToString(separator = "\n") {
            "- ${it.severity}: ${it.message}"
        }.ifBlank { "- Info: No findings reported." }

        return """
            |# ${manifest.title}
            |
            |${manifest.summary}
            |
            |- Slug: `${manifest.slug}`
            |- Trust: ${manifest.trustLevel}
            |- Bundle schema: ${manifest.bundleSchemaVersion}
            |- Profiles: ${manifest.profileCount}
            |- Tasks: ${manifest.taskCount}
            |- Actions: ${manifest.actionCount}
            |- Contexts: ${manifest.contextCount}
            |- Scenes: ${manifest.sceneCount}
            |- Screenshots: ${manifest.screenshotCount}
            |- Submission channel: ${manifest.submissionChannel} (${SUBMISSION_URL})
            |
            |## Safety Findings
            |$findingsText
        """.trimMargin()
    }

    private fun capabilityRequirements(bundle: OpenTaskerBundle): List<CapabilityRequirement> =
        bundle.tasks
            .flatMap { it.actions }
            .map { it.type }
            .distinct()
            .sorted()
            .map { actionId -> actionId to ActionCapabilityRegistry.get(actionId) }
            .filter { (_, capability) -> capability.level != CapabilityLevel.Supported }
            .map { (actionId, capability) ->
                CapabilityRequirement(
                    actionId = actionId,
                    level = capability.level,
                    reason = capability.reason,
                )
            }
}
