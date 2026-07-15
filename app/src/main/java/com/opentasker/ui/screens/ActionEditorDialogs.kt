package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.actions.FieldType
import com.opentasker.core.actions.NotificationTaskBindings
import com.opentasker.core.actions.NotificationTaskCandidate
import com.opentasker.core.actions.NotificationTaskReference
import com.opentasker.core.actions.NotificationTaskResolution
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.DesignSystem

private data class LocalizedActionMetadata(
    val metadata: ActionMetadata,
    val name: String,
    val description: String,
    val category: String,
)

@Composable
internal fun ActionPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    val localizedActions = mutableListOf<LocalizedActionMetadata>()
    for (metadata in ActionMetadataRegistry.all()) {
        if (!metadata.pickerVisible) continue
        localizedActions += LocalizedActionMetadata(
            metadata = metadata,
            name = stringResource(metadata.nameRes),
            description = stringResource(metadata.descriptionRes),
            category = stringResource(metadata.categoryRes),
        )
    }
    val actionGroups = localizedActions
        .groupBy { it.category }
        .toSortedMap()
        .map { (category, actions) -> category to actions.sortedBy { it.name } }
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
                    items(actions, key = { it.metadata.id }) { localized ->
                        val metadata = localized.metadata
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
                                    Text(localized.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    if (capability.level != CapabilityLevel.Supported) {
                                        StatusPill(
                                            if (capability.level == CapabilityLevel.Unsupported) stringResource(R.string.label_unsupported) else stringResource(R.string.label_setup),
                                            if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(localized.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (capability.level != CapabilityLevel.Supported) {
                                    Text(stringResource(capability.reasonRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
    tasks: List<Task> = emptyList(),
): String = args[key] ?: notificationTaskEditorValue(actionId, key, args, tasks) ?: when (actionId to key) {
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

private fun notificationTaskEditorValue(
    actionId: String,
    key: String,
    args: Map<String, String>,
    tasks: List<Task>,
): String? {
    if (actionId != "notify.show") return null
    val buttonIndex = (1..NotificationTaskBindings.BUTTON_COUNT)
        .firstOrNull { NotificationTaskBindings.taskIdKey(it) == key }
        ?: return null
    val reference = NotificationTaskBindings.parse(args, buttonIndex) ?: return ""
    return when (val resolution = NotificationTaskBindings.resolve(reference, tasks.toNotificationCandidates())) {
        is NotificationTaskResolution.Bound -> resolution.task.id.toString()
        else -> ""
    }
}

internal fun unresolvedNotificationTaskBindings(
    actionId: String,
    args: Map<String, String>,
    tasks: List<Task>,
): Map<String, NotificationTaskResolution> {
    if (actionId != "notify.show") return emptyMap()
    val candidates = tasks.toNotificationCandidates()
    return (1..NotificationTaskBindings.BUTTON_COUNT).mapNotNull { buttonIndex ->
        val reference = NotificationTaskBindings.parse(args, buttonIndex) ?: return@mapNotNull null
        val resolution = NotificationTaskBindings.resolve(reference, candidates)
        if (resolution is NotificationTaskResolution.Bound) {
            null
        } else {
            NotificationTaskBindings.taskIdKey(buttonIndex) to resolution
        }
    }.toMap()
}

private fun List<Task>.toNotificationCandidates(): List<NotificationTaskCandidate> =
    map { NotificationTaskCandidate(it.id, it.name) }

@Composable
internal fun ActionConfigDialog(
    state: ActionEditState,
    tasks: List<Task> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (ActionSpec) -> Unit,
) {
    val metadataName = stringResource(state.metadata.nameRes)
    val metadataDescription = stringResource(state.metadata.descriptionRes)
    var label by rememberSaveable(state.existing?.id, state.metadata.id, metadataName) {
        mutableStateOf(state.existing?.label ?: metadataName)
    }
    var values by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(
            state.metadata.fields.associate { field ->
                field.key to existingActionArgValue(
                    actionId = state.metadata.id,
                    key = field.key,
                    args = state.existing?.args.orEmpty(),
                    tasks = tasks,
                )
            }
        )
    }
    val initialTaskBindingIssues = remember(state.existing?.args, state.metadata.id, tasks) {
        unresolvedNotificationTaskBindings(
            actionId = state.metadata.id,
            args = state.existing?.args.orEmpty(),
            tasks = tasks,
        )
    }
    var addressedTaskBindingKeys by rememberSaveable(state.existing?.id, state.metadata.id) {
        mutableStateOf(emptyList<String>())
    }
    val taskBindingIssues = initialTaskBindingIssues.filterKeys { it !in addressedTaskBindingKeys }
    val capability = remember(state.metadata.id) { ActionCapabilityRegistry.get(state.metadata.id) }
    val missingRequired = state.metadata.fields.any { it.required && values[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(metadataName) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(metadataDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                stringResource(capability.reasonRes),
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
                        onChange = { newValue ->
                            values = values + (field.key to newValue)
                            if (field.fieldType == FieldType.TASK && field.key !in addressedTaskBindingKeys) {
                                addressedTaskBindingKeys = addressedTaskBindingKeys + field.key
                            }
                        },
                        tasks = tasks,
                    )
                    taskBindingIssues[field.key]?.let { issue ->
                        Text(
                            notificationTaskBindingIssueText(issue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && taskBindingIssues.isEmpty() && capability.canAdd,
                onClick = {
                    onSave(
                        ActionSpec(
                            id = state.existing?.id ?: 0,
                            type = state.metadata.id,
                            label = label.trim().takeUnless { it.isBlank() || it == metadataName },
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
internal fun ActionFieldInput(
    field: ActionField,
    value: String,
    onChange: (String) -> Unit,
    tasks: List<Task> = emptyList(),
) {
    val label = stringResource(field.labelRes) + if (field.required) " *" else ""
    val hint = field.hintRes?.let { stringResource(it) }
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
                    hint?.let {
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
            placeholder = hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.NUMBER -> OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '-' || ch == '.' }) },
            label = { Text(label) },
            placeholder = hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        FieldType.TASK -> TaskActionFieldInput(
            label = label,
            hint = hint,
            value = value,
            tasks = tasks,
            onChange = onChange,
        )

        FieldType.DROPDOWN,
        FieldType.TEXT -> OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            placeholder = hint?.let { { Text(it) } },
            supportingText = if (field.required) {{ Text(stringResource(R.string.label_required)) }} else null,
            singleLine = field.fieldType != FieldType.MULTILINE,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TaskActionFieldInput(
    label: String,
    hint: String?,
    value: String,
    tasks: List<Task>,
    onChange: (String) -> Unit,
) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val selectedId = value.toLongOrNull()
    val selectedLabel = when {
        value.isBlank() -> stringResource(R.string.label_none)
        selectedId == null -> stringResource(R.string.action_task_binding_invalid_value, value)
        else -> tasks.firstOrNull { it.id == selectedId }?.name
            ?: stringResource(R.string.action_task_binding_missing_id, selectedId)
    }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.label_none)) },
                onClick = {
                    onChange("")
                    expanded = false
                },
            )
            tasks.sortedBy { it.name.lowercase() }.forEach { task ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_task_picker_option, task.name, task.id)) },
                    onClick = {
                        onChange(task.id.toString())
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun notificationTaskBindingIssueText(issue: NotificationTaskResolution): String = when (issue) {
    is NotificationTaskResolution.Bound -> ""
    is NotificationTaskResolution.Missing -> when (val reference = issue.reference) {
        is NotificationTaskReference.Id -> stringResource(R.string.action_task_binding_missing_id, reference.taskId)
        is NotificationTaskReference.LegacyName -> stringResource(R.string.action_task_binding_missing_name, reference.taskName)
        is NotificationTaskReference.Invalid -> stringResource(R.string.action_task_binding_invalid_value, reference.rawValue)
    }
    is NotificationTaskResolution.Ambiguous -> stringResource(
        R.string.action_task_binding_ambiguous_name,
        issue.taskName,
    )
    is NotificationTaskResolution.Invalid -> stringResource(R.string.action_task_binding_invalid_value, issue.rawValue)
}
