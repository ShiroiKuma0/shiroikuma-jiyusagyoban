package com.opentasker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import android.os.Build
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

// Quiet Workshop palette. Cyan carries action and selection while navy creates structure on black.
private val BrandCyan = Color(0xFF13A8D5)
private val BrandCyanBright = Color(0xFF62DDF4)
private val SignalAmber = Color(0xFFFFB42E)
private val SignalRed = Color(0xFFFF6B66)
private val Text = Color(0xFFF7FAFF)
private val TextSecondary = Color(0xFFA7B3C6)
private val WorkshopBackground = Color(0xFF050A13)
private val WorkshopSurface = Color(0xFF081120)
private val WorkshopNavy = Color(0xFF0C172E)
private val WorkshopElevated = Color(0xFF12213B)
private val WorkshopOutline = Color(0xFF2B3D59)

private val Graphite = darkColorScheme(
    primary = BrandCyan,
    onPrimary = Color(0xFF001F2A),
    primaryContainer = Color(0xFF073B51),
    onPrimaryContainer = Color(0xFFBDEFFF),
    secondary = SignalAmber,
    onSecondary = Color(0xFF2A1A00),
    secondaryContainer = Color(0xFF3C2A08),
    onSecondaryContainer = Color(0xFFFFE0A0),
    tertiary = BrandCyanBright,
    onTertiary = Color(0xFF002A32),
    tertiaryContainer = Color(0xFF073943),
    onTertiaryContainer = Color(0xFFC2F4FF),
    error = SignalRed,
    onError = Color(0xFF35000A),
    errorContainer = Color(0xFF47131D),
    onErrorContainer = Color(0xFFFFD9DE),
    background = WorkshopBackground,
    onBackground = Text,
    surface = WorkshopSurface,
    onSurface = Text,
    surfaceVariant = WorkshopNavy,
    onSurfaceVariant = TextSecondary,
    outline = WorkshopOutline,
    outlineVariant = Color(0xFF1B2A40),
    // AlertDialog uses surfaceContainerHigh and DropdownMenu uses surfaceContainer. Leaving these
    // unset fell back to Material's purple-tinted baseline tokens, so every editor dialog and menu
    // in this dialog-heavy app rendered off-palette.
    surfaceContainerLowest = WorkshopBackground,
    surfaceContainerLow = WorkshopNavy,
    surfaceContainer = WorkshopSurface,
    surfaceContainerHigh = WorkshopElevated,
    surfaceContainerHighest = Color(0xFF182B49),
    surfaceBright = Color(0xFF213653),
    surfaceDim = WorkshopBackground,
)

/**
 * True-black variant of [Graphite].
 *
 * The scheme previously called "Amoled" used #101211, which lights every background pixel — the
 * saving an OLED user asks for only happens at #000000. Accent and text colours are unchanged, so
 * body text keeps 19:1 on black and secondary text ~8.8:1.
 */
private val Amoled = darkColorScheme(
    primary = BrandCyan,
    onPrimary = Color(0xFF001F2A),
    primaryContainer = Color(0xFF073B51),
    onPrimaryContainer = Color(0xFFBDEFFF),
    secondary = SignalAmber,
    onSecondary = Color(0xFF2A1A00),
    secondaryContainer = Color(0xFF342507),
    onSecondaryContainer = Color(0xFFFFE0A0),
    tertiary = BrandCyanBright,
    onTertiary = Color(0xFF002A32),
    tertiaryContainer = Color(0xFF073943),
    onTertiaryContainer = Color(0xFFC2F4FF),
    error = SignalRed,
    onError = Color(0xFF35000A),
    errorContainer = Color(0xFF360E16),
    onErrorContainer = Color(0xFFFFD9DE),
    background = Color.Black,
    onBackground = Text,
    surface = Color.Black,
    onSurface = Text,
    // Cards and dialogs need to separate from the background; keep them as dark as legibility allows.
    surfaceVariant = WorkshopNavy,
    onSurfaceVariant = TextSecondary,
    outline = WorkshopOutline,
    // Raised from #1B1F1D: against pure black, alpha-washed at the call sites, the old value
    // landed near 1.07:1 and card and section boundaries simply vanished. Text was never the
    // problem here - the grouping cues were.
    outlineVariant = Color(0xFF1B2A40),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = WorkshopNavy,
    surfaceContainer = Color(0xFF050B15),
    surfaceContainerHigh = WorkshopElevated,
    surfaceContainerHighest = Color(0xFF182B49),
    surfaceBright = Color(0xFF213653),
    surfaceDim = Color.Black,
)

// Catppuccin Latte palette for the light theme. Mirrors the dark scheme's structure so every
// semantic token (including the *Container and outlineVariant slots the UI relies on) is defined
// and coherent in both modes, rather than falling back to mismatched Material defaults.
private val LightCyan = Color(0xFF006A86)
private val LightAmber = Color(0xFF775500)
private val LightBlue = Color(0xFF146477)
private val LightRed = Color(0xFFA34141)
private val LightText = Color(0xFF162033)
private val LightSubtext = Color(0xFF59677C)
private val LightBase = Color(0xFFF3F7FC)
private val LightSurface = Color(0xFFFFFFFF)
private val LightElevated = Color(0xFFE8F0F8)
private val LightOutline = Color(0xFF78879A)

private val Light = lightColorScheme(
    primary = LightCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC4EFFF),
    onPrimaryContainer = Color(0xFF003545),
    secondary = LightAmber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7AC),
    onSecondaryContainer = Color(0xFF3C2B00),
    tertiary = LightBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC3EEF7),
    onTertiaryContainer = Color(0xFF00363F),
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
    outlineVariant = Color(0xFFD5E0EC),
    // Without these, dialogs and menus fall back to Material's lavender baseline
    // containers, which read as clearly off-palette against the warm cream surfaces.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = LightSurface,
    surfaceContainer = Color(0xFFF1F6FB),
    surfaceContainerHigh = LightElevated,
    surfaceContainerHighest = Color(0xFFDFEAF4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4EBF3),
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
        fontWeight = FontWeight.Bold,
        fontSize = 31.sp,
        lineHeight = 37.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
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
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
)

/**
 * Applies a persisted [ThemeMode].
 *
 * Every entry point took the mode apart itself, so each of the five copies had to be found and
 * updated whenever a mode was added — and adding one broke four activities at compile time. The
 * mapping lives here now.
 */
@Composable
fun OpenTaskerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.Dark, ThemeMode.Amoled, ThemeMode.HighContrast -> true
        ThemeMode.Light -> false
        ThemeMode.System, ThemeMode.Dynamic -> isSystemInDarkTheme()
    }
    OpenTaskerTheme(
        darkTheme = darkTheme,
        highContrast = themeMode == ThemeMode.HighContrast,
        amoled = themeMode == ThemeMode.Amoled,
        dynamicColor = themeMode == ThemeMode.Dynamic,
        content = content,
    )
}

@Composable
fun OpenTaskerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    amoled: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        highContrast -> HighContrast
        // Dynamic colour is opt-in and platform-gated; below API 31 it degrades to the static
        // scheme rather than silently doing nothing different.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        amoled && darkTheme -> Amoled
        darkTheme -> Graphite
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
