package com.sysadmindoc.ruledeck.ui.theme

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
import androidx.compose.ui.graphics.compositeOver
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

private val RuleDeckShapes = Shapes(
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
    // Every entry is a role of its own (UiRoles.kt, RoleSection.SCREENS): by default they are what
    // they were when one accent fed them all, and each can now be set apart on the UI page.
    fun c(role: UiRole) = Color(UiRoles.argb(role, prefs))
    val background = Color(prefs.background)
    val surface = Color(prefs.surface)
    val container = c(UiRole.SCHEME_CONTAINER)

    return darkColorScheme(
        primary = c(UiRole.SCHEME_PRIMARY),
        onPrimary = c(UiRole.SCHEME_ON_PRIMARY),
        primaryContainer = c(UiRole.SCHEME_PRIMARY_CONTAINER),
        onPrimaryContainer = c(UiRole.SCHEME_ON_PRIMARY_CONTAINER),
        secondary = c(UiRole.SCHEME_SECONDARY),
        onSecondary = c(UiRole.SCHEME_ON_SECONDARY),
        secondaryContainer = c(UiRole.SCHEME_SECONDARY_CONTAINER),
        onSecondaryContainer = c(UiRole.SCHEME_ON_SECONDARY_CONTAINER),
        tertiary = c(UiRole.SCHEME_TERTIARY),
        onTertiary = c(UiRole.SCHEME_ON_TERTIARY),
        tertiaryContainer = c(UiRole.SCHEME_TERTIARY_CONTAINER),
        onTertiaryContainer = c(UiRole.SCHEME_ON_TERTIARY_CONTAINER),
        error = c(UiRole.SCHEME_ERROR),
        onError = c(UiRole.SCHEME_ON_ERROR),
        errorContainer = c(UiRole.SCHEME_ERROR_CONTAINER),
        onErrorContainer = c(UiRole.SCHEME_ON_ERROR_CONTAINER),
        background = background,
        onBackground = c(UiRole.SCHEME_ON_BACKGROUND),
        surface = surface,
        onSurface = c(UiRole.SCHEME_ON_SURFACE),
        surfaceVariant = c(UiRole.SCHEME_SURFACE_VARIANT),
        // Material3 1.4 ("expressive") routes Outlined/Text button LABEL + ICON colour to
        // onSurfaceVariant (not primary as older versions did). Its default is therefore the
        // full-strength text colour, not the dim textSecondary, which made every button's text
        // render as a muted/olive yellow while its border stayed bright.
        onSurfaceVariant = c(UiRole.SCHEME_ON_SURFACE_VARIANT),
        // All surfaceContainer roles share one role, by default the background, so elevated popups —
        // DropdownMenus (surfaceContainer) and AlertDialogs (surfaceContainerHigh) — render true
        // black, not the lifted/brownish `surface`. Cards keep `surface` (set directly).
        surfaceContainerLowest = container,
        surfaceContainerLow = container,
        surfaceContainer = container,
        surfaceContainerHigh = container,
        surfaceContainerHighest = container,
        surfaceTint = Color.Transparent,
        inverseSurface = c(UiRole.SCHEME_INVERSE_SURFACE),
        inverseOnSurface = c(UiRole.SCHEME_INVERSE_ON_SURFACE),
        outline = c(UiRole.SCHEME_OUTLINE),
        outlineVariant = c(UiRole.SCHEME_OUTLINE_VARIANT),
        scrim = c(UiRole.SCHEME_SCRIM),
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
fun RuleDeckTheme(
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
            // Under edge-to-edge (target SDK 35+) window.statusBarColor/navigationBarColor are
            // deprecated no-ops; only the appearance flags still control bar icon contrast.
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // The theme's black window background is only what the platform draws before any code
            // runs; from here on the window wears the background 白い熊 set.
            // Only an opaque window is repainted: the dialog and over-lock-screen activities have a
            // see-through window on purpose, and painting it would black out what is behind them.
            val ground = window.decorView.background as? android.graphics.drawable.ColorDrawable
            if (ground != null && android.graphics.Color.alpha(ground.color) == 255 && ground.color != prefs.background) {
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(prefs.background))
            }
            // Background is user-chosen and usually dark; keep light status-bar/nav-bar icons unless
            // the chosen background is itself light.
            val lightBg = colors.background.luminance() > 0.5f
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightBg
                isAppearanceLightNavigationBars = lightBg
            }
        }
    }
    val roles = remember(prefs) { RolePalette(prefs) }
    androidx.compose.runtime.CompositionLocalProvider(LocalRolePalette provides roles) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = RuleDeckShapes,
            content = content,
        )
    }
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

// ---- colour helpers ----

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

