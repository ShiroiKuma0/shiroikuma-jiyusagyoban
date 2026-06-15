package com.opentasker.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** Contextual bar shown while a multi-selection is active: count, select-all, delete, and clear. */
@Composable
fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSelectAll, enabled = count < total) { Text("Select all") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete selected") }
        }
    }
}

/** Confirmation dialog for deleting a multi-selection of [count] items named [noun]. */
@Composable
fun ConfirmDeleteSelected(count: Int, noun: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Delete $count $noun${if (count == 1) "" else "s"}?") },
        text = { Text("This permanently removes the selected ${noun}s.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Long-press starts / extends a multi-selection; a tap toggles the item's membership while a selection
 * is active, otherwise runs [onTapNormal] (the item's usual tap behaviour).
 */
fun Modifier.selectableItem(
    selectionActive: Boolean,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onTapNormal: () -> Unit = {},
): Modifier = this.pointerInput(selectionActive) {
    detectTapGestures(
        onLongPress = { onLongPress() },
        onTap = { if (selectionActive) onToggleSelect() else onTapNormal() },
    )
}
