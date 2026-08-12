package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.engine.SUB_TASK_ACTION_ID
import com.opentasker.core.engine.SUB_TASK_PARAM_PREFIX
import com.opentasker.core.model.ActionSpec
import com.opentasker.ui.components.RgbaColorPickerDialog
import com.opentasker.ui.components.ThemedDropdownMenu
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.widget.WidgetEditor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog

/**
 * Upstream's picker search, on the fork's metadata.
 *
 * Upstream filters a `LocalizedActionMetadata` triple it builds by resolving three `@StringRes` ids
 * per action. The fork keeps name/description/category as inline strings on [ActionMetadata] itself,
 * so there is nothing to localize first and the filter runs straight over the metadata. Matching
 * covers the display name, the description and the stable action id, so `file.read` finds the action
 * as readily as "Read file" does.
 */
internal fun filterActionPickerItems(
    items: List<ActionMetadata>,
    query: String,
): List<ActionMetadata> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return items
    return items.filter { item ->
        item.name.contains(normalizedQuery, ignoreCase = true) ||
            item.description.contains(normalizedQuery, ignoreCase = true) ||
            item.id.contains(normalizedQuery, ignoreCase = true)
    }
}

@Composable
internal fun ActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // The fork's metadata is inline strings, so the catalogue needs no locale rekeying — build it once.
    val allActions = remember { ActionMetadataRegistry.all().toList() }
    val filteredActions = remember(allActions, query) { filterActionPickerItems(allActions, query) }
    val actionGroups = remember(filteredActions) {
        filteredActions
            .groupBy { it.category }
            .toSortedMap()
            .map { (category, actions) -> category to actions.sortedBy { it.name } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_action)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.action_picker_search_label)) },
                    placeholder = { Text(stringResource(R.string.action_picker_search_hint)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            TextButton(onClick = { query = "" }) {
                                Text(stringResource(R.string.action_picker_clear_search))
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                )
                if (actionGroups.isEmpty()) {
                    Text(
                        stringResource(R.string.action_picker_no_results),
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                    ) {
                        actionGroups.forEach { (category, actions) ->
                            item(key = "category-$category") {
                                Text(
                                    category,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(actions, key = { it.id }) { metadata ->
                                val capability = ActionCapabilityRegistry.get(metadata.id)
                                Card(
                                    onClick = { onSelect(metadata) },
                                    enabled = capability.canAdd,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                    // In every static scheme surfaceVariant IS the dialog's own
                                    // container colour, so compositing it over itself at any alpha
                                    // yields the same colour: the enabled/disabled distinction the
                                    // two alphas were meant to draw was mathematically zero.
                                    border = BorderStroke(
                                        width = 1.dp,
                                        color = if (capability.canAdd) {
                                            MaterialTheme.colorScheme.outline
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                                        ) {
                                            Text(metadata.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                            if (capability.level != CapabilityLevel.Supported) {
                                                StatusPill(
                                                    if (capability.level == CapabilityLevel.Unsupported) stringResource(R.string.label_unsupported) else stringResource(R.string.label_setup),
                                                    if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                        }
                                        Text(metadata.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (capability.level != CapabilityLevel.Supported) {
                                            Text(capability.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

internal fun existingActionArgValue(
    actionId: String,
    key: String,
    args: Map<String, String>,
): String = args[key] ?: when (actionId to key) {
    "brightness.set" to "brightness" -> args["level"]
    "screenshot.take" to "path" -> args["filename"]
    "file.read" to "var" -> args["variable"]
    "file.write" to "text" -> args["content"]
    "file.append" to "text" -> args["content"]
    "file.list" to "var" -> args["variable"]
    "http.get" to "var" -> args["variable"]
    "http.post" to "data" -> args["body"]
    "http.post" to "var" -> args["variable"]
    else -> null
}.orEmpty()

@Composable
internal fun ActionConfigDialog(
    state: ActionEditState,
    onDismiss: () -> Unit,
    onSave: (ActionSpec) -> Unit,
) {
    var label by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(state.existing?.label ?: state.metadata.name)
    }
    var values by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(
            state.metadata.fields.associate { field ->
                field.key to existingActionArgValue(
                    actionId = state.metadata.id,
                    key = field.key,
                    args = state.existing?.args.orEmpty(),
                )
            }
        )
    }
    val capability = remember(state.metadata.id) { ActionCapabilityRegistry.get(state.metadata.id) }
    val missingRequired = state.metadata.fields.any { it.required && values[it.key].isNullOrBlank() }

    // Run Task carries dynamic named parameters as `param:<name>` args that no metadata field covers.
    // Edit them here (seeded from the existing action) and merge them back on Save — without this the
    // save would rebuild args from the visible fields alone and silently drop every parameter.
    val isRunTask = state.metadata.id == SUB_TASK_ACTION_ID
    var params by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(
            state.existing?.args.orEmpty()
                .filterKeys { it.startsWith(SUB_TASK_PARAM_PREFIX) }
                .map { it.key.removePrefix(SUB_TASK_PARAM_PREFIX) to it.value },
        )
    }

    AlertDialog(
        // Yellow edge + more height: this is the full editor, so give it room without being a full page.
        modifier = Modifier
            .fillMaxWidth(0.96f)
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        // Back and Cancel still dismiss in one tap; only a stray tap on the scrim is refused.
        // These forms carry many fields, and discarding them has no undo because nothing was
        // saved yet.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(state.metadata.name) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(state.metadata.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (capability.level != CapabilityLevel.Supported) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            color = if (capability.level == CapabilityLevel.Unsupported) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            },
                            shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        ) {
                            Text(
                                capability.reason,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text(stringResource(R.string.action_label_field)) },
                        supportingText = { Text(stringResource(R.string.action_label_hint)) },
                        // Labels are frequently multi-line (bilingual notes) — a single line hid the rest and
                        // made them uneditable. Grow with the text; there's plenty of dialog height.
                        minLines = 3,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(state.metadata.fields, key = { it.key }) { field ->
                    ActionFieldInput(
                        field = field,
                        value = values[field.key].orEmpty(),
                        onChange = { newValue -> values = values + (field.key to newValue) },
                    )
                }
                if (isRunTask) {
                    item {
                        RunTaskParametersSection(params = params, onChange = { params = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && capability.canAdd,
                onClick = {
                    val paramArgs = if (isRunTask) {
                        params.filter { it.first.isNotBlank() }
                            .associate { "$SUB_TASK_PARAM_PREFIX${it.first.trim()}" to it.second }
                    } else {
                        emptyMap()
                    }
                    onSave(
                        ActionSpec(
                            id = state.existing?.id ?: 0,
                            type = state.metadata.id,
                            label = label.trim().ifBlank { state.metadata.name },
                            args = values.filterValues { it.isNotBlank() } + paramArgs,
                            continueOnError = state.existing?.continueOnError ?: false,
                            condition = state.existing?.condition,
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Editor for a Run Task action's named parameters (the `param:<name>` args the sub-task reads as
 * {{ param.name }}). A row per parameter — name + value + delete — plus an add button. The parent
 * merges these back into the action's args on Save.
 */
@Composable
private fun RunTaskParametersSection(
    params: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
        Text(
            "Parameters",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Named values passed to the task; it reads each as {{ param.name }} (or %@name).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        params.forEachIndexed { index, (name, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { newName ->
                        onChange(params.toMutableList().also { it[index] = newName to it[index].second })
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { newValue ->
                        onChange(params.toMutableList().also { it[index] = it[index].first to newValue })
                    },
                    label = { Text("Value") },
                    modifier = Modifier.weight(1.4f),
                )
                IconButton(onClick = { onChange(params.toMutableList().also { it.removeAt(index) }) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove parameter")
                }
            }
        }
        OutlinedButton(onClick = { onChange(params + ("" to "")) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Add parameter")
        }
    }
}

@Composable
internal fun ActionFieldInput(field: ActionField, value: String, onChange: (String) -> Unit) {
    val label = field.label + if (field.required) " *" else ""
    when (field.fieldType) {
        FieldType.CHECKBOX -> {
            val checked = value.toBoolean()
            val stateDescriptionLabel = if (checked) stringResource(R.string.label_on) else stringResource(R.string.label_off)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        role = Role.Switch,
                        onValueChange = { onChange(it.toString()) },
                    )
                    .semantics {
                        stateDescription = stateDescriptionLabel
                    },
            ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    field.hint?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        }

        FieldType.MULTILINE -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '-' || ch == '.' }) },
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // #AARRGGBB via the shared RGBA slider picker; a tappable swatch shows the current value.
        FieldType.COLOR -> {
            var showPicker by remember { mutableStateOf(false) }
            val parsed = remember(value) {
                runCatching { if (value.isBlank()) null else android.graphics.Color.parseColor(value) }.getOrNull()
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (parsed == null) "Default" else value.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (parsed == null) Color.Transparent else Color(parsed))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                )
            }
            if (showPicker) {
                RgbaColorPickerDialog(
                    initial = value,
                    onConfirm = { onChange(it); showPicker = false },
                    onClear = { onChange(""); showPicker = false },
                    onDismiss = { showPicker = false },
                )
            }
        }

        // Visual widget-layout editor (full-screen), with a raw-JSON advanced fallback below it.
        FieldType.WIDGET_LAYOUT -> {
            var editing by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (value.isBlank()) "Design layout (visual editor)" else "Edit layout visually")
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text("Layout JSON (advanced)") },
                    placeholder = field.hint?.let { { Text(it) } },
                    supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (editing) {
                Dialog(
                    onDismissRequest = { editing = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    WidgetEditor(
                        initialJson = value,
                        onDone = { onChange(it); editing = false },
                        onCancel = { editing = false },
                    )
                }
            }
        }

        // Editable combo: free-text (so it can be a %variable) PLUS a dropdown of the field's options.
        FieldType.DROPDOWN -> {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(label) },
                    placeholder = field.hint?.let { { Text(it) } },
                    supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
                    singleLine = true,
                    trailingIcon = if (field.options.isEmpty()) null else {
                        {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose a value")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                ThemedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    field.options.forEach { opt ->
                        DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); expanded = false })
                    }
                }
            }
        }

        // Editable text (a package name or %var) plus an installed-apps picker that fills it.
        FieldType.APP_PACKAGE -> {
            var showPicker by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                placeholder = field.hint?.let { { Text(it) } },
                supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(Icons.Filled.Apps, contentDescription = "Pick an app")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (showPicker) {
                AppPickerDialog(
                    onDismiss = { showPicker = false },
                    onPick = { pkg -> onChange(pkg); showPicker = false },
                )
            }
        }

        FieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = field.hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
            // Not single-line: short values stay compact but long ones (a font name, a path, a message)
            // wrap and grow so they stay fully editable rather than scrolling inside one hidden line.
            maxLines = 8,
            // Opt-in folder icon that fills the field from the system directory/file picker.
            trailingIcon = if (field.pathPicker) {
                { PathPickerTrailingIcon(onPath = onChange) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A folder icon whose menu opens the system directory or file picker (SAF) and reports the chosen
 * item as a plain filesystem path (e.g. /storage/emulated/0/Download/…) — no persistent URI grant is
 * taken because callers only need the path string. Sits in a field's trailing-icon slot.
 */
@Composable
private fun PathPickerTrailingIcon(onPath: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        documentUriToFsPath(uri, isTree = true)?.let { onPath(if (it.endsWith("/")) it else "$it/") }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        documentUriToFsPath(uri, isTree = false)?.let(onPath)
    }
    IconButton(onClick = { menu = true }) {
        Icon(Icons.Filled.Folder, contentDescription = "Pick a folder or file")
    }
    ThemedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text("Pick folder…") },
            onClick = { menu = false; treeLauncher.launch(null) },
        )
        DropdownMenuItem(
            text = { Text("Pick file…") },
            onClick = { menu = false; fileLauncher.launch(arrayOf("*/*")) },
        )
    }
}

/**
 * Convert a Storage Access Framework document/tree Uri from the external-storage provider to a real
 * filesystem path. Handles the primary volume (/storage/emulated/0) and named secondary volumes
 * (/storage/<id>). Returns null for other providers (a content Uri that has no filesystem path).
 */
private fun documentUriToFsPath(uri: Uri?, isTree: Boolean): String? {
    if (uri == null || uri.authority != "com.android.externalstorage.documents") return null
    val docId = runCatching {
        if (isTree) DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)
    }.getOrNull() ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val relative = parts.getOrNull(1).orEmpty()
    val base = if (volume.equals("primary", ignoreCase = true)) {
        Environment.getExternalStorageDirectory().absolutePath
    } else {
        "/storage/$volume"
    }
    return if (relative.isEmpty()) base else "$base/$relative"
}
