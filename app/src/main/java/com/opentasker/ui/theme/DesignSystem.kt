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
    val pill = 50.dp   // Full round (chips, avatar circles)
}

// ========== Elevation/Shadow Scale ==========
object Elevation {
    val none = 0.dp
    val sm = 1.dp      // Subtle lift
    val md = 4.dp      // Standard elevation
    val lg = 8.dp      // Prominent elevation
    val xl = 12.dp     // High elevation (dialogs, modals)
}

// ========== Extended Typography ==========
// Fine-tuned typography for better visual hierarchy
@Immutable
data class ExtendedTypography(
    // Headings
    val displayLarge: TextStyle = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp
    ),
    val displayMedium: TextStyle = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    val headlineLarge: TextStyle = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    val headlineMedium: TextStyle = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    val headlineSmall: TextStyle = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    // Title text
    val titleLarge: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    val titleMedium: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val titleSmall: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    // Body text
    val bodyLarge: TextStyle = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    val bodyMedium: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    val bodySmall: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    // Label text
    val labelLarge: TextStyle = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    val labelMedium: TextStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    val labelSmall: TextStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
)

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
}

// ========== Opacity Scale ==========
object Opacity {
    val disabled = 0.38f
    val secondary = 0.60f
    val tertiary = 0.38f
    val hintText = 0.60f
}
