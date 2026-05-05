package com.opentasker.core.transfer

import com.opentasker.core.capabilities.CapabilityLevel

private const val TASKER_DISABLED_PROFILE_WARNING =
    "Imported Tasker profiles are disabled by default; review contexts, actions, and required permissions before enabling them."

data class TaskerImportPreview(
    val sourceTaskCount: Int,
    val sourceProfileCount: Int,
    val sourceVariableCount: Int,
    val sourceSceneCount: Int,
    val importTaskCount: Int,
    val importProfileCount: Int,
    val importVariableCount: Int,
    val importSceneCount: Int,
    val mappedActionCount: Int,
    val unsupportedActionCount: Int,
    val capabilityWarnings: List<String>,
    val warnings: List<String>,
    val lossyWarnings: List<String>,
    val canImport: Boolean,
)

object TaskerImportPlanner {
    fun preview(report: TaskerXmlImportReport): TaskerImportPreview {
        val plan = OpenTaskerBundleCodec.validate(report.bundle)
        val hasImportableContent = report.bundle.tasks.isNotEmpty() ||
            report.bundle.profiles.isNotEmpty() ||
            report.bundle.variables.isNotEmpty() ||
            report.bundle.scenes.isNotEmpty()
        val emptyWarning = if (hasImportableContent) {
            emptyList()
        } else {
            listOf("No importable Tasker tasks, profiles, variables, or scenes were found.")
        }

        return TaskerImportPreview(
            sourceTaskCount = report.sourceTaskCount,
            sourceProfileCount = report.sourceProfileCount,
            sourceVariableCount = report.sourceVariableCount,
            sourceSceneCount = report.sourceSceneCount,
            importTaskCount = report.bundle.tasks.size,
            importProfileCount = report.bundle.profiles.size,
            importVariableCount = report.bundle.variables.size,
            importSceneCount = report.bundle.scenes.size,
            mappedActionCount = report.mappedActions.size,
            unsupportedActionCount = report.unsupportedActions.size,
            capabilityWarnings = report.bundle.metadata.capabilityRequirements
                .filter { it.level != CapabilityLevel.Supported }
                .map { "${it.actionId}: ${it.level.name.lowercase()} - ${it.reason}" },
            warnings = (report.warnings + plan.warnings + emptyWarning).distinct(),
            lossyWarnings = (report.lossyWarnings + plan.lossyWarnings).distinct(),
            canImport = plan.canImport && hasImportableContent,
        )
    }

    fun confirmedBundle(report: TaskerXmlImportReport): OpenTaskerBundle {
        val shouldAddDisabledWarning = report.bundle.profiles.isNotEmpty()
        val warnings = if (shouldAddDisabledWarning) {
            report.bundle.metadata.warnings + TASKER_DISABLED_PROFILE_WARNING
        } else {
            report.bundle.metadata.warnings
        }

        return report.bundle.copy(
            profiles = report.bundle.profiles.map { it.copy(enabled = false) },
            metadata = report.bundle.metadata.copy(warnings = warnings.distinct()),
        )
    }
}
