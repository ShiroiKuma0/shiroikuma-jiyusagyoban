package com.opentasker.ui.screens

/**
 * Keeps the navigation rail available slightly earlier when accessibility font scaling needs
 * more room for labels. The policy is pure so width, resize, fold, and font-scale regressions can
 * be checked without a device or a screenshot renderer.
 */
internal fun usesNavigationRail(widthDp: Int, fontScale: Float = 1f): Boolean {
    val safeFontScale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val railThreshold = if (safeFontScale >= 1.3f) 560 else 600
    return widthDp >= railThreshold
}
