package com.opentasker.ui.screens

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.transfer.ImportPreview
import com.opentasker.core.transfer.MacroDroidImportPlanner
import com.opentasker.core.transfer.MacroDroidImportReport
import com.opentasker.core.transfer.OpenTaskerBundle

internal data class AutomationImportReviewState(
    val bundle: OpenTaskerBundle,
    val preview: ImportPreview,
    @StringRes val titleRes: Int,
    val mappedActionRows: List<String>,
    val unsupportedActionRows: List<String>,
)

internal fun macroDroidImportReviewState(report: MacroDroidImportReport): AutomationImportReviewState =
    AutomationImportReviewState(
        bundle = MacroDroidImportPlanner.confirmedBundle(report),
        preview = MacroDroidImportPlanner.preview(report),
        titleRes = R.string.dialog_review_macrodroid,
        mappedActionRows = report.mappedActions.map {
            "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} -> ${it.openTaskerActionIds.joinToString(" + ")}"
        },
        unsupportedActionRows = report.unsupportedActions.map {
            "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} (${it.reason})"
        },
    )
