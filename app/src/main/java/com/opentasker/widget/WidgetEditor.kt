package com.opentasker.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.widget.Background
import com.opentasker.core.widget.Offset
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.core.widget.WidgetNode
import com.opentasker.core.widget.WidgetRenderer
import com.opentasker.ui.components.RgbaColorPickerDialog
import com.opentasker.ui.theme.ThemeStore

// ---- immutable tree edits, addressed by a path of child indices --------------------------------

private fun WidgetNode.nodeAt(path: List<Int>): WidgetNode = path.fold(this) { n, i -> n.children[i] }

private fun WidgetNode.updateAt(path: List<Int>, f: (WidgetNode) -> WidgetNode): WidgetNode {
    if (path.isEmpty()) return f(this)
    val i = path.first()
    return copy(children = children.toMutableList().also { it[i] = it[i].updateAt(path.drop(1), f) })
}

private fun WidgetNode.removeAt(path: List<Int>): WidgetNode {
    if (path.size == 1) return copy(children = children.toMutableList().also { it.removeAt(path[0]) })
    val i = path.first()
    return copy(children = children.toMutableList().also { it[i] = it[i].removeAt(path.drop(1)) })
}

private fun WidgetNode.addChild(path: List<Int>, child: WidgetNode): WidgetNode =
    updateAt(path) { it.copy(children = it.children + child) }

private fun WidgetNode.moveChild(path: List<Int>, delta: Int): WidgetNode {
    if (path.isEmpty()) return this
    return updateAt(path.dropLast(1)) { parent ->
        val ch = parent.children.toMutableList()
        val from = path.last(); val to = (from + delta).coerceIn(0, ch.lastIndex)
        if (to != from) ch.add(to, ch.removeAt(from))
        parent.copy(children = ch)
    }
}

private data class Flat(val node: WidgetNode, val path: List<Int>, val depth: Int)

private fun WidgetNode.flatten(path: List<Int> = emptyList(), depth: Int = 0, acc: MutableList<Flat> = mutableListOf()): List<Flat> {
    acc.add(Flat(this, path, depth))
    children.forEachIndexed { i, c -> c.flatten(path + i, depth + 1, acc) }
    return acc
}

private fun WidgetNode.isContainer() = type in setOf("column", "row", "box", "free")

private val NODE_TYPES = listOf("column", "row", "box", "free", "text", "image", "shape", "spacer")
private val ALIGNS = listOf("start", "center", "end", "topStart", "top", "topEnd", "bottomStart", "bottom", "bottomEnd")
private val ARRANGES = listOf("start", "center", "end")
private val SCALES = listOf("fit", "crop", "fill")
private val SHAPES = listOf("rect", "oval", "line")

/**
 * Visual editor for a [WidgetNode] tree: a live bitmap preview, the node tree (add child / delete /
 * move), and a property panel for the selected node. No hand-written JSON. Returns the layout JSON.
 */
@Composable
fun WidgetEditor(initialJson: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var root by remember { mutableStateOf(WidgetLayoutCodec.decode(initialJson) ?: WidgetLayoutCodec.PLACEHOLDER) }
    var selected by remember { mutableStateOf<List<Int>>(emptyList()) }
    val fonts = remember { ThemeStore.availableFonts().map { it.fileName }.filter { it.isNotEmpty() } }
    val clipboard = LocalClipboardManager.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                Text("Widget layout", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { clipboard.setText(AnnotatedString(WidgetLayoutCodec.encode(root))) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy JSON")
                }
                OutlinedButton(onClick = { onDone(WidgetLayoutCodec.encode(root)) }) { Text("Save") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            WidgetPreview(root)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                root.flatten().forEach { flat ->
                    TreeRow(
                        flat = flat,
                        selected = flat.path == selected,
                        onSelect = { selected = flat.path },
                        onAddChild = { root = root.addChild(flat.path, WidgetNode(type = "text", text = "Text", size = 20, color = "#FFFF00")) },
                        onDelete = { root = root.removeAt(flat.path); selected = emptyList() },
                        onMove = { delta -> root = root.moveChild(flat.path, delta) },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val node = runCatching { root.nodeAt(selected) }.getOrNull()
                if (node != null) {
                    PropertyPanel(node, selected.isNotEmpty(), fonts) { updated -> root = root.updateAt(selected) { updated } }
                }
            }
        }
    }
}

@Composable
private fun WidgetPreview(root: WidgetNode) {
    val density = LocalDensity.current.density
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(170.dp).padding(12.dp)
            .background(Color(0xFF202020), RoundedCornerShape(8.dp)),
    ) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }.toInt()
        val bitmap = remember(root, wPx, hPx) {
            runCatching { WidgetRenderer(density) { ThemeStore.typeface(it) }.render(root, wPx, hPx) }.getOrNull()
        }
        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Preview", modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TreeRow(flat: Flat, selected: Boolean, onSelect: () -> Unit, onAddChild: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = (flat.depth * 16).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            nodeLabel(flat.node),
            modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.ArrowUpward, "Up", modifier = Modifier.size(18.dp)) }
        IconButton(onClick = { onMove(1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.ArrowDownward, "Down", modifier = Modifier.size(18.dp)) }
        if (flat.node.isContainer()) {
            IconButton(onClick = onAddChild, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Add, "Add child", modifier = Modifier.size(18.dp)) }
        }
        if (flat.path.isNotEmpty()) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, "Delete", modifier = Modifier.size(18.dp)) }
        }
    }
}

private fun nodeLabel(node: WidgetNode): String = when (node.type) {
    "text" -> "text: ${node.text?.take(16).orEmpty()}"
    "image" -> "image"
    "shape" -> "shape: ${node.shape ?: "rect"}"
    "spacer" -> "spacer"
    else -> node.type
}

@Composable
private fun PropertyPanel(node: WidgetNode, isChild: Boolean, fonts: List<String>, onChange: (WidgetNode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField("Type", node.type, NODE_TYPES) { onChange(node.copy(type = it)) }
        when (node.type) {
            "text" -> {
                TextField2("Text", node.text.orEmpty()) { onChange(node.copy(text = it)) }
                DropdownField("Font", node.font ?: "(inherit/default)", listOf("(inherit/default)") + fonts) {
                    onChange(node.copy(font = it.takeIf { f -> f != "(inherit/default)" }))
                }
                NumberField("Size (dp)", node.size) { onChange(node.copy(size = it)) }
                ColorField("Color", node.color) { onChange(node.copy(color = it)) }
                BoolField("Bold", node.bold == true) { onChange(node.copy(bold = it)) }
                DropdownField("Text align", node.align ?: "start", listOf("start", "center", "end")) { onChange(node.copy(align = it)) }
            }
            "image" -> {
                TextField2("Source path", node.src.orEmpty()) { onChange(node.copy(src = it)) }
                DropdownField("Scale", node.scale ?: "fit", SCALES) { onChange(node.copy(scale = it)) }
                ColorField("Tint", node.tint) { onChange(node.copy(tint = it)) }
            }
            "shape" -> {
                DropdownField("Shape", node.shape ?: "rect", SHAPES) { onChange(node.copy(shape = it)) }
                ColorField("Fill", node.color) { onChange(node.copy(color = it)) }
                ColorField("Stroke", node.stroke) { onChange(node.copy(stroke = it)) }
                NumberField("Stroke width (dp)", node.strokeWidth) { onChange(node.copy(strokeWidth = it)) }
                NumberField("Corner (dp)", node.corner) { onChange(node.copy(corner = it)) }
            }
            "column", "row" -> {
                DropdownField("Arrange (main)", node.arrange ?: "start", ARRANGES) { onChange(node.copy(arrange = it)) }
                DropdownField("Align (cross)", node.align ?: "start", listOf("start", "center", "end")) { onChange(node.copy(align = it)) }
                NumberField("Gap (dp)", node.gap) { onChange(node.copy(gap = it)) }
            }
        }
        // Placement of THIS node inside its parent (when it's a child).
        if (isChild) {
            DropdownField("Anchor (in box)", node.align ?: "center", ALIGNS) { onChange(node.copy(align = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { NumberField("Offset X (dp)", node.offset?.x) { onChange(node.copy(offset = Offset(it ?: 0, node.offset?.y ?: 0))) } }
                Box(Modifier.weight(1f)) { NumberField("Offset Y (dp)", node.offset?.y) { onChange(node.copy(offset = Offset(node.offset?.x ?: 0, it ?: 0))) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { NumberField("Free X (dp)", node.x) { onChange(node.copy(x = it)) } }
                Box(Modifier.weight(1f)) { NumberField("Free Y (dp)", node.y) { onChange(node.copy(y = it)) } }
            }
        }
        // Common box: size + background.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { TextField2("Width", node.width ?: "wrap") { onChange(node.copy(width = it)) } }
            Box(Modifier.weight(1f)) { TextField2("Height", node.height ?: "wrap") { onChange(node.copy(height = it)) } }
        }
        ColorField("Background", node.background?.color) {
            onChange(node.copy(background = (node.background ?: Background()).copy(color = it)))
        }
        NumberField("Background corner (dp)", node.background?.corner) {
            onChange(node.copy(background = (node.background ?: Background()).copy(corner = it ?: 0)))
        }
    }
}

// ---- small field helpers -----------------------------------------------------------------------

@Composable
private fun TextField2(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NumberField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value?.toString().orEmpty(),
            onValueChange = { s -> onChange(s.filter { it.isDigit() || it == '-' }.toIntOrNull()) },
            label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange((value ?: 0) - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "Decrease $label") }
        IconButton(onClick = { onChange((value ?: 0) + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Increase $label") }
    }
}

@Composable
private fun BoolField(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorField(label: String, value: String?, onChange: (String?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val parsed = remember(value) { runCatching { if (value.isNullOrBlank()) null else android.graphics.Color.parseColor(value) }.getOrNull() }
    Row(
        Modifier.fillMaxWidth().clickable { show = true }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (parsed == null) "Default" else value!!.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(parsed?.let { Color(it) } ?: Color.Transparent)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
        )
    }
    if (show) {
        RgbaColorPickerDialog(
            initial = value.orEmpty(),
            onConfirm = { onChange(it); show = false },
            onClear = { onChange(null); show = false },
            onDismiss = { show = false },
        )
    }
}

@Composable
private fun DropdownField(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            enabled = false,
        )
        Box(Modifier.matchParentSize().clickable { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}
