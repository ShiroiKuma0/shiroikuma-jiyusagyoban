package com.opentasker.ui.gengoshima

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.ui.theme.OpenTaskerTheme

/** The 言語島 board, looked at without the phone: folded (narrow) and open (wide). */
@Composable
private fun Frame(busy: String? = null, inbox: Int = 3) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            GengoshimaBoardScreen(PaddingValues(10.dp), busy = busy, inboxCount = inbox, onRun = {}, onClose = {})
        }
    }
}

@PreviewTest
@Preview(name = "言語島 board — folded", widthDp = 413, heightDp = 1900, showBackground = true)
@Composable
fun GengoshimaBoardFoldedPreview() = Frame()

@PreviewTest
@Preview(name = "言語島 board — open", widthDp = 900, heightDp = 1100, showBackground = true)
@Composable
fun GengoshimaBoardOpenPreview() = Frame(busy = "言語島 聴く -- [227]", inbox = 0)
