package com.opentasker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.storage.ItemMetaEntity
import kotlinx.coroutines.launch

/**
 * A foldable, persistent "Note" line for any list item, keyed by (tab, itemId) in [ItemMetaEntity].
 * Self-contained: it reads/writes its own row via the global DB, so it just drops into a card's expanded
 * body — `ItemNoteSection("tasks", task.id)`. The note's own fold state (noteExpanded) persists
 * independently of the card's fold, so it survives collapsing the card and reopening it later.
 */
@Composable
fun ItemNoteSection(tab: String, itemKey: String, modifier: Modifier = Modifier) {
    val dao = remember { OpenTaskerApp_NoHilt.db.itemMetaDao() }
    val meta by remember(tab, itemKey) { dao.getAsFlow(tab, itemKey) }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var editing by remember(tab, itemKey) { mutableStateOf(false) }

    val note = meta?.note.orEmpty()
    val noteExpanded = meta?.noteExpanded ?: false

    fun persist(newNote: String? = null, newExpanded: Boolean? = null) {
        scope.launch {
            val cur = dao.get(tab, itemKey) ?: ItemMetaEntity(tab = tab, itemKey = itemKey)
            dao.upsert(cur.copy(note = newNote ?: cur.note, noteExpanded = newExpanded ?: cur.noteExpanded))
        }
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { persist(newExpanded = !noteExpanded) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (note.isBlank()) "Add note" else "Note",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (noteExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (noteExpanded) "Collapse note" else "Expand note",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        if (noteExpanded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .clickable { editing = true }
                    .padding(10.dp),
            ) {
                Text(
                    note.ifBlank { "Tap to add a note." },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (note.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    color = if (note.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    if (editing) {
        var text by remember { mutableStateOf(note) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Note") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What does this do?") },
                    minLines = 3,
                )
            },
            confirmButton = {
                TextButton(onClick = { persist(newNote = text.trim(), newExpanded = true); editing = false }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
