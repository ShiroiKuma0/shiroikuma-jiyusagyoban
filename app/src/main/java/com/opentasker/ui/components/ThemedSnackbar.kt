package com.opentasker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.ui.theme.ThemeStore

/**
 * App-wide snackbar ("flash") in the fork's black-yellow flash format. Material's default snackbar draws
 * on `inverseSurface`, which in a black-yellow theme comes out yellow-fill / black-text (the regression
 * 白い熊 saw). This reuses the same flash* theme values as the standalone flash overlay so every transient
 * message matches: black background, yellow text, yellow border.
 */
@Composable
fun ThemedSnackbar(data: SnackbarData) {
    val prefs by ThemeStore.state.collectAsState()
    Surface(
        modifier = Modifier.padding(12.dp),
        color = Color(prefs.flashBackground),
        contentColor = Color(prefs.flashText),
        shape = RoundedCornerShape(prefs.flashCornerRadiusDp.dp),
        border = if (prefs.flashBorderWidthDp > 0)
            BorderStroke(prefs.flashBorderWidthDp.dp, Color(prefs.flashBorder)) else null,
    ) {
        Text(
            text = data.visuals.message,
            color = Color(prefs.flashText),
            fontSize = prefs.flashTextSizeSp.sp,
            fontWeight = FontWeight(prefs.flashFontWeight),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Drop-in replacement for `SnackbarHost(state)` that renders every message via [ThemedSnackbar]. */
@Composable
fun ThemedSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data -> ThemedSnackbar(data) }
}
