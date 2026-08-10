package com.opentasker.ui.screens

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Document-picker launchers for the automation shell.
 *
 * Each of these was five lines of identical boilerplate in `ActiveAutomationUi.kt`, whose size is
 * capped by `ActiveAutomationModuleSplitTest`.
 */
@Composable
internal fun rememberCreateDocumentLauncher(
    mimeType: String,
    onUri: (Uri) -> Unit,
): ManagedActivityResultLauncher<String, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        uri?.let(onUri)
    }

@Composable
internal fun rememberOpenDocumentLauncher(
    onUri: (Uri) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onUri)
    }
