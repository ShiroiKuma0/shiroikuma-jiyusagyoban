package com.opentasker.automation.app

internal enum class AppUsagePollAction {
    PAUSE_FOR_MISSING_ACCESS,
    QUERY_FOREGROUND,
}

internal fun appUsagePollAction(hasUsageAccess: Boolean): AppUsagePollAction =
    if (hasUsageAccess) AppUsagePollAction.QUERY_FOREGROUND else AppUsagePollAction.PAUSE_FOR_MISSING_ACCESS
