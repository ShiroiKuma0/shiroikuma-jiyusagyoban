package com.sysadmindoc.ruledeck.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.widget.TaskPickerSample
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme

/**
 * The launcher's "Pick a task" dialog in its form of 2026-11-27: title with icon and explanation,
 * projects with the layers icon, a group with the folder, tasks as plain rows on a left rule, and
 * the Cancel pill pinned below. Rendered because the form is a matter of looks.
 */
@PreviewTest
@Preview(widthDp = 336, heightDp = 700)
@Composable
fun TaskPickerPreview() {
    RuleDeckTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Surface(
                Modifier.fillMaxSize().padding(8.dp).border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.background,
            ) { TaskPickerSample() }
        }
    }
}
