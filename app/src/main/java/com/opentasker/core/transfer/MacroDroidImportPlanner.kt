package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel

private const val MACRODROID_DISABLED_PROFILE_WARNING =
    "Imported MacroDroid profiles are disabled by default. Review triggers, actions, placeholders, and required permissions before enabling them."

object MacroDroidImportPlanner {
    fun preview(report: MacroDroidImportReport): TaskerImportPreview {
        val plan = OpenTaskerBundleCodec.validate(report.bundle)
        val hasImportableContent = report.bundle.tasks.isNotEmpty() || report.bundle.profiles.isNotEmpty()
        val emptyWarning = if (hasImportableContent) {
            emptyList()
        } else {
            listOf("No importable MacroDroid macros were found.")
        }
        return TaskerImportPreview(
            sourceTaskCount = report.sourceMacroCount,
            sourceProfileCount = report.sourceMacroCount,
            sourceVariableCount = report.sourceVariableCount,
            sourceSceneCount = 0,
            importTaskCount = report.bundle.tasks.size,
            importProfileCount = report.bundle.profiles.size,
            importVariableCount = report.bundle.variables.size,
            importSceneCount = 0,
            mappedActionCount = report.mappedActions.size,
            unsupportedActionCount = report.unsupportedActions.size,
            // Fork: the fork's bundle validator carries no capabilityRequirements/powerRequests, so
            // the warnings are derived from the registry exactly as TaskerImportPlanner derives them.
            capabilityWarnings = report.bundle.tasks
                .flatMap { task -> task.actions.map { it.type } }
                .distinct()
                .map { actionId -> actionId to com.opentasker.core.capabilities.ActionCapabilityRegistry.get(actionId) }
                .filter { (_, capability) -> capability.level != CapabilityLevel.Supported }
                .map { (actionId, capability) -> "$actionId: ${capability.level.name.lowercase()} - ${capability.reason}" },
            warnings = (report.warnings + plan.warnings + emptyWarning).distinct(),
            lossyWarnings = (report.lossyWarnings + plan.lossyWarnings).distinct(),
            canImport = plan.canImport && hasImportableContent,
        )
    }

    fun confirmedBundle(report: MacroDroidImportReport): OpenTaskerBundle = report.bundle.copy(
        profiles = report.bundle.profiles.map { it.copy(enabled = false, requiresRiskAcknowledgement = true) },
        metadata = report.bundle.metadata.copy(
            warnings = (report.bundle.metadata.warnings + MACRODROID_DISABLED_PROFILE_WARNING).distinct(),
        ),
    )
}
