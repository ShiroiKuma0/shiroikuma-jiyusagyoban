package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opentasker.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RunLogRetentionPreviewDialog(
    preview: RunLogRetentionPreview,
    onDismiss: () -> Unit,
    onExportJson: () -> Unit,
    onConfirm: () -> Unit,
) {
    val oldest = preview.oldestTimestamp?.let { timestamp ->
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    } ?: stringResource(R.string.label_none)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.run_log_retention_preview_title)) },
        text = {
            Text(
                stringResource(
                    R.string.run_log_retention_preview_body,
                    preview.storedCount,
                    oldest,
                    preview.prunableCount,
                ),
            )
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
