package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    val actionGroups = remember {
        ActionMetadataRegistry.all()
            .groupBy { it.category }
            .toSortedMap()
            .map { (category, actions) -> category to actions.sortedBy { it.name } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_action)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
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
                                containerColor = if (capability.canAdd) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                                },
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.metadata.name) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
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
                        singleLine = true,
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
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && capability.canAdd,
                onClick = {
                    onSave(
                        ActionSpec(
                            id = state.existing?.id ?: 0,
                            type = state.metadata.id,
                            label = label.trim().ifBlank { state.metadata.name },
                            args = values.filterValues { it.isNotBlank() },
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
