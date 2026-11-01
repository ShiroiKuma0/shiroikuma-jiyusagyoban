package com.opentasker.core.dialog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentasker.ui.components.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * One field of a `dialog.form`, already resolved by the action: options read, current value filled in.
 *
 * Types: `section` (a heading), `note` (a line of explanation), `text`, `choice`, `color` and `badge`
 * — the last a live preview of a badge drawn from four other fields ([refs]: text, fill, ink, border),
 * so a colour is chosen while looking at what it will produce.
 */
data class FormField(
    val type: String,
    val key: String,
    val label: String,
    val info: String,
    val hint: String,
    val options: List<String>,
    val values: List<String>,
    val initial: String,
    val maxLength: Int,
    val refs: Map<String, String>,
) {
    companion object {
        fun listFromJson(json: String): List<FormField> {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }

        fun fromJson(o: JSONObject): FormField = FormField(
            type = o.optString("type", "text"),
            key = o.optString("var"),
            label = o.optString("label"),
            info = o.optString("info"),
            hint = o.optString("hint"),
            options = o.optJSONArray("options").strings(),
            values = o.optJSONArray("values").strings(),
            initial = o.optString("value"),
            maxLength = o.optInt("max", 0),
            refs = listOf("text", "fill", "ink", "border").associateWith { o.optString(it) },
        )

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }
    }
}

/** The colours offered as swatches — named, because a swatch alone says nothing to a colour-blind eye. */
internal val FORM_PALETTE = listOf(
    "黄" to "#FFFF00", "青" to "#1E5AFF", "赤" to "#FF0000", "黒" to "#000000", "白" to "#FFFFFF",
    "緑" to "#00D084", "橙" to "#FF8C00", "紫" to "#8B3FD9", "灰" to "#808080", "暗赤" to "#A00000",
)

internal fun parseFormColor(hex: String): Color? =
    runCatching { if (hex.isBlank()) null else Color(android.graphics.Color.parseColor(hex.trim())) }.getOrNull()

/**
 * Many labelled settings in ONE dialog, instead of a question per dialog (白い熊, 2026-11-01).
 *
 * Every field has a label above it, and an ⓘ (or a long press on the label) opens what it is for.
 * Choices with few short options are pills; longer lists drop down. Colours are named swatches plus
 * a typed `#RRGGBB`, and a `badge` field redraws live from them. [skipLabel], when given, adds the
 * middle answer — "leave this one as it is" — for a run of forms where only some need changing.
 */
@Composable
fun FormDialog(
    title: String,
    intro: String,
    fields: List<FormField>,
    okLabel: String,
    cancelLabel: String,
    skipLabel: String?,
    onConfirm: (Map<String, String>) -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            fields.filter { it.key.isNotEmpty() && it.type !in setOf("section", "note", "badge") }
                .forEach { put(it.key, it.initial) }
        }
    }
    var info by remember { mutableStateOf<FormField?>(null) }
    val shape = RoundedCornerShape(28.dp)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // A bare Column paints the background but, unlike a Surface, sets no content colour, so every
        // Text without its own colour — the field labels, the drop-down's value, the swatch names —
        // fell back to Compose's default black and vanished on the dark surface (白い熊, 2026-11-01).
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, shape),
            ) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (intro.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (f in fields) {
                        when (f.type) {
                            "section" -> FormSection(f) { info = f }
                            "note" -> Text(f.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            "badge" -> FormBlock(f, onInfo = { info = f }) { BadgePreview(f, values) }
                            "choice" -> FormBlock(f, onInfo = { info = f }) {
                                ChoiceField(f, values[f.key].orEmpty()) { values[f.key] = it }
                            }
                            "color" -> FormBlock(f, onInfo = { info = f }) {
                                ColorField(values[f.key].orEmpty()) { values[f.key] = it }
                            }
                            else -> FormBlock(f, onInfo = { info = f }) {
                                OutlinedTextField(
                                    value = values[f.key].orEmpty(),
                                    onValueChange = { v -> values[f.key] = if (f.maxLength > 0) v.take(f.maxLength) else v },
                                    singleLine = true,
                                    placeholder = if (f.hint.isNotBlank()) ({ Text(f.hint) }) else null,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onCancel) { Text(cancelLabel) }
                    Spacer(Modifier.weight(1f))
                    if (skipLabel != null) {
                        OutlinedButton(onClick = onSkip) { Text(skipLabel) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = { onConfirm(values.toMap()) }) { Text(okLabel, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
    info?.let { f ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { Button(onClick = { info = null }) { Text("OK") } },
            title = { Text(f.label) },
            text = { Text(f.info, style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

@Composable
private fun FormSection(f: FormField, onInfo: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                f.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (f.info.isNotBlank()) InfoMark(onInfo)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
    }
}

/** A field's label — long-pressable for its explanation — with the ⓘ that does the same, then the control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormBlock(f: FormField, onInfo: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (f.label.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.label,
                    Modifier
                        .weight(1f)
                        .then(if (f.info.isNotBlank()) Modifier.combinedClickable(onClick = {}, onLongClick = onInfo) else Modifier),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (f.info.isNotBlank()) InfoMark(onInfo)
            }
        }
        content()
    }
}

@Composable
private fun InfoMark(onClick: () -> Unit) {
    Icon(
        Icons.Outlined.Info,
        contentDescription = "info",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(6.dp).size(22.dp),
    )
}

/** Pills for a few short options; a drop-down for anything longer, such as a list of conversations. */
@Composable
private fun ChoiceField(f: FormField, value: String, onPick: (String) -> Unit) {
    val values = f.values.takeIf { it.size == f.options.size } ?: f.options
    val selected = values.indexOf(value)
    val pills = f.options.size <= 10 && f.options.all { it.length <= 8 }
    if (pills) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            f.options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .widthIn(min = 44.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(100.dp))
                        .clickable { onPick(values[i]) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                f.options.getOrNull(selected) ?: f.hint.ifBlank { "—" },
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        if (open) ChoicePicker(f.label, f.options, selected, onDismiss = { open = false }) { i -> onPick(values[i]); open = false }
    }
}

/**
 * The long list behind a drop-down, as a bordered dialog of pills in the form's own style — the chosen
 * one filled — capped in height and scrolling. A bare DropdownMenu ran the full screen height with
 * wide gaps and no border (白い熊, 2026-11-01).
 */
@Composable
private fun ChoicePicker(title: String, options: List<String>, selected: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEachIndexed { i, label ->
                        val on = i == selected
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(22.dp))
                                .clickable { onPick(i) }
                                .padding(horizontal = 18.dp, vertical = 11.dp),
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss) { Text("戻る / Back") }
                }
            }
        }
    }
}

/** Named swatches, the chosen one ringed and ticked, plus a typed `#RRGGBB` for anything else. */
@Composable
private fun ColorField(value: String, onPick: (String) -> Unit) {
    var typed by remember { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((name, hex) in FORM_PALETTE) {
                val c = parseFormColor(hex) ?: continue
                val on = hex.equals(value.trim(), ignoreCase = true)
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(hex); typed = hex }
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                if (on) 3.dp else 1.dp,
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("✓", fontWeight = FontWeight.Bold, color = if (c.luminance() > 0.5f) Color.Black else Color.White)
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(parseFormColor(value) ?: Color.Transparent)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { v ->
                    typed = v.take(9)
                    if (parseFormColor(typed) != null && typed.startsWith("#")) onPick(typed.uppercase())
                },
                singleLine = true,
                placeholder = { Text("#RRGGBB") },
                isError = parseFormColor(typed) == null,
                modifier = Modifier.width(150.dp),
            )
            Text(
                FORM_PALETTE.firstOrNull { it.second.equals(value.trim(), ignoreCase = true) }?.first ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The badge as it will look — large, then near its real size beside it — redrawn from the live values. */
@Composable
private fun BadgePreview(f: FormField, values: Map<String, String>) {
    val text = values[f.refs["text"].orEmpty()].orEmpty()
    val fill = parseFormColor(values[f.refs["fill"].orEmpty()].orEmpty()) ?: Color.Gray
    val ink = parseFormColor(values[f.refs["ink"].orEmpty()].orEmpty()) ?: Color.Black
    val border = parseFormColor(values[f.refs["border"].orEmpty()].orEmpty()) ?: fill
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(text, fill, ink, border, height = 48.dp, stroke = 3.dp, fontSize = 24)
        Badge(text, fill, ink, border, height = 22.dp, stroke = 1.5.dp, fontSize = 12)
    }
}

@Composable
private fun Badge(text: String, fill: Color, ink: Color, border: Color, height: Dp, stroke: Dp, fontSize: Int) {
    Box(
        Modifier
            .height(height)
            .widthIn(min = height)
            .clip(RoundedCornerShape(height / 4))
            .background(fill)
            .border(stroke, border, RoundedCornerShape(height / 4))
            .padding(horizontal = height / 5),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.ifEmpty { " " }, color = ink, fontWeight = FontWeight.Bold, fontSize = fontSize.sp, maxLines = 1)
    }
}
