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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.AutomationMode
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.ProfileConcurrencyPolicy
import com.opentasker.core.model.ProfileLifecyclePolicy
import com.opentasker.core.model.ProfileLifetime
import com.opentasker.core.model.ProfileOverflowPolicy
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.core.templates.ProfileTemplate
import com.opentasker.core.templates.ProfileTemplateCatalog
import com.opentasker.core.templates.TemplateAvailability
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.selectedContainerColor
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
        // Yellow edge, matching the action editor and the other editor dialogs.
        modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
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
internal fun TaskIconEditorRow(iconPath: String?, onStage: (String?) -> Unit, targetPackage: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preview = remember(iconPath) { TaskIconStore.loadBitmap(iconPath) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showActivityIcons by remember { mutableStateOf(false) }
    var showIconPack by remember { mutableStateOf(false) }
    var showFramework by remember { mutableStateOf(false) }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (targetPackage != null) OutlinedButton(onClick = { showActivityIcons = true }) { Text("App icons") }
                    OutlinedButton(onClick = { showIconPack = true }) { Text("Icon pack") }
                    OutlinedButton(onClick = { showFramework = true }) { Text("System") }
                }
            }
        }
    }
    if (showActivityIcons && targetPackage != null) {
        ActivityIconPickerDialog(
            targetPackage = targetPackage,
            onDismiss = { showActivityIcons = false },
            onPick = { showActivityIcons = false; onStage(it) },
        )
    }
    if (showIconPack) {
        IconPackPickerDialog(
            onDismiss = { showIconPack = false },
            onPick = { showIconPack = false; onStage(it) },
        )
    }
    if (showFramework) {
        FrameworkIconPickerDialog(
            onDismiss = { showFramework = false },
            onPick = { showFramework = false; onStage(it) },
        )
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

/**
 * The profile-policy fields the engine arbitrates on, carried out of the dialog as one value.
 *
 * They travel together rather than as seven more `onSave` parameters because they are one decision —
 * how this profile behaves when it competes with others — and because a positional lambda that long
 * is a standing invitation to transpose two arguments.
 */
data class ProfilePolicyDraft(
    val priority: Int,
    val gracePeriodSec: Int,
    val lifetime: ProfileLifetime,
    val expiresAtMs: Long?,
    val maxActiveExecutions: Int?,
    val burstLimit: Int?,
    val overflowPolicy: ProfileOverflowPolicy,
    val fallbackTaskId: Long?,
) {
    companion object {
        fun from(profile: Profile?): ProfilePolicyDraft = ProfilePolicyDraft(
            priority = profile?.priority ?: 0,
            gracePeriodSec = profile?.gracePeriodSec ?: 0,
            lifetime = profile?.lifetime ?: ProfileLifetime.NEVER,
            expiresAtMs = profile?.expiresAtMs,
            maxActiveExecutions = profile?.maxActiveExecutions,
            burstLimit = profile?.burstLimit,
            overflowPolicy = profile?.overflowPolicy ?: ProfileOverflowPolicy.LOG,
            fallbackTaskId = profile?.fallbackTaskId,
        )
    }
}

/** Applies a draft to a profile, normalising it through the same policy the engine reads. */
fun Profile.withPolicy(draft: ProfilePolicyDraft): Profile = ProfileLifecyclePolicy.normalize(
    copy(
        priority = draft.priority,
        gracePeriodSec = draft.gracePeriodSec,
        lifetime = draft.lifetime,
        expiresAtMs = draft.expiresAtMs,
        maxActiveExecutions = draft.maxActiveExecutions,
        burstLimit = draft.burstLimit,
        overflowPolicy = draft.overflowPolicy,
        fallbackTaskId = draft.fallbackTaskId,
        // Re-arming a spent one-shot is the point of editing it back to ONCE; a profile that is no
        // longer ONCE cannot stay "consumed" either.
        lifetimeConsumed = lifetimeConsumed && draft.lifetime == ProfileLifetime.ONCE && lifetime == ProfileLifetime.ONCE,
    ),
)

@Composable
internal fun ProfileEditorDialog(
    profile: Profile?,
    tasks: List<Task>,
    siblingNames: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (String, Boolean, Long, Int, AutomationMode, String?, ProfilePolicyDraft) -> Unit,
) {
    val initialTaskId = profile?.enterTaskId ?: tasks.firstOrNull()?.id ?: 0L
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    // New profiles start disabled, matching templates and imports and the "Leave off until
    // reviewed" helper; every other creation path is review-first.
    var enabled by rememberSaveable(profile?.id) { mutableStateOf(profile?.enabled ?: false) }
    // Keyed only on profile identity: re-keying on `tasks` reset the user's selection to the
    // default whenever the tasks flow re-emitted (a parallel import, a rename re-sorting the
    // list) mid-edit. A vanished selection is caught by the canSave existence check below.
    var enterTaskId by rememberSaveable(profile?.id) { mutableLongStateOf(initialTaskId) }
    var cooldown by rememberSaveable(profile?.id) { mutableStateOf((profile?.cooldownSec ?: 0).toString()) }
    var automationMode by rememberSaveable(profile?.id) { mutableStateOf(profile?.automationMode ?: AutomationMode.SINGLE) }
    var group by rememberSaveable(profile?.id) { mutableStateOf(profile?.group.orEmpty()) }
    val parsedCooldown = cooldown.toIntOrNull()

    // ── Arbitration policy ────────────────────────────────────────────────────────────────────
    var priorityText by rememberSaveable(profile?.id) { mutableStateOf((profile?.priority ?: 0).toString()) }
    var graceText by rememberSaveable(profile?.id) { mutableStateOf((profile?.gracePeriodSec ?: 0).toString()) }
    var lifetime by rememberSaveable(profile?.id) { mutableStateOf(profile?.lifetime ?: ProfileLifetime.NEVER) }
    var expiryText by rememberSaveable(profile?.id) { mutableStateOf(profile?.expiresAtMs?.let(::isoDate).orEmpty()) }
    var maxActiveText by rememberSaveable(profile?.id) { mutableStateOf(profile?.maxActiveExecutions?.toString().orEmpty()) }
    var burstText by rememberSaveable(profile?.id) { mutableStateOf(profile?.burstLimit?.toString().orEmpty()) }
    var overflowPolicy by rememberSaveable(profile?.id) { mutableStateOf(profile?.overflowPolicy ?: ProfileOverflowPolicy.LOG) }
    var fallbackTaskId by rememberSaveable(profile?.id) { mutableLongStateOf(profile?.fallbackTaskId ?: 0L) }

    val parsedPriority = priorityText.trim().toIntOrNull()
    val priorityValid = parsedPriority != null &&
        parsedPriority in ProfileLifecyclePolicy.MIN_PRIORITY..ProfileLifecyclePolicy.MAX_PRIORITY
    val parsedGrace = graceText.trim().toIntOrNull()
    val graceValid = parsedGrace != null && parsedGrace in 0..ProfileLifecyclePolicy.MAX_GRACE_PERIOD_SEC
    val parsedExpiry = expiryText.trim().takeIf(String::isNotEmpty)?.let(::epochMillisAtStartOfDay)
    // An expiry is required for UNTIL_DATE and meaningless otherwise, so it only blocks Save there.
    val expiryValid = lifetime != ProfileLifetime.UNTIL_DATE || parsedExpiry != null
    val parsedMaxActive = maxActiveText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
    val maxActiveValid = maxActiveText.isBlank() || (parsedMaxActive != null &&
        parsedMaxActive in ProfileConcurrencyPolicy.MIN_MAX_ACTIVE..ProfileConcurrencyPolicy.MAX_MAX_ACTIVE)
    val parsedBurst = burstText.trim().takeIf(String::isNotEmpty)?.toIntOrNull()
    val burstValid = burstText.isBlank() || (parsedBurst != null &&
        parsedBurst in ProfileConcurrencyPolicy.MIN_BURST_LIMIT..ProfileConcurrencyPolicy.MAX_BURST_LIMIT)
    val policyValid = priorityValid && graceValid && expiryValid && maxActiveValid && burstValid
    val policyDraft = ProfilePolicyDraft(
        priority = parsedPriority ?: 0,
        gracePeriodSec = parsedGrace ?: 0,
        lifetime = lifetime,
        expiresAtMs = parsedExpiry.takeIf { lifetime == ProfileLifetime.UNTIL_DATE },
        maxActiveExecutions = parsedMaxActive,
        burstLimit = parsedBurst,
        overflowPolicy = overflowPolicy,
        fallbackTaskId = fallbackTaskId.takeIf { it > 0L },
    )
    // Names are unique within a project (siblingNames = other profiles in the same project, lowercased).
    val nameClash = name.isNotBlank() && name.trim().lowercase() in siblingNames
    // The picked enter task must still exist (upstream deep-audit: no saving a dangling binding).
    val selectedTaskExists = tasks.any { it.id == enterTaskId }
    val canSave = name.isNotBlank() && !nameClash && enterTaskId > 0 && selectedTaskExists &&
        (cooldown.isBlank() || parsedCooldown != null) && policyValid
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
                    SelectableOption(
                        title = stringResource(automationModeTitleRes(mode)),
                        body = automationModeDescription(mode),
                        selected = mode == automationMode,
                        onClick = { automationMode = mode },
                    )
                }

                HorizontalDivider()
                Text("Arbitration", style = MaterialTheme.typography.titleSmall)
                Text(
                    "How this profile behaves when it competes with others. Everything here is off by " +
                        "default — a profile left alone runs exactly as before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it.filter { c -> c.isDigit() || c == '-' }.take(4) },
                    label = { Text("Priority") },
                    supportingText = {
                        Text(
                            if (!priorityValid) {
                                "Whole number between ${ProfileLifecyclePolicy.MIN_PRIORITY} and ${ProfileLifecyclePolicy.MAX_PRIORITY}."
                            } else {
                                "While this profile matches, it suppresses any matching profile with a STRICTLY lower " +
                                    "priority — their tasks are skipped and logged, and they run again the moment this " +
                                    "one stops matching. Equal priorities never suppress each other, so leaving every " +
                                    "profile at 0 keeps them all independent."
                            }
                        )
                    },
                    isError = !priorityValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = graceText,
                    onValueChange = { graceText = it.filter(Char::isDigit).take(4) },
                    label = { Text("Grace period (seconds)") },
                    supportingText = {
                        Text(
                            if (!graceValid) {
                                "Whole number of seconds, 0 to ${ProfileLifecyclePolicy.MAX_GRACE_PERIOD_SEC}."
                            } else {
                                "The conditions must hold this long before an activation OR a deactivation is " +
                                    "accepted — it debounces a flapping trigger. 0 reacts immediately."
                            }
                        )
                    },
                    isError = !graceValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Lifetime", style = MaterialTheme.typography.labelLarge)
                SelectableOption(
                    title = "Always",
                    body = "Runs for as long as it stays enabled.",
                    selected = lifetime == ProfileLifetime.NEVER,
                    onClick = { lifetime = ProfileLifetime.NEVER },
                )
                SelectableOption(
                    title = "Until a date",
                    body = "Stops matching at the start of the day you name, and disables itself.",
                    selected = lifetime == ProfileLifetime.UNTIL_DATE,
                    onClick = { lifetime = ProfileLifetime.UNTIL_DATE },
                )
                SelectableOption(
                    title = "Once",
                    body = "Runs a single time, then disables itself. Re-enabling it here arms it again.",
                    selected = lifetime == ProfileLifetime.ONCE,
                    onClick = { lifetime = ProfileLifetime.ONCE },
                )
                if (lifetime == ProfileLifetime.UNTIL_DATE) {
                    OutlinedTextField(
                        value = expiryText,
                        onValueChange = { expiryText = it.take(10) },
                        label = { Text("Expires on (YYYY-MM-DD)") },
                        supportingText = {
                            Text(
                                if (!expiryValid) "Enter a real date as YYYY-MM-DD, e.g. 2026-12-31."
                                else "The profile stops matching at 00:00 local time on this day."
                            )
                        },
                        isError = !expiryValid,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = maxActiveText,
                    onValueChange = { maxActiveText = it.filter(Char::isDigit).take(1) },
                    label = { Text("Max concurrent runs (optional)") },
                    supportingText = {
                        Text(
                            if (!maxActiveValid) {
                                "${ProfileConcurrencyPolicy.MIN_MAX_ACTIVE}–${ProfileConcurrencyPolicy.MAX_MAX_ACTIVE}, or blank for the engine default."
                            } else {
                                "Caps how many of this profile's runs may be in flight at once."
                            }
                        )
                    },
                    isError = !maxActiveValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = burstText,
                    onValueChange = { burstText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Burst limit (optional)") },
                    supportingText = {
                        Text(
                            if (!burstValid) {
                                "${ProfileConcurrencyPolicy.MIN_BURST_LIMIT}–${ProfileConcurrencyPolicy.MAX_BURST_LIMIT}, or blank for the engine default."
                            } else {
                                "Caps how many runs this profile may START inside the engine's burst window."
                            }
                        )
                    },
                    isError = !burstValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("When a run is refused", style = MaterialTheme.typography.labelLarge)
                SelectableOption(
                    title = "Log it",
                    body = "A refused run leaves a skipped entry in the Run Log, saying which limit refused it.",
                    selected = overflowPolicy == ProfileOverflowPolicy.LOG,
                    onClick = { overflowPolicy = ProfileOverflowPolicy.LOG },
                )
                SelectableOption(
                    title = "Drop it quietly",
                    body = "No Run Log entry. For a fast trigger whose refusals are noise rather than news.",
                    selected = overflowPolicy == ProfileOverflowPolicy.SILENT,
                    onClick = { overflowPolicy = ProfileOverflowPolicy.SILENT },
                )

                Text("Recovery task", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Run when this profile's task fails and nothing in it caught the error. It receives " +
                        "the failure as variables — which task, which action, which message — so it can " +
                        "report or repair. It runs once and never triggers its own recovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectableOption(
                    title = "None",
                    body = "A failure is logged and left alone.",
                    selected = fallbackTaskId <= 0L,
                    onClick = { fallbackTaskId = 0L },
                )
                tasks.filter { it.id != enterTaskId }.forEach { candidate ->
                    SelectableOption(
                        title = candidate.name,
                        body = stringResource(R.string.label_action_count, candidate.actions.size),
                        selected = candidate.id == fallbackTaskId,
                        onClick = { fallbackTaskId = candidate.id },
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onSave(name, enabled, enterTaskId, parsedCooldown ?: 0, automationMode, group.trim().ifBlank { null }, policyDraft) }) {
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
        // These groups are radio pickers (overflow policy, lifetime, retrigger). Without the role
        // and selected state a screen reader announced them as plain buttons, so an unselected
        // option said nothing about being unselected and gave no group context.
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                this.role = Role.RadioButton
                this.selected = selected
            },
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) selectedContainerColor() else Color.Transparent,
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
            // An empty state can carry up to five actions; at large font scale the last of them
            // fell off-screen on a compact device with no way to reach it.
            .verticalScroll(rememberScrollState())
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
    // Upstream dropped these two in 47307ea, making action/context removal immediate and undoable
    // through a snackbar instead of a confirmation dialog. The fork keeps the confirmation dialog —
    // its ActiveAutomationUi still routes both through openDeleteAction/openDeleteContext — so both
    // targets stay.
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
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
                ) {
                    Text(
                        stringResource(R.string.delete_undo_helper),
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
internal fun TaskIconPickerDialog(
    initialIconPath: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
    targetPackage: String? = null,
    title: String = "Task icon",
) {
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
        title = { Text(title) },
        text = { TaskIconEditorRow(iconPath = staged, onStage = { stage(it) }, targetPackage = targetPackage) },
        confirmButton = { OutlinedButton(onClick = { onConfirm(staged) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Renders an expiry instant as the ISO date the editor and ProfileLifecyclePolicy both show. */
private fun isoDate(epochMillis: Long): String = runCatching {
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()
}.getOrDefault("")

/**
 * Parses `YYYY-MM-DD` to midnight local time, or null if it is not a real date.
 *
 * Start-of-day, not end: "expires on the 1st" then means it stops matching as the 1st begins, which
 * is what ProfileLifecyclePolicy's own "expired on <date>" message goes on to say.
 */
private fun epochMillisAtStartOfDay(text: String): Long? = runCatching {
    java.time.LocalDate.parse(text)
        .atStartOfDay(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}.getOrNull()
