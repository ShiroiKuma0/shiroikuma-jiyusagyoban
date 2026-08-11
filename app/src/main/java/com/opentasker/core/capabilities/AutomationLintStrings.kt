package com.opentasker.core.capabilities

import android.content.res.Resources
import com.opentasker.app.R

data class AutomationLintCopy(
    val title: String,
    val detail: String,
    val suggestedFix: String,
)

/** Presentation copy for structural lint; callers that show findings supply app resources. */
interface AutomationLintStrings {
    fun missingReversal(profileName: String, writes: String): AutomationLintCopy
    fun repeatedTriggering(profileName: String): AutomationLintCopy
    fun priorityConflict(leftName: String, rightName: String, overlap: String, leftPriority: Int, rightPriority: Int, equalPriority: Boolean): AutomationLintCopy
    fun interProfileLoop(path: String): AutomationLintCopy

    companion object {
        fun from(resources: Resources): AutomationLintStrings = ResourceAutomationLintStrings(resources)
        val English: AutomationLintStrings get() = EnglishAutomationLintStrings
    }
}

private class ResourceAutomationLintStrings(
    private val resources: Resources,
) : AutomationLintStrings {
    override fun missingReversal(profileName: String, writes: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_missing_reversal_title),
        detail = resources.getString(R.string.automation_lint_missing_reversal_detail, profileName, writes),
        suggestedFix = resources.getString(R.string.automation_lint_missing_reversal_fix),
    )

    override fun repeatedTriggering(profileName: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_repeated_triggering_title),
        detail = resources.getString(R.string.automation_lint_repeated_triggering_detail, profileName),
        suggestedFix = resources.getString(R.string.automation_lint_repeated_triggering_fix),
    )

    override fun priorityConflict(
        leftName: String,
        rightName: String,
        overlap: String,
        leftPriority: Int,
        rightPriority: Int,
        equalPriority: Boolean,
    ): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_priority_conflict_title),
        detail = resources.getString(
            R.string.automation_lint_priority_conflict_detail,
            leftName,
            rightName,
            overlap,
            leftPriority,
            rightPriority,
        ),
        suggestedFix = resources.getString(
            if (equalPriority) R.string.automation_lint_priority_conflict_equal_fix
            else R.string.automation_lint_priority_conflict_ordered_fix,
        ),
    )

    override fun interProfileLoop(path: String): AutomationLintCopy = AutomationLintCopy(
        title = resources.getString(R.string.automation_lint_inter_profile_loop_title),
        detail = resources.getString(R.string.automation_lint_inter_profile_loop_detail, path),
        suggestedFix = resources.getString(R.string.automation_lint_inter_profile_loop_fix),
    )
}

private object EnglishAutomationLintStrings : AutomationLintStrings {
    override fun missingReversal(profileName: String, writes: String): AutomationLintCopy = AutomationLintCopy(
        title = "Missing reversal",
        detail = "$profileName writes $writes when it enters but has no exit task.",
        suggestedFix = "Add an exit task that restores the setting, or use a bounded temporary-state action.",
    )

    override fun repeatedTriggering(profileName: String): AutomationLintCopy = AutomationLintCopy(
        title = "Repeated triggering",
        detail = "$profileName has a state context without a cooldown, dwell, or explicit idempotency guard.",
        suggestedFix = "Add a cooldown, a dwell requirement, or an explicit guard condition to the enter task.",
    )

    override fun priorityConflict(
        leftName: String,
        rightName: String,
        overlap: String,
        leftPriority: Int,
        rightPriority: Int,
        equalPriority: Boolean,
    ): AutomationLintCopy = AutomationLintCopy(
        title = "Priority conflict",
        detail = "$leftName and $rightName can both write $overlap while their profile priorities are $leftPriority and $rightPriority.",
        suggestedFix = if (equalPriority) {
            "Raise one task priority or make the contexts mutually exclusive."
        } else {
            "Confirm that the higher-priority task should win, or make the contexts mutually exclusive."
        },
    )

    override fun interProfileLoop(path: String): AutomationLintCopy = AutomationLintCopy(
        title = "Inter-profile loop",
        detail = "Profiles form a direct task cycle: $path.",
        suggestedFix = "Remove one task.run edge or add an explicit state guard so the chain cannot retrigger itself.",
    )
}
