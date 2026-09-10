package com.opentasker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
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
 *
 * ## The Undo pill and the close button
 *
 * When the message carries an action label the bar grows a pill and a ✕. Both are drawn here rather
 * than left to Material, for the same reason the surface is: Material's own action slot picks its
 * colour from the theme's inverse pair and would come out unreadable in this palette.
 *
 * A bar with a ✕ is shown INDEFINITELY (see the caller). Material's longest fixed duration is about
 * ten seconds, which is not long enough to notice a mistake, read what it was and decide — and once
 * there is a way to dismiss it by hand, a timeout is a way to lose the offer rather than a courtesy.
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = data.visuals.message,
                color = Color(prefs.flashText),
                fontSize = prefs.flashTextSizeSp.sp,
                fontWeight = FontWeight(prefs.flashFontWeight),
                modifier = Modifier.weight(1f, fill = false).padding(vertical = 4.dp),
            )
            data.visuals.actionLabel?.let { label ->
                Spacer(Modifier.width(12.dp))
                // A pill, outlined in the flash border colour: it has to read as pressable against a
                // black ground without borrowing a hue, since hue is never load-bearing here.
                Surface(
                    onClick = { data.performAction() },
                    color = Color.Transparent,
                    contentColor = Color(prefs.flashText),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, Color(prefs.flashBorder)),
                ) {
                    Text(
                        text = label,
                        color = Color(prefs.flashText),
                        fontSize = prefs.flashTextSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (data.visuals.withDismissAction) {
                IconButton(onClick = { data.dismiss() }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = Color(prefs.flashText),
                    )
                }
            }
        }
    }
}

/** Drop-in replacement for `SnackbarHost(state)` that renders every message via [ThemedSnackbar]. */
@Composable
fun ThemedSnackbarHost(hostState: SnackbarHostState) {
    SnackbarHost(hostState) { data -> ThemedSnackbar(data) }
}
