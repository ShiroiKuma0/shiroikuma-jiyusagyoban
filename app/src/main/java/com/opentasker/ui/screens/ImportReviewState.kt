package com.opentasker.ui.screens

import androidx.annotation.StringRes
import com.opentasker.app.R
import com.opentasker.core.transfer.MacroDroidImportPlanner
import com.opentasker.core.transfer.MacroDroidImportReport
import com.opentasker.core.transfer.OpenTaskerBundle
import com.opentasker.core.transfer.TaskerImportPlanner
import com.opentasker.core.transfer.TaskerImportPreview
import com.opentasker.core.transfer.TaskerXmlImportReport

internal data class TaskerImportReviewState(
    val bundle: OpenTaskerBundle,
    val preview: TaskerImportPreview,
    @StringRes val titleRes: Int,
    val mappedActionRows: List<String>,
    val unsupportedActionRows: List<String>,
)

/** A picked `.emdr`, waiting for the password it was exported with. */
internal class MacroDroidPasswordPrompt(val bytes: ByteArray, val wrongPassword: Boolean = false) {
    fun copy(wrongPassword: Boolean) = MacroDroidPasswordPrompt(bytes, wrongPassword)
}

internal fun taskerImportReviewState(report: TaskerXmlImportReport): TaskerImportReviewState =
    TaskerImportReviewState(
        bundle = TaskerImportPlanner.confirmedBundle(report),
        preview = TaskerImportPlanner.preview(report),
        titleRes = R.string.dialog_review_tasker,
        mappedActionRows = report.mappedActions.map {
            "${it.taskName}: ${it.taskerCode} -> ${it.openTaskerActionId}"
        },
        unsupportedActionRows = report.unsupportedActions.map {
            "${it.taskName} step ${it.actionIndex + 1}: code ${it.taskerCode}"
        },
    )

internal fun macroDroidImportReviewState(report: MacroDroidImportReport): TaskerImportReviewState =
    TaskerImportReviewState(
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
