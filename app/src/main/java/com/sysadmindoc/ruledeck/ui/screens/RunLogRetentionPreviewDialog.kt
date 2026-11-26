package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.sysadmindoc.ruledeck.R
import java.util.Date

@Composable
internal fun RunLogRetentionPreviewDialog(
    preview: RunLogRetentionPreview,
    onDismiss: () -> Unit,
    onExportJson: () -> Unit,
    onConfirm: () -> Unit,
) {
    val context = LocalContext.current
    val oldest = preview.oldestTimestamp?.let { timestamp ->
        displayDateTimeFormat(context, withSeconds = false).format(Date(timestamp))
    } ?: stringResource(R.string.label_none)
    val removed = preview.prunableCount + preview.heldPrunableCount
    val body = stringResource(
        R.string.run_log_retention_preview_body,
        pluralStringResource(R.plurals.count_run_log_entries, preview.storedCount, preview.storedCount),
        oldest,
        pluralStringResource(R.plurals.count_run_log_entries, removed, removed),
    )
    // Held runs can still be replayed, so losing them is called out on its own. Chinese joins the two
    // sentences without a space, so the join is a resource too.
    val text = if (preview.heldPrunableCount > 0) {
        stringResource(
            R.string.run_log_retention_preview_body_with_held,
            body,
            pluralStringResource(R.plurals.run_log_retention_preview_held, preview.heldPrunableCount, preview.heldPrunableCount),
        )
    } else {
        body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_log_retention_preview_title)) },
        text = {
            Text(text, modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(R.string.run_log_retention_confirm)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onExportJson) { Text(stringResource(R.string.run_log_export_before_pruning)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
