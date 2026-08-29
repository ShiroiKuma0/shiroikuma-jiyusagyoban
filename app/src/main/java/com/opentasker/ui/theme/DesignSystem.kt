package com.opentasker.ui.theme

import androidx.compose.ui.unit.dp

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
        val xl = 20.dp     // Extra large spacing (screen sections, major gaps)
        val xxl = 28.dp    // Double extra large (top-level section gaps)
    }

    object Screen {
        val horizontalPadding = 18.dp
        val verticalPadding = 12.dp
        val cardPadding = 14.dp
        val heroCardPadding = 16.dp
        val sectionGap = 16.dp
        val cardGap = 6.dp
    }

    // ========== Border Radius Scale ==========
    // Consistent radius scale for modern, slightly rounded aesthetic
    object Radii {
        val xs = 2.dp      // Tight radius (small controls)
        val sm = 4.dp      // Small radius (inputs, compact controls)
        val md = 6.dp      // Medium radius (cards, standard buttons)
        val lg = 8.dp      // Large radius (dialogs, large cards)
        val xl = 8.dp      // Extra large radius (bottom sheets, premium cards)
        val xxl = 8.dp     // Maximum surface radius
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
        val listItemHeight = 56.dp
        
        // Checkbox size
        val checkboxSize = 24.dp
        
        // Status indicator size
        val statusIndicator = 12.dp

        val compactControlHeight = 36.dp
        val cardActionHeight = 40.dp
    }

    // ========== Semantic Colors ==========
    object SemanticColor {
        val warningDark = androidx.compose.ui.graphics.Color(0xFFFFB42E)
        // Text-grade amber, not the accent-grade #DF8E1D it replaced: that measured 2.5:1 on
        // surface and 2.2:1 on surfaceVariant, below the 4.5:1 AA floor for the 13sp warning
        // text it is used for.
        val warningLight = androidx.compose.ui.graphics.Color(0xFF775500)
    }

    // ========== Opacity Scale ==========
    object Opacity {
        val disabled = 0.38f
        val secondary = 0.60f
        val tertiary = 0.38f
        val hintText = 0.60f
        val elevatedSurface = 0.92f
        val restingSurface = 0.78f
        val selectedSurface = 0.50f
        val subtleBorder = 0.42f
    }
}
