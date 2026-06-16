package com.opentasker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// AMOLED-black palette with Catppuccin Mocha accents.
// Optimized for premium dark theme with excellent contrast and visual hierarchy.
private val Mauve = Color(0xFFCBA6F7)          // Primary - vibrant purple
private val Sapphire = Color(0xFF74C7EC)       // Secondary - cool blue
private val Green = Color(0xFFA6E3A1)          // Tertiary - fresh green
private val Red = Color(0xFFF38BA8)            // Error - warm red
private val Peach = Color(0xFFFFB4A2)          // Warning accent
private val Text = Color(0xFFCDD6F4)           // Primary text - light lavender
private val TextSecondary = Color(0xFFA6ADC8)  // Secondary text - muted lavender
private val Surface0 = Color(0xFF11111B)       // Primary surface
private val Surface1 = Color(0xFF1E1E2E)       // Elevated surface
private val Overlay0 = Color(0xFF45475A)       // Subtle overlay

private val Amoled = darkColorScheme(
    primary = Mauve,                           // Primary actions, active states
    onPrimary = Color.Black,                   // Text on primary
    primaryContainer = Mauve.copy(alpha = 0.12f), // Subtle primary background
    onPrimaryContainer = Mauve,
    secondary = Sapphire,                      // Secondary actions
    onSecondary = Color.Black,
    secondaryContainer = Sapphire.copy(alpha = 0.12f),
    onSecondaryContainer = Sapphire,
    tertiary = Green,                          // Tertiary actions, success states
    onTertiary = Color.Black,
    tertiaryContainer = Green.copy(alpha = 0.12f),
    onTertiaryContainer = Green,
    error = Red,                               // Error states
    onError = Color.Black,
    errorContainer = Red.copy(alpha = 0.12f),
    onErrorContainer = Red,
    background = Color.Black,                  // True AMOLED black for status bar
    onBackground = Text,
    surface = Surface0,                        // Main surface color
    onSurface = Text,
    surfaceVariant = Surface1,                 // Elevated surfaces
    onSurfaceVariant = TextSecondary,
    outline = Overlay0,                        // Borders and dividers
    outlineVariant = Overlay0.copy(alpha = 0.5f),
)

// Catppuccin Latte palette for the light theme. Mirrors the dark scheme's structure so every
// semantic token (including the *Container and outlineVariant slots the UI relies on) is defined
// and coherent in both modes, rather than falling back to mismatched Material defaults.
private val LatteMauve = Color(0xFF8839EF)        // Primary
private val LatteSapphire = Color(0xFF209FB5)     // Secondary
private val LatteGreen = Color(0xFF40A02B)        // Tertiary / success
private val LatteRed = Color(0xFFD20F39)          // Error
private val LatteText = Color(0xFF4C4F69)         // Primary text
private val LatteSubtext = Color(0xFF6C6F85)      // Secondary text
private val LatteBase = Color(0xFFEFF1F5)         // App background
private val LatteSurface = Color(0xFFFFFFFF)      // Card/sheet surface
private val LatteElevated = Color(0xFFE6E9EF)     // Elevated surface (mantle)
private val LatteOverlay = Color(0xFF9CA0B0)      // Borders and dividers

private val Light = lightColorScheme(
    primary = LatteMauve,
    onPrimary = Color.White,
    primaryContainer = LatteMauve.copy(alpha = 0.12f),
    onPrimaryContainer = LatteMauve,
    secondary = LatteSapphire,
    onSecondary = Color.White,
    secondaryContainer = LatteSapphire.copy(alpha = 0.12f),
    onSecondaryContainer = LatteSapphire,
    tertiary = LatteGreen,
    onTertiary = Color.White,
    tertiaryContainer = LatteGreen.copy(alpha = 0.12f),
    onTertiaryContainer = LatteGreen,
    error = LatteRed,
    onError = Color.White,
    errorContainer = LatteRed.copy(alpha = 0.12f),
    onErrorContainer = LatteRed,
    background = LatteBase,
    onBackground = LatteText,
    surface = LatteSurface,
    onSurface = LatteText,
    surfaceVariant = LatteElevated,
    onSurfaceVariant = LatteSubtext,
    outline = LatteOverlay,
    outlineVariant = LatteOverlay.copy(alpha = 0.5f),
)

private val HighContrast = darkColorScheme(
    primary = Color(0xFFFFFF00),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF303000),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF00E5FF),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF003740),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF00E676),
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF003A1D),
    onTertiaryContainer = Color.White,
    error = Color(0xFFFF5252),
    onError = Color.Black,
    errorContainer = Color(0xFF4A0000),
    onErrorContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF171717),
    onSurfaceVariant = Color(0xFFF5F5F5),
    outline = Color.White,
    outlineVariant = Color(0xFFE0E0E0),
)

private val OpenTaskerTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

private val OpenTaskerShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
)

@Composable
fun OpenTaskerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        highContrast -> HighContrast
        darkTheme -> Amoled
        else -> Light
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !highContrast
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = OpenTaskerTypography,
        shapes = OpenTaskerShapes,
        content = content,
    )
}
