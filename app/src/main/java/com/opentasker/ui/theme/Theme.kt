package com.opentasker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// AMOLED-black palette with Catppuccin Mocha accents.
private val Mauve = Color(0xFFCBA6F7)
private val Sapphire = Color(0xFF74C7EC)
private val Green = Color(0xFFA6E3A1)
private val Red = Color(0xFFF38BA8)
private val Text = Color(0xFFCDD6F4)
private val Surface0 = Color(0xFF11111B)

private val Amoled = darkColorScheme(
    primary = Mauve,
    secondary = Sapphire,
    tertiary = Green,
    error = Red,
    background = Color.Black,
    surface = Surface0,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Text,
    onSurface = Text,
)

private val Light = lightColorScheme(
    primary = Color(0xFF8839EF),
    secondary = Color(0xFF209FB5),
    tertiary = Color(0xFF40A02B),
)

@Composable
fun OpenTaskerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) Amoled else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
