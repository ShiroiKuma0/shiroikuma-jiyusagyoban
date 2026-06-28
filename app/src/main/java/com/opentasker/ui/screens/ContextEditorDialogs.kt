package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionField
import com.opentasker.core.actions.FieldType
import com.opentasker.core.contexts.CalendarSunEventPresets
import com.opentasker.core.contexts.DaySchedule
import com.opentasker.core.contexts.EventContextPreset
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.ui.theme.DesignSystem

@Composable
internal fun ContextTypePickerDialog(onDismiss: () -> Unit, onSelect: (ContextType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_add_context)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                ContextType.entries.forEach { type ->
                    Card(
                        onClick = { onSelect(type) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs)) {
                            Text(type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                            Text(contextDescription(type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
internal fun ContextConfigDialog(
    state: ContextEditState,
    onDismiss: () -> Unit,
    onSave: (ContextSpec) -> Unit,
) {
    var invert by rememberSaveable(state.profile.id, state.index, state.type) { mutableStateOf(state.existing?.invert ?: false) }
    var config by rememberSaveable(state.profile.id, state.index, state.type) {
        mutableStateOf(defaultContextConfig(state.type) + (state.existing?.config ?: emptyMap()))
    }
    var nfcWriteMessage by rememberSaveable(state.profile.id, state.index, state.type) { mutableStateOf<String?>(null) }
    val fields = contextFields(state.type)
    val saveConfig = contextConfigForSave(state.type, config)
    val missingRequired = fields.any { it.required && config[it.key].isNullOrBlank() } ||
        (state.type == ContextType.DAY && saveConfig["days"].isNullOrBlank())
    val onLabel = stringResource(R.string.label_on)
    val offLabel = stringResource(R.string.label_off)

    LaunchedEffect(Unit) {
        NfcTagWriteSession.results.collect { result ->
            nfcWriteMessage = result.message
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.type.name.lowercase().replaceFirstChar { it.uppercase() }) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(contextDescription(state.type), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = invert,
                                role = Role.Switch,
                                onValueChange = { invert = it },
                            )
                            .semantics {
                                stateDescription = if (invert) onLabel else offLabel
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.context_invert_match), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    stringResource(R.string.context_invert_helper),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = invert, onCheckedChange = null)
                        }
                    }
                    HorizontalDivider()
                }
                if (state.type == ContextType.DAY) {
                    item("day-schedule") {
                        DayScheduleInput(
                            value = config["days"].orEmpty(),
                            onChange = { value -> config = config + ("days" to value) },
                        )
                    }
                } else {
                    items(fields, key = { it.key }) { field ->
                        ActionFieldInput(
                            field = field,
                            value = config[field.key].orEmpty(),
                            onChange = { value -> config = config + (field.key to value) },
                        )
                    }
                    if (state.type == ContextType.EVENT && config["event"].equals("nfc", ignoreCase = true)) {
                        item("nfc-write-helper") {
                            NfcWriteHelperCard(
                                tagId = config["tagId"].orEmpty(),
                                message = nfcWriteMessage,
                                onArm = { label ->
                                    nfcWriteMessage = NfcTagWriteSession.armTextRecord(label).message
                                },
                            )
                        }
                    }
                    val eventPresets = if (state.type == ContextType.EVENT) {
                        CalendarSunEventPresets.presetsFor(config["event"].orEmpty())
                    } else {
                        emptyList()
                    }
                    if (eventPresets.isNotEmpty()) {
                        item("event-presets") {
                            EventPresetRow(
                                presets = eventPresets,
                                onApply = { preset ->
                                    config = CalendarSunEventPresets.applyPreset(config, preset)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired,
                onClick = { onSave(ContextSpec(state.type, saveConfig, invert)) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun contextFields(type: ContextType): List<ActionField> = when (type) {
    ContextType.APPLICATION -> listOf(ActionField("package", "Package name", required = true, hint = "com.example.app (personal profile only)"))
    ContextType.TIME -> listOf(
        ActionField("start", "Start HH:mm", required = true, hint = "09:00"),
        ActionField("end", "End HH:mm", required = true, hint = "17:00"),
    )
    ContextType.DAY -> listOf(ActionField("days", "Days", required = true, hint = "weekdays, weekends, MON-FRI"))
    ContextType.LOCATION -> listOf(
        ActionField("latitude", "Latitude", FieldType.NUMBER, required = true),
        ActionField("longitude", "Longitude", FieldType.NUMBER, required = true),
        ActionField("radiusMeters", "Radius meters", FieldType.NUMBER, required = true, hint = "100"),
        ActionField("maxAccuracyMeters", "Max accuracy meters", FieldType.NUMBER, hint = "50"),
        ActionField("dwellSeconds", "Dwell seconds", FieldType.NUMBER, hint = "300"),
    )
    ContextType.STATE -> listOf(
        ActionField("key", "State key", required = true, hint = "battery_level, charging, headphones, screen"),
        ActionField("operator", "Operator", hint = "=, >=, <=, >, <"),
        ActionField("value", "Expected value", required = true, hint = "true/false, connected/disconnected, on/off, 80"),
    )
    ContextType.EVENT -> listOf(
        ActionField("event", "Event type", required = true, hint = "boot_completed, notification, nfc, bluetooth, calendar, sunrise, sunset, shake, package_added, package_removed, package_replaced"),
        ActionField("state", "Event state", hint = "during, upcoming, connected, disconnected"),
        ActionField("calendar", "Calendar name", hint = "Work"),
        ActionField("beforeMinutes", "Before minutes", FieldType.NUMBER, hint = "15"),
        ActionField("package", "Package allowlist", hint = "com.example.app, com.chat.app"),
        ActionField("tagId", "NFC tag ID", hint = "04AABBCC"),
        ActionField("latitude", "Latitude", FieldType.NUMBER, hint = "40.7128"),
        ActionField("longitude", "Longitude", FieldType.NUMBER, hint = "-74.0060"),
        ActionField("offsetMinutes", "Sun offset minutes", FieldType.NUMBER, hint = "-30"),
        ActionField("windowMinutes", "Sun window minutes", FieldType.NUMBER, hint = "5"),
        ActionField("title", "Title contains", hint = "Optional notification title text"),
        ActionField("body", "Body contains", hint = "Optional notification body text"),
        ActionField("filter", "Any metadata filter", hint = "Optional text/package/action filter"),
        ActionField("regex", "Use regex matching", FieldType.CHECKBOX),
    )
    ContextType.PLUGIN -> listOf(
        ActionField("package", "Plugin package", required = true, hint = "com.example.plugin"),
        ActionField("bundleJson", "Plugin config JSON", hint = "{\"key\":\"value\"}"),
        ActionField("blurb", "Description", hint = "Optional label from plugin config"),
        ActionField("timeoutMs", "Query timeout ms", FieldType.NUMBER, hint = "5000"),
    )
}

private fun contextConfigForSave(type: ContextType, config: Map<String, String>): Map<String, String> {
    val nonBlank = config.filterValues { it.isNotBlank() }
    if (type == ContextType.DAY) {
        val canonicalDays = DaySchedule.canonicalize(config["days"].orEmpty()).orEmpty()
        return if (canonicalDays.isBlank()) {
            nonBlank - "days"
        } else {
            nonBlank + ("days" to canonicalDays)
        }
    }
    if (type == ContextType.PLUGIN) {
        val result = nonBlank.toMutableMap()
        val bundle = result["bundleJson"]?.trim().orEmpty()
        if (bundle.isBlank() || bundle == "{}") {
            result.remove("bundleJson")
        }
        val timeout = result["timeoutMs"]?.toLongOrNull()?.coerceIn(1_000, 30_000)
        if (timeout != null) {
            result["timeoutMs"] = timeout.toString()
        } else {
            result.remove("timeoutMs")
        }
        return result
    }
    return nonBlank
}

private fun defaultContextConfig(type: ContextType): Map<String, String> = when (type) {
    ContextType.TIME -> mapOf("start" to "09:00", "end" to "17:00")
    ContextType.DAY -> mapOf("days" to "MON,TUE,WED,THU,FRI")
    ContextType.LOCATION -> mapOf("radiusMeters" to "100")
    ContextType.PLUGIN -> mapOf("timeoutMs" to "5000")
    else -> emptyMap()
}

@Composable
internal fun DayScheduleInput(value: String, onChange: (String) -> Unit) {
    val selected = DaySchedule.parse(value)
    val canonical = DaySchedule.canonicalize(selected).orEmpty()
    val allDays = DaySchedule.orderedDays.toSet()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.context_day_schedule), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
            DayPresetButton(
                label = stringResource(R.string.context_daily),
                selected = selected == allDays,
                onClick = { onChange(DaySchedule.canonicalize(allDays).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
            DayPresetButton(
                label = stringResource(R.string.context_weekdays),
                selected = selected == DaySchedule.weekdays,
                onClick = { onChange(DaySchedule.canonicalize(DaySchedule.weekdays).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
            DayPresetButton(
                label = stringResource(R.string.context_weekend),
                selected = selected == DaySchedule.weekends,
                onClick = { onChange(DaySchedule.canonicalize(DaySchedule.weekends).orEmpty()) },
                modifier = Modifier.weight(1f),
            )
        }
        listOf(
            listOf("MON", "TUE", "WED"),
            listOf("THU", "FRI", "SAT", "SUN"),
        ).forEach { rowDays ->
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                rowDays.forEach { day ->
                    DayPresetButton(
                        label = day,
                        selected = day in selected,
                        onClick = {
                            val next = if (day in selected) selected - day else selected + day
                            onChange(DaySchedule.canonicalize(next).orEmpty())
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it) },
            label = { Text(stringResource(R.string.context_days_label)) },
            placeholder = { Text(stringResource(R.string.context_days_hint)) },
            supportingText = {
                Text(
                    when {
                        value.isBlank() -> stringResource(R.string.context_days_select_one)
                        canonical.isBlank() -> stringResource(R.string.context_days_invalid_helper)
                        else -> DaySchedule.displayLabel(value)
                    },
                )
            },
            isError = value.isNotBlank() && canonical.isBlank(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun DayPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f) else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.62f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f),
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
internal fun NfcWriteHelperCard(
    tagId: String,
    message: String?,
    onArm: (String) -> Unit,
) {
    val label = if (tagId.isBlank()) {
        stringResource(R.string.context_nfc_trigger_label)
    } else {
        stringResource(R.string.context_nfc_trigger_label_with_tag, tagId)
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit), tint = MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.context_nfc_write_helper), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.context_nfc_write_helper_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { onArm(label) }) {
                    Text(stringResource(R.string.action_arm))
                }
            }
            message?.takeIf { it.isNotBlank() }?.let { value ->
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun EventPresetRow(
    presets: List<EventContextPreset>,
    onApply: (EventContextPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
        Text(stringResource(R.string.context_presets), style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            items(presets, key = { it.id }) { preset ->
                OutlinedButton(onClick = { onApply(preset) }) {
                    Text(preset.label)
                }
            }
        }
    }
}

internal fun contextDescription(type: ContextType): String = when (type) {
    ContextType.APPLICATION -> "Matches when an app is detected in the foreground."
    ContextType.TIME -> "Matches during a clock time window."
    ContextType.DAY -> "Matches on selected days, presets, or weekday/weekend ranges."
    ContextType.LOCATION -> "Matches near a latitude/longitude radius with optional accuracy and dwell checks."
    ContextType.STATE -> "Matches a device state such as battery level, charging, headphones, or screen."
    ContextType.EVENT -> "Matches a one-shot event such as boot, notification, NFC, Bluetooth connect/disconnect, calendar, sun, shake, or Locale plugin queries."
    ContextType.PLUGIN -> "Matches when a Locale/Tasker condition plugin reports satisfied. The plugin is polled periodically and its last known state is cached."
}
