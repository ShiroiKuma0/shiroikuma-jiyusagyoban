package com.opentasker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * OpenTasker design system: Unified spacing, radius, and component utilities.
 */

object DesignSystem {
    // ========== Spacing Scale ==========
    // Consistent 4dp baseline spacing system for all padding/margins
    object Spacing {
        val xs = 4.dp      // Minimal spacing (icon margins, chip padding)
        val sm = 8.dp      // Small spacing (list item padding, button internal)
        val md = 12.dp     // Medium spacing (card padding, section separation)
        val lg = 16.dp     // Large spacing (page padding, major sections)
        val xl = 24.dp     // Extra large spacing (screen sections, major gaps)
        val xxl = 32.dp    // Double extra large (top-level section gaps)
    }

    // ========== Border Radius Scale ==========
    // Consistent radius scale for modern, slightly rounded aesthetic
    object Radii {
        val xs = 4.dp      // Tight radius (badge, small buttons)
        val sm = 6.dp      // Small radius (input fields, small components)
        val md = 8.dp      // Medium radius (cards, standard buttons)
        val lg = 12.dp     // Large radius (dialogs, large cards)
        val xl = 16.dp     // Extra large radius (bottom sheets, premium cards)
        val xxl = 18.dp    // Card radius (premium summary cards)
    }

    // ========== Elevation/Shadow Scale ==========
    object Elevation {
        val none = 0.dp
        val sm = 1.dp      // Subtle lift
        val md = 4.dp      // Standard elevation
        val lg = 8.dp      // Prominent elevation
        val xl = 12.dp     // High elevation (dialogs, modals)
    }

    // ========== Component Size Scale ==========
    object ComponentSize {
        // Button sizes
        val buttonSmall = 32.dp
        val buttonMedium = 40.dp
        val buttonLarge = 48.dp
        val buttonXLarge = 56.dp
        
        // Icon sizes
        val iconSmall = 16.dp
        val iconMedium = 20.dp
        val iconStandard = 24.dp
        val iconLarge = 32.dp
        val iconXLarge = 48.dp
        
        // Touch target minimum (accessibility)
        val touchTargetMin = 48.dp
        
        // List item height
        val listItemHeight = 64.dp
        
        // Checkbox size
        val checkboxSize = 24.dp
        
        // Status indicator size
        val statusIndicator = 12.dp
    }

    // ========== Semantic Colors ==========
    object SemanticColor {
        val warningDark = androidx.compose.ui.graphics.Color(0xFFFFB4A2) // Peach — warm amber for warnings in dark theme
        val warningLight = androidx.compose.ui.graphics.Color(0xFFDF8E1D) // Latte Yellow — warm amber for warnings in light theme
    }

    // ========== Opacity Scale ==========
    object Opacity {
        val disabled = 0.38f
        val secondary = 0.60f
        val tertiary = 0.38f
        val hintText = 0.60f
    }
}
