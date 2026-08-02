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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Command-center palette derived from the nine-screen product mockup. Graphite surfaces keep
// dense automation data calm, while iris, mint, blue, and coral communicate action and state.
private val Iris = Color(0xFF8B5CF6)
private val SignalBlue = Color(0xFF63B8FF)
private val SignalMint = Color(0xFF63E6A6)
private val SignalRed = Color(0xFFFF6B7A)
private val Text = Color(0xFFF1F4FA)
private val TextSecondary = Color(0xFFA9B3C5)
private val GraphiteBackground = Color(0xFF090C12)
private val GraphiteSurface = Color(0xFF10151E)
private val GraphiteElevated = Color(0xFF171E29)
private val GraphiteOutline = Color(0xFF344052)

private val Amoled = darkColorScheme(
    primary = Iris,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1745),
    onPrimaryContainer = Color(0xFFE5D2FF),
    secondary = SignalBlue,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF102B40),
    onSecondaryContainer = Color(0xFFCBE9FF),
    tertiary = SignalMint,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF103727),
    onTertiaryContainer = Color(0xFFC8F8DF),
    error = SignalRed,
    onError = Color(0xFF35000A),
    errorContainer = Color(0xFF47131D),
    onErrorContainer = Color(0xFFFFD9DE),
    background = GraphiteBackground,
    onBackground = Text,
    surface = GraphiteSurface,
    onSurface = Text,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = TextSecondary,
    outline = GraphiteOutline,
    outlineVariant = Color(0xFF273142),
)

// Catppuccin Latte palette for the light theme. Mirrors the dark scheme's structure so every
// semantic token (including the *Container and outlineVariant slots the UI relies on) is defined
// and coherent in both modes, rather than falling back to mismatched Material defaults.
private val LightIris = Color(0xFF6D35C8)
private val LightBlue = Color(0xFF176B9C)
private val LightMint = Color(0xFF147A4B)
private val LightRed = Color(0xFFB3263B)
private val LightText = Color(0xFF171A21)
private val LightSubtext = Color(0xFF566173)
private val LightBase = Color(0xFFF3F5F9)
private val LightSurface = Color(0xFFFFFFFF)
private val LightElevated = Color(0xFFE9EDF4)
private val LightOutline = Color(0xFF8C98AA)

private val Light = lightColorScheme(
    primary = LightIris,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECDDFF),
    onPrimaryContainer = Color(0xFF32125F),
    secondary = LightBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EEFF),
    onSecondaryContainer = Color(0xFF07334E),
    tertiary = LightMint,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1F3DF),
    onTertiaryContainer = Color(0xFF083D25),
    error = LightRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDADD),
    onErrorContainer = Color(0xFF6D001A),
    background = LightBase,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = LightElevated,
    onSurfaceVariant = LightSubtext,
    outline = LightOutline,
    outlineVariant = Color(0xFFC8D0DC),
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
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
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
            // Under edge-to-edge (target SDK 35+) window.statusBarColor/navigationBarColor are
            // deprecated no-ops; only the appearance flags still control bar icon contrast.
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme && !highContrast
                isAppearanceLightNavigationBars = !darkTheme && !highContrast
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = OpenTaskerTypography,
        shapes = OpenTaskerShapes,
        content = content,
    )
}

/**
 * Opaque selected-state fill for chips and rows. The container tint is composited over the surface so
 * a selection reads as a distinct, solid fill in both the AMOLED and light themes, instead of the
 * alpha-on-alpha wash that left selected chips distinguishable only by their border.
 */
@Composable
fun selectedContainerColor(): Color =
    MaterialTheme.colorScheme.primary
        .copy(alpha = 0.42f)
        .compositeOver(MaterialTheme.colorScheme.surface)
