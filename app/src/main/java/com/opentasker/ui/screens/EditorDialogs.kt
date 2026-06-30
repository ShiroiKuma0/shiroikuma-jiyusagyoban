package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.core.templates.TemplateAvailability
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.ui.theme.DesignSystem
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TemplatePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (ProfileTemplate) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_starter_templates)) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ProfileTemplateCatalog.all, key = { it.id }) { template ->
                    val status = when (template.availability) {
                        TemplateAvailability.Ready -> stringResource(R.string.status_ready)
                        TemplateAvailability.RequiresSetup -> stringResource(R.string.status_needs_setup)
                        TemplateAvailability.Planned -> stringResource(R.string.status_planned)
                    }
                    Card(
                        onClick = { onSelect(template) },
                        enabled = template.installable,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (template.installable) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                            },
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                            ) {
                                Text(template.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                StatusPill(
                                    status,
                                    when (template.availability) {
                                        TemplateAvailability.Ready -> MaterialTheme.colorScheme.tertiary
                                        TemplateAvailability.RequiresSetup -> MaterialTheme.colorScheme.primary
                                        TemplateAvailability.Planned -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(template.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(template.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(template.safetyNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun TemplateSlotDialog(
    template: ProfileTemplate,
    onDismiss: () -> Unit,
    onInstall: (Map<String, String>) -> Unit,
) {
    var values by rememberSaveable(template.id) { mutableStateOf(template.defaults()) }
    val missingRequired = template.slots.any { it.required && values[it.key].isNullOrBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.title) },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                item {
                    Text(template.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    ) {
                        Text(
                            stringResource(R.string.template_disabled_review),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(template.slots, key = { it.key }) { slot ->
                    OutlinedTextField(
                        value = values[slot.key].orEmpty(),
                        onValueChange = { values = values + (slot.key to it) },
                        label = { Text(slot.label + if (slot.required) " *" else "") },
                        placeholder = slot.hint?.let { { Text(it) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !missingRequired && template.installable,
                onClick = { onInstall(values) },
            ) {
                Text(stringResource(R.string.action_create_for_review))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun TaskEditorDialog(
    task: Task?,
    siblingNames: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (String, Int, String?, Boolean) -> Unit,
) {
    var name by rememberSaveable(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var priority by rememberSaveable(task?.id) { mutableStateOf((task?.priority ?: 5).toString()) }
    var freezeBubble by rememberSaveable(task?.id) { mutableStateOf(task?.freezeBubble ?: false) }
    val parsedPriority = priority.toIntOrNull()
    // Names are unique within a project (siblingNames = other tasks in the same project, lowercased).
    val nameClash = name.isNotBlank() && name.trim().lowercase() in siblingNames
    val canSave = name.isNotBlank() && !nameClash && parsedPriority != null && parsedPriority in 0..10

    // The persisted icon (when editing) vs. the in-progress staged selection. While staging we only delete
    // a *staged* file we are replacing; the persisted one is cleaned on Save (in updateTask) or kept on Cancel.
    val originalPath = remember(task?.id) { task?.iconPath }
    var iconPath by rememberSaveable(task?.id) { mutableStateOf(task?.iconPath) }

    fun stageIcon(newPath: String?) {
        val current = iconPath
        if (current != null && current != originalPath) TaskIconStore.delete(current)
        iconPath = newPath
    }
    val cleanupAndDismiss = {
        val current = iconPath
        if (current != null && current != originalPath) TaskIconStore.delete(current)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = cleanupAndDismiss,
        title = { Text(if (task == null) stringResource(R.string.dialog_create_task) else stringResource(R.string.dialog_edit_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.task_name_label)) },
                    placeholder = { Text(stringResource(R.string.task_name_hint)) },
                    isError = nameClash,
                    supportingText = {
                        Text(
                            if (nameClash) "A task with this name already exists in this project."
                            else stringResource(R.string.task_name_helper)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.task_priority_label)) },
                    supportingText = {
                        Text(
                            if (parsedPriority == null || parsedPriority !in 0..10) {
                                stringResource(R.string.task_priority_invalid)
                            } else {
                                stringResource(R.string.task_priority_helper)
                            }
                        )
                    },
                    isError = parsedPriority == null || parsedPriority !in 0..10,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Freeze bubble: running this task pops a re-freeze bubble for the app it launches,
                // shown on the Desktop launcher.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Freeze bubble", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Re-freeze on the Desktop — running this task pops a freeze bubble for its app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = freezeBubble, onCheckedChange = { freezeBubble = it })
                }
                TaskIconEditorRow(iconPath = iconPath, onStage = { stageIcon(it) })
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, parsedPriority ?: 5, iconPath, freezeBubble) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = cleanupAndDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The shared icon-source editor: an icon preview + App / Picture / Emoji / Audio / Clear. Each source
 * snapshots a fresh PNG (via [TaskIconStore]) and reports it through [onStage]; the caller owns
 * staging/cleanup. Reuses [AppPickerDialog] and [EmojiPickerDialog] (same package).
 */
@Composable
private fun TaskIconEditorRow(iconPath: String?, onStage: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember(iconPath) { TaskIconStore.loadBitmap(iconPath) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { TaskIconStore.saveFromUri(context, uri) }
            if (saved != null) onStage(saved)
        }
    }
    // Pick an audio file (mp3/ogg/…) and use its embedded album art as the icon.
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) { TaskIconStore.saveFromAudio(context, uri) }
            if (saved != null) onStage(saved)
            else android.widget.Toast.makeText(context, "No album art in that file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shortcut icon", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Selected icon",
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("App") }
                    OutlinedButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("Picture") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showEmojiPicker = true }) { Text("Emoji") }
                    OutlinedButton(onClick = { audioPicker.launch("audio/*") }) { Text("Audio") }
                    if (iconPath != null) TextButton(onClick = { onStage(null) }) { Text("Clear") }
                }
            }
        }
    }
    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onPick = { pkg ->
                showAppPicker = false
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { TaskIconStore.saveFromApp(context, pkg) }
                    if (saved != null) onStage(saved)
                }
            },
        )
    }
    if (showEmojiPicker) {
        EmojiPickerDialog(
            initial = "",
            onDismiss = { showEmojiPicker = false },
            onConfirm = { glyph ->
                showEmojiPicker = false
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { TaskIconStore.saveFromText(context, glyph) }
                    if (saved != null) onStage(saved)
                }
            },
        )
    }
}

@Composable
internal fun ProfileEditorDialog(
    profile: Profile?,
    tasks: List<Task>,
    siblingNames: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Long, Int, AutomationMode, String?) -> Unit,
) {
    val initialTaskId = profile?.enterTaskId ?: tasks.firstOrNull()?.id ?: 0L
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var enabled by rememberSaveable(profile?.id) { mutableStateOf(profile?.enabled ?: true) }
    var enterTaskId by rememberSaveable(profile?.id, tasks) { mutableLongStateOf(initialTaskId) }
    var cooldown by rememberSaveable(profile?.id) { mutableStateOf((profile?.cooldownSec ?: 0).toString()) }
    var automationMode by rememberSaveable(profile?.id) { mutableStateOf(profile?.automationMode ?: AutomationMode.SINGLE) }
    var group by rememberSaveable(profile?.id) { mutableStateOf(profile?.group.orEmpty()) }
    val parsedCooldown = cooldown.toIntOrNull()
    // Names are unique within a project (siblingNames = other profiles in the same project, lowercased).
    val nameClash = name.isNotBlank() && name.trim().lowercase() in siblingNames
    val canSave = name.isNotBlank() && !nameClash && enterTaskId > 0 && (cooldown.isBlank() || parsedCooldown != null)
    val onLabel = stringResource(R.string.label_on)
    val offLabel = stringResource(R.string.label_off)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) stringResource(R.string.dialog_create_profile) else stringResource(R.string.dialog_edit_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.profile_name_label)) },
                    placeholder = { Text(stringResource(R.string.profile_name_hint)) },
                    isError = nameClash,
                    supportingText = {
                        Text(
                            if (nameClash) "A profile with this name already exists in this project."
                            else stringResource(R.string.profile_name_helper)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text(stringResource(R.string.profile_group_label)) },
                    placeholder = { Text(stringResource(R.string.profile_group_hint)) },
                    supportingText = { Text(stringResource(R.string.profile_group_helper)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = enabled,
                            role = Role.Switch,
                            onValueChange = { enabled = it },
                        )
                        .semantics {
                            stateDescription = if (enabled) onLabel else offLabel
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.profile_enable_after_save), style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(R.string.profile_enable_after_save_helper),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                }
                Text(stringResource(R.string.profile_enter_task), style = MaterialTheme.typography.labelLarge)
                tasks.forEach { task ->
                    SelectableOption(
                        title = task.name,
                        body = stringResource(R.string.label_action_count, task.actions.size),
                        selected = task.id == enterTaskId,
                        onClick = { enterTaskId = task.id },
                    )
                }
                OutlinedTextField(
                    value = cooldown,
                    onValueChange = { cooldown = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.profile_cooldown_label)) },
                    supportingText = {
                        Text(
                            if (cooldown.isNotBlank() && parsedCooldown == null) {
                                stringResource(R.string.profile_cooldown_invalid)
                            } else {
                                stringResource(R.string.profile_cooldown_helper)
                            }
                        )
                    },
                    isError = cooldown.isNotBlank() && parsedCooldown == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.profile_retrigger_label), style = MaterialTheme.typography.labelLarge)
                AutomationMode.entries.forEach { mode ->
                    val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
                    SelectableOption(
                        title = label,
                        body = automationModeDescription(mode),
                        selected = mode == automationMode,
                        onClick = { automationMode = mode },
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, enabled, enterTaskId, parsedCooldown ?: 0, automationMode, group.trim().ifBlank { null }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun SelectableOption(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f) else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                StatusPill(stringResource(R.string.label_selected), MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun automationModeDescription(mode: AutomationMode): String = when (mode) {
    AutomationMode.SINGLE -> stringResource(R.string.automation_mode_single)
    AutomationMode.RESTART -> stringResource(R.string.automation_mode_restart)
    AutomationMode.QUEUED -> stringResource(R.string.automation_mode_queued)
    AutomationMode.PARALLEL -> stringResource(R.string.automation_mode_parallel)
}

@Composable
internal fun EmptyState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    contentPadding: PaddingValues,
    actionEnabled: Boolean = true,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    secondaryActionEnabled: Boolean = true,
    tertiaryActionLabel: String? = null,
    onTertiaryAction: (() -> Unit)? = null,
    tertiaryActionEnabled: Boolean = true,
    quaternaryActionLabel: String? = null,
    onQuaternaryAction: (() -> Unit)? = null,
    quaternaryActionEnabled: Boolean = true,
) {
    val actionWidth = Modifier
        .widthIn(max = 420.dp)
        .fillMaxWidth()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        ) {
            Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.ui_info_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Text(actionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (
            secondaryActionLabel != null &&
            onSecondaryAction != null &&
            tertiaryActionLabel != null &&
            onTertiaryAction != null
        ) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = actionWidth,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                OutlinedButton(
                    onClick = onSecondaryAction,
                    enabled = secondaryActionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(secondaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                OutlinedButton(
                    onClick = onTertiaryAction,
                    enabled = tertiaryActionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(tertiaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        } else if (secondaryActionLabel != null && onSecondaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSecondaryAction,
                enabled = secondaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Text(secondaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        } else if (tertiaryActionLabel != null && onTertiaryAction != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onTertiaryAction,
                enabled = tertiaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            ) {
                Text(tertiaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (quaternaryActionLabel != null && onQuaternaryAction != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onQuaternaryAction,
                enabled = quaternaryActionEnabled,
                modifier = actionWidth
                    .heightIn(min = 48.dp),
            ) {
                Text(quaternaryActionLabel, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

internal sealed interface DeleteTarget {
    data class ProfileTarget(val profile: Profile) : DeleteTarget
    data class TaskTarget(val task: Task) : DeleteTarget
    data class SceneTarget(val scene: Scene) : DeleteTarget
    data class ActionTarget(val task: Task, val index: Int, val action: ActionSpec) : DeleteTarget
    data class ContextTarget(val profile: Profile, val index: Int, val context: ContextSpec) : DeleteTarget
}

@Composable
internal fun DeleteConfirmationDialog(
    target: DeleteTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (target) {
        is DeleteTarget.ProfileTarget -> stringResource(R.string.dialog_delete_profile)
        is DeleteTarget.TaskTarget -> stringResource(R.string.dialog_delete_task)
        is DeleteTarget.SceneTarget -> stringResource(R.string.dialog_delete_scene)
        is DeleteTarget.ActionTarget -> stringResource(R.string.dialog_remove_action)
        is DeleteTarget.ContextTarget -> stringResource(R.string.dialog_remove_context)
    }
    val body = when (target) {
        is DeleteTarget.ProfileTarget -> stringResource(R.string.delete_profile_body, target.profile.name)
        is DeleteTarget.TaskTarget -> stringResource(R.string.delete_task_body, target.task.name, target.task.actions.size)
        is DeleteTarget.SceneTarget -> stringResource(R.string.delete_scene_body, target.scene.name, target.scene.elements.size)
        is DeleteTarget.ActionTarget -> stringResource(R.string.delete_action_body, target.index + 1, target.task.name)
        is DeleteTarget.ContextTarget -> stringResource(R.string.delete_context_body, target.context.type.name.lowercase(), target.profile.name)
    }
    val confirmLabel = when (target) {
        is DeleteTarget.ProfileTarget -> stringResource(R.string.profile_delete)
        is DeleteTarget.TaskTarget -> stringResource(R.string.task_delete)
        is DeleteTarget.SceneTarget -> stringResource(R.string.scenes_delete)
        is DeleteTarget.ActionTarget -> stringResource(R.string.action_remove_action)
        is DeleteTarget.ContextTarget -> stringResource(R.string.action_remove_context)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
                ) {
                    Text(
                        stringResource(R.string.delete_cannot_undo),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

/** Standalone icon picker (used from a task card's clickable icon). Stages files internally and returns
 *  the chosen path via [onConfirm]; the caller persists it (and cleans the old file via updateTask). */
@Composable
internal fun TaskIconPickerDialog(initialIconPath: String?, onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    val original = remember { initialIconPath }
    var staged by remember { mutableStateOf(initialIconPath) }
    fun stage(newPath: String?) {
        val current = staged
        if (current != null && current != original) TaskIconStore.delete(current)
        staged = newPath
    }
    val cancel = {
        val current = staged
        if (current != null && current != original) TaskIconStore.delete(current)
        onDismiss()
    }
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = cancel,
        title = { Text("Task icon") },
        text = { TaskIconEditorRow(iconPath = staged, onStage = { stage(it) }) },
        confirmButton = { OutlinedButton(onClick = { onConfirm(staged) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
