package com.opentasker.core.transfer

/** Counts and warnings shown before an imported automation file is written to the library. */
data class ImportPreview(
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
    val powerRequests: List<RecipePowerRequest>,
    val warnings: List<String>,
    val lossyWarnings: List<String>,
    val canImport: Boolean,
)
