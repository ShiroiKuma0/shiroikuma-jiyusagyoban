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

// Premium command-center palette: warm text, quiet graphite surfaces, and one restrained
// sage accent. The UI should read like a workbench, not a collection of outlined cards.
private val Sage = Color(0xFFB7C7B0)
private val Sand = Color(0xFFD8C8AF)
private val CoolBlue = Color(0xFF9CB7B0)
private val SignalRed = Color(0xFFE38B84)
private val Text = Color(0xFFF4F1EA)
private val TextSecondary = Color(0xFFA9AEA8)
private val GraphiteBackground = Color(0xFF101211)
private val GraphiteSurface = Color(0xFF151817)
private val GraphiteElevated = Color(0xFF1C211F)
private val GraphiteOutline = Color(0xFF39413D)

private val Amoled = darkColorScheme(
    primary = Sage,
    onPrimary = Color(0xFF182019),
    primaryContainer = Color(0xFF2A352B),
    onPrimaryContainer = Color(0xFFD9E7D3),
    secondary = Sand,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF383328),
    onSecondaryContainer = Color(0xFFF0E2C8),
    tertiary = CoolBlue,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF263532),
    onTertiaryContainer = Color(0xFFD6E7E2),
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
    outlineVariant = Color(0xFF252B28),
    // AlertDialog uses surfaceContainerHigh and DropdownMenu uses surfaceContainer. Leaving these
    // unset fell back to Material's purple-tinted baseline tokens, so every editor dialog and menu
    // in this dialog-heavy app rendered off-palette.
    surfaceContainerLowest = Color(0xFF0D0F0E),
    surfaceContainerLow = Color(0xFF131615),
    surfaceContainer = GraphiteSurface,
    surfaceContainerHigh = GraphiteElevated,
    surfaceContainerHighest = Color(0xFF222825),
    surfaceBright = Color(0xFF2A312E),
    surfaceDim = GraphiteBackground,
)

// Catppuccin Latte palette for the light theme. Mirrors the dark scheme's structure so every
// semantic token (including the *Container and outlineVariant slots the UI relies on) is defined
// and coherent in both modes, rather than falling back to mismatched Material defaults.
private val LightSage = Color(0xFF4D6653)
private val LightSand = Color(0xFF765E3D)
private val LightBlue = Color(0xFF4D6864)
private val LightRed = Color(0xFF9D4F4B)
private val LightText = Color(0xFF20241F)
private val LightSubtext = Color(0xFF606960)
private val LightBase = Color(0xFFF4F2EC)
private val LightSurface = Color(0xFFFBFAF6)
private val LightElevated = Color(0xFFE9ECE4)
private val LightOutline = Color(0xFF89928A)

private val Light = lightColorScheme(
    primary = LightSage,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8D8),
    onPrimaryContainer = Color(0xFF1F3524),
    secondary = LightSand,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E5D2),
    onSecondaryContainer = Color(0xFF43321C),
    tertiary = LightBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDEAE6),
    onTertiaryContainer = Color(0xFF1E3934),
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
    outlineVariant = Color(0xFFD7DDD5),
    // Without these, dialogs and menus fall back to Material's lavender baseline
    // containers, which read as clearly off-palette against the warm cream surfaces.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurface,
    surfaceContainer = Color(0xFFF1EFE8),
    surfaceContainerHigh = LightElevated,
    surfaceContainerHighest = Color(0xFFE1E5DC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E2DB),
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
    // High contrast keeps dialogs black rather than Material's #2B2930 baseline.
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF0D0D0D),
    surfaceContainerHighest = Color(0xFF171717),
    surfaceBright = Color(0xFF171717),
    surfaceDim = Color.Black,
)

private val OpenTaskerTypography = Typography(
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 15.sp,
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
        .copy(alpha = 0.22f)
        .compositeOver(MaterialTheme.colorScheme.surface)
