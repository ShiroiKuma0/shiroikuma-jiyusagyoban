package com.opentasker.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.core.widget.WidgetRenderer
import com.opentasker.widget.StyledWidgetProvider
import androidx.compose.ui.layout.ContentScale
import com.opentasker.ui.components.GroupMoveDialogs
import com.opentasker.ui.components.GroupOps
import com.opentasker.ui.components.groupedItems
import com.opentasker.ui.components.rememberGroupMoveHost
import com.opentasker.ui.components.ItemNoteSection
import com.opentasker.ui.components.SelectionBar
import com.opentasker.ui.components.SelectionCheck
import com.opentasker.ui.components.selectableItem
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
    onMessage: (String) -> Unit,
    createSignal: Int,
    expandedTemplates: SnapshotStateMap<String, Boolean>,
    selectedNames: Set<String>,
    onLongPressTemplate: (WidgetTemplate) -> Unit,
    onToggleSelectTemplate: (WidgetTemplate) -> Unit,
    onSelectAllTemplates: () -> Unit,
    onClearTemplateSelection: () -> Unit,
    onDeleteSelectedTemplates: () -> Unit,
    groupOps: GroupOps,
    contentPadding: PaddingValues,
) {
    // The template currently open in the full-screen editor (null = none).
    var editing by remember { mutableStateOf<WidgetTemplate?>(null) }
    var showNameDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val selectionActive = selectedNames.isNotEmpty()

    // "New template" lives in the tab's + menu (TabActionsFab); a tick of [createSignal] opens the
    // name dialog. Import/export run through the unified JSON-bundle flow, also from the + menu.
    LaunchedEffect(createSignal) {
        if (createSignal > 0) showNameDialog = true
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (selectionActive) {
            SelectionBar(
                count = selectedNames.size,
                total = templates.size,
                onSelectAll = onSelectAllTemplates,
                onClear = onClearTemplateSelection,
                onDelete = onDeleteSelectedTemplates,
            )
        }

        if (templates.isEmpty()) {
            Text(
                "No templates yet. Create one, design it in the editor, then point a Set Widget action at it by name.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        val moveHost = rememberGroupMoveHost()
        val templateRow: @Composable (WidgetTemplate) -> Unit = { template ->
            TemplateRow(
                template = template,
                selectionActive = selectionActive,
                selected = template.name in selectedNames,
                expanded = expandedTemplates[template.name] == true,
                onToggleExpanded = { expandedTemplates[template.name] = expandedTemplates[template.name] != true },
                onLongPress = { onLongPressTemplate(template) },
                onToggleSelect = { onToggleSelectTemplate(template) },
                onEdit = { editing = template },
                onCopy = { clipboard.setText(AnnotatedString(template.layout)) },
                onDelete = { onDelete(template.name) },
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (groupOps.groups.isEmpty()) {
                items(templates, key = { it.name }) { template -> templateRow(template) }
            } else {
                groupedItems(
                    templates, { it.name }, groupOps,
                    onMoveItem = { moveHost.movingItemKey = it },
                    onMoveGroup = { moveHost.movingGroup = it },
                ) { template -> templateRow(template) }
            }
        }
        GroupMoveDialogs(groupOps, moveHost)
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
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize().selectableItem(
            selectionActive = selectionActive,
            onLongPress = onLongPress,
            onToggleSelect = onToggleSelect,
            onTapNormal = onToggleExpanded,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                }
                Text(template.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse template" else "Expand template",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                ItemNoteSection("widgets", template.name)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TemplatePreview(template.layout)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit template") }
                    IconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy layout JSON") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete template") }
                }
            }
        }
    }
}

/** A small bitmap thumbnail of the layout. Renders at a canvas scaled to the template's biggest font
 *  (so big-screen clock fonts don't overflow the tiny box into a narrow strip), with `%vars` expanded
 *  against the live globals, then scales the bitmap down to fit. */
@Composable
private fun TemplatePreview(layout: String) {
    val node = remember(layout) {
        val expanded = runCatching { StyledWidgetProvider.expandGlobals(layout) }.getOrDefault(layout)
        WidgetLayoutCodec.decode(expanded)
    }
    Box(
        Modifier.size(84.dp, 52.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF202020)),
    ) {
        if (node != null) {
            val maxFont = remember(node) { maxFontSize(node).coerceIn(12, 240) }
            val wPx = (maxFont * 7).coerceIn(160, 1000)
            val hPx = wPx * 52 / 84
            val bitmap = remember(node, wPx, hPx) {
                // density 1f: dp values map straight to px in the over-sized canvas; the Image scales down.
                runCatching { WidgetRenderer(1f) { ThemeStore.typeface(it) }.render(node, wPx, hPx) }.getOrNull()
            }
            if (bitmap != null) Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Largest text `size` (dp) anywhere in the node tree, for scaling the preview canvas. */
private fun maxFontSize(node: com.opentasker.core.widget.WidgetNode): Int =
    maxOf(node.size ?: 0, node.children.maxOfOrNull { maxFontSize(it) } ?: 0)

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

