package com.opentasker.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * EVERY dialog in the app wears the yellow border — this is the only `AlertDialog` any screen calls.
 *
 * The border was a convention, passed by hand as `modifier = Modifier.border(1.5.dp, …)` on each
 * call, and a convention kept by hand is a convention half kept: on 2026-09-30 twenty-odd of the
 * app's dialogs had no border at all, 言語島's new-island dialog among them, and 白い熊 asked for
 * "this dialog and all such dialogs" to have it. So the border lives here, drawn around whatever
 * shape the dialog has, and `BorderedDialogTest` fails the build if a screen imports Material's own
 * `AlertDialog` again.
 *
 * Same name and parameters as Material3's, so a call site changes only its import.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}
