package com.opentasker.ui.screens

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.opentasker.app.R

@Composable
internal fun sceneOverlayReady(): Boolean = Settings.canDrawOverlays(LocalContext.current)

@Composable
internal fun SceneOverlayReadinessPill(
    overlayReady: Boolean,
    modifier: Modifier = Modifier,
) {
    StatusPill(
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
