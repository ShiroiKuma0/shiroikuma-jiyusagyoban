package com.sysadmindoc.ruledeck.ui.screens

import androidx.annotation.StringRes
import com.sysadmindoc.ruledeck.R
import com.sysadmindoc.ruledeck.core.transfer.ImportPreview
import com.sysadmindoc.ruledeck.core.transfer.MacroDroidImportPlanner
import com.sysadmindoc.ruledeck.core.transfer.MacroDroidImportReport
import com.sysadmindoc.ruledeck.core.transfer.RuleDeckBundle

internal data class AutomationImportReviewState(
    val bundle: RuleDeckBundle,
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
            "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} -> ${it.ruleDeckActionIds.joinToString(" + ")}"
        },
        unsupportedActionRows = report.unsupportedActions.map {
            "${it.macroName} step ${it.actionIndex + 1}: ${it.classType} (${it.reason})"
        },
    )
