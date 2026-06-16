package com.opentasker.ui.theme

import android.app.Activity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Base text styles (weights/sizes only); colour and font family/scale come from ThemePrefs.
private val BaseTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    // Button text uses labelLarge. Pure #FFFF00 at the old Medium weight read as a dim/"alpha" yellow
    // against black at small sizes; SemiBold makes it render as saturated, full-strength yellow like the
    // headings. Lower-priority affordances are plain TextButtons (no border), which carry the hierarchy.
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val OpenTaskerShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
)

/**
 * Black-yellow-by-default scheme assembled from the user's [ThemePrefs].
 *
 * Two things keep it clean: every *Container role is a flat dark surface (not a low-alpha accent,
 * which composites to olive over black), and [ColorScheme.surfaceTint] is transparent so Material's
 * tonal-elevation overlay never tints surfaces. The result is true black with pure-yellow content.
 */
fun colorSchemeFrom(prefs: ThemePrefs): ColorScheme {
    val background = Color(prefs.background)
    val surface = Color(prefs.surface)
    val text = Color(prefs.text)
    val textSecondary = Color(prefs.textSecondary)
    val accent = Color(prefs.accent)
    val onAccent = accent.contrastingOnColor()
    val border = Color(prefs.border)
    val error = Color(0xFFFF6B6B)

    return darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surface,
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = surface,
        onSecondaryContainer = accent,
        tertiary = accent,
        onTertiary = onAccent,
        tertiaryContainer = surface,
        onTertiaryContainer = accent,
        error = error,
        onError = Color.Black,
        errorContainer = surface,
        onErrorContainer = error,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface,
        // Material3 1.4 ("expressive") routes Outlined/Text button LABEL + ICON colour to
        // onSurfaceVariant (not primary as older versions did). Mapping it to the dim textSecondary
        // made every button's text render as a muted/olive yellow while its border stayed bright.
        // Point it at the full-strength text colour so button labels are pure #FFFF00 like the headings.
        onSurfaceVariant = text,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        surfaceTint = Color.Transparent,
        inverseSurface = text,
        inverseOnSurface = background,
        outline = border,
        outlineVariant = border,
        scrim = Color.Black,
    )
}

/** Applies the chosen font family, optional uniform weight, and size scale to every text style. */
fun typographyFrom(prefs: ThemePrefs, fontFamily: FontFamily?): Typography {
    val scale = prefs.fontScalePct / 100f
    val weight = if (prefs.fontWeight in 100..900) FontWeight(prefs.fontWeight) else null

    fun TextStyle.themed(): TextStyle = copy(
        fontFamily = fontFamily ?: this.fontFamily,
        fontWeight = weight ?: this.fontWeight,
        fontSize = if (fontSize.isSp) (fontSize.value * scale).sp else fontSize,
        lineHeight = if (lineHeight.isSp) (lineHeight.value * scale).sp else lineHeight,
    )

    val b = BaseTypography
    return Typography(
        displayLarge = b.displayLarge.themed(),
        displayMedium = b.displayMedium.themed(),
        displaySmall = b.displaySmall.themed(),
        headlineLarge = b.headlineLarge.themed(),
        headlineMedium = b.headlineMedium.themed(),
        headlineSmall = b.headlineSmall.themed(),
        titleLarge = b.titleLarge.themed(),
        titleMedium = b.titleMedium.themed(),
        titleSmall = b.titleSmall.themed(),
        bodyLarge = b.bodyLarge.themed(),
        bodyMedium = b.bodyMedium.themed(),
        bodySmall = b.bodySmall.themed(),
        labelLarge = b.labelLarge.themed(),
        labelMedium = b.labelMedium.themed(),
        labelSmall = b.labelSmall.themed(),
    )
}

@Composable
fun OpenTaskerTheme(
    prefs: ThemePrefs = ThemePrefs.DEFAULT,
    content: @Composable () -> Unit,
) {
    val colors = remember(prefs) { colorSchemeFrom(prefs) }
    val fontFamily = remember(prefs.fontFileName) { ThemeStore.fontFamily(prefs.fontFileName) }
    val typography = remember(prefs, fontFamily) { typographyFrom(prefs, fontFamily) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Only an Activity has a status bar to tint; in a scene overlay window the context is the
            // Application, so skip it (casting unconditionally would crash the overlay).
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            // Background is user-chosen and usually dark; keep light status-bar icons unless the
            // chosen background is itself light.
            val lightBg = colors.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = lightBg
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = OpenTaskerShapes,
        content = content,
    )
}

// ---- colour helpers ----

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

private fun Color.contrastingOnColor(): Color = if (luminance() > 0.5f) Color.Black else Color.White
