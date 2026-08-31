package com.opentasker.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val QUICK_EMOJI = listOf(
    "⭐", "🔔", "🏠", "📱", "💡", "🔋", "📶", "🌙",
    "☀️", "🎵", "📷", "✅", "⚙️", "🔒", "🚀", "❤️",
    "🍎", "🍊", "🍇", "⏰", "📅", "🔥", "💧", "🎮",
    "📺", "✈️", "🚗", "💼", "🏃", "☕", "🌧️", "🔆",
)

/**
 * Pick an emoji (or a few characters) to use as a task icon. The chosen glyph is rendered to a PNG by
 * [com.opentasker.core.icons.TaskIconStore.saveFromText]. Tap a quick-pick or type with the keyboard's
 * emoji button.
 */
@Composable
internal fun EmojiPickerDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Emoji icon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(16) },
                    label = { Text("Emoji or characters") },
                    supportingText = { Text("Use your keyboard's emoji button, or tap one below.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Live preview of the glyph at a large size.
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    if (text.isNotBlank()) Text(text, fontSize = 48.sp, maxLines = 1)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 44.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(QUICK_EMOJI, key = { it }) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { text = emoji },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 24.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
