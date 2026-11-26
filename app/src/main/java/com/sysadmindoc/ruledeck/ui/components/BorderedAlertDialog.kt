package com.sysadmindoc.ruledeck.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialogDefaults
import com.sysadmindoc.ruledeck.ui.theme.UiRole
import com.sysadmindoc.ruledeck.ui.theme.role
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
 * Same name and parameters as Material3's, so a call site changes only its import. Ground, frame,
 * title, text and icon are roles of their own (UiRoles.kt).
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
    containerColor: Color = role(UiRole.DIALOG_GROUND),
    iconContentColor: Color = role(UiRole.DIALOG_ICON),
    titleContentColor: Color = role(UiRole.DIALOG_TITLE),
    textContentColor: Color = role(UiRole.DIALOG_BODY),
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(1.5.dp, role(UiRole.DIALOG_FRAME), shape),
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

/** The frame of a dialog that is not an [AlertDialog] — a bare `Dialog { }` panel wears it too. */
@Composable
fun Modifier.dialogFrame(shape: Shape): Modifier = border(1.5.dp, role(UiRole.DIALOG_FRAME), shape)
