package com.opentasker.ui.screens

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opentasker.app.R

@Composable
internal fun sceneOverlayReady(): Boolean = Settings.canDrawOverlays(LocalContext.current)

@Composable
internal fun SceneOverlayReadinessPill(
    overlayReady: Boolean,
    modifier: Modifier = Modifier,
) {
    SceneStatusPill(
        label = if (overlayReady) {
            stringResource(R.string.status_overlay_ready)
        } else {
            stringResource(R.string.status_needs_setup)
        },
        color = if (overlayReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}

@Composable
internal fun SceneOverlayButton(
    visible: Boolean,
    onShowOverlay: () -> Unit,
) {
    if (visible) {
        OutlinedButton(onClick = onShowOverlay) {
            Text(stringResource(R.string.action_show), maxLines = 1)
        }
    }
}

@Composable
private fun SceneStatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
