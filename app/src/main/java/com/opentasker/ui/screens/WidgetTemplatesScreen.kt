package com.opentasker.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.core.widget.WidgetRenderer
import com.opentasker.ui.theme.ThemeStore
import com.opentasker.widget.WidgetEditor
import com.opentasker.widget.WidgetTemplate

/**
 * The "Widgets" tab: a library of named widget-layout templates. Create / edit a template in the
 * visual editor, copy its JSON, or delete it. The Set Widget action references a template by name.
 */
@Composable
fun WidgetTemplatesScreen(
    templates: List<WidgetTemplate>,
    onSave: (name: String, layout: String) -> Unit,
    onDelete: (name: String) -> Unit,
    contentPadding: PaddingValues,
) {
    // The template currently open in the full-screen editor (null = none).
    var editing by remember { mutableStateOf<WidgetTemplate?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        OutlinedButton(
            onClick = { showNameDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("  New template")
        }

        if (templates.isEmpty()) {
            Text(
                "No templates yet. Create one, design it in the editor, then point a Set Widget action at it by name.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(templates, key = { it.name }) { template ->
                TemplateRow(
                    template = template,
                    onEdit = { editing = template },
                    onCopy = { clipboard.setText(AnnotatedString(template.layout)) },
                    onDelete = { onDelete(template.name) },
                )
            }
        }
    }

    if (showNameDialog) {
        NameDialog(
            existing = templates.map { it.name },
            onConfirm = { name ->
                showNameDialog = false
                editing = WidgetTemplate(name, WidgetLayoutCodec.encode(WidgetLayoutCodec.PLACEHOLDER))
            },
            onDismiss = { showNameDialog = false },
        )
    }

    editing?.let { target ->
        Dialog(
            onDismissRequest = { editing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            WidgetEditor(
                initialJson = target.layout,
                onDone = { layout -> onSave(target.name, layout); editing = null },
                onCancel = { editing = null },
            )
        }
    }
}

@Composable
private fun TemplateRow(
    template: WidgetTemplate,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TemplatePreview(template.layout)
            Text(template.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy layout JSON") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete template") }
        }
    }
}

/** A small bitmap thumbnail of the layout (raw `%vars` render as literal text — they fill in at run time). */
@Composable
private fun TemplatePreview(layout: String) {
    val density = LocalDensity.current.density
    val node = remember(layout) { WidgetLayoutCodec.decode(layout) }
    Box(
        Modifier.size(84.dp, 52.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF202020)),
    ) {
        if (node != null) {
            val wPx = with(LocalDensity.current) { 84.dp.toPx() }.toInt()
            val hPx = with(LocalDensity.current) { 52.dp.toPx() }.toInt()
            val bitmap = remember(node, wPx, hPx) {
                runCatching { WidgetRenderer(density) { ThemeStore.typeface(it) }.render(node, wPx, hPx) }.getOrNull()
            }
            if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NameDialog(existing: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val clash = trimmed in existing
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("New template") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Template name") },
                    singleLine = true,
                    isError = clash,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (clash) {
                    Text(
                        "A template with that name already exists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && !clash, onClick = { onConfirm(trimmed) }) { Text("Design") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
