package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme

/**
 * The App language picker in its settled form (2026-11-27): the pinned pair of pills, the framed box
 * with "System default" first, the flags. Rendered because the form is a matter of looks.
 */
@PreviewTest
@Preview(widthDp = 380, heightDp = 620)
@Composable
fun AppLanguageDialogPreview() {
    RuleDeckTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AppLanguageDialog(onDismiss = {})
        }
    }
}
