package com.opentasker.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.opentasker.ui.theme.ThemeStore
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Contextual bar shown while a multi-selection is active: count, select-all, clear, delete, and any
 * extra per-tab actions (e.g. "Move to project", shown only when [onMoveToProject] is provided).
 */
@Composable
fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onMoveToProject: (() -> Unit)? = null,
    // Clipboard actions (Tasks tab only): a ⋮-style copy menu is shown when [onCopy] is provided. Paste is
    // listed only when [onPaste] != null (something is on the clipboard).
    onClone: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onCut: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    // Shown as "$count $noun" — defaults to the item wording; the group bar passes "groups".
    noun: String = "selected",
) {
    val prefs by ThemeStore.state.collectAsState()
    Surface(color = Color(prefs.selectionColor)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
            Text("$count $noun", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSelectAll, enabled = count < total) { Text("Select all") }
            if (onCopy != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy / cut / clone / paste") }
                    ThemedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        onClone?.let { act -> DropdownMenuItem(text = { Text("Clone") }, onClick = { menu = false; act() }) }
                        DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy() })
                        onCut?.let { act -> DropdownMenuItem(text = { Text("Cut") }, onClick = { menu = false; act() }) }
                        onPaste?.let { act -> DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; act() }) }
                    }
                }
            }
            if (onMoveToProject != null) {
                IconButton(onClick = onMoveToProject) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Move selected to project") }
            }
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
 * Compact (24dp) selection indicator for a list card's header. Unlike a Material `Checkbox` it doesn't
 * enforce a 48dp touch target, so showing it when a selection starts doesn't grow the card's height —
 * the row's tap (via [selectableItem]) already toggles membership, so the indicator is display-only.
 */
@Composable
fun SelectionCheck(selected: Boolean) {
    Icon(
        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
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
