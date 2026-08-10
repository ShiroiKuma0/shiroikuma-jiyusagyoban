package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionArgumentSensitivity
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.contexts.contextConfigSummary
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.ui.theme.DesignSystem
@Composable
internal fun ProfilesScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    runLogs: List<RunLogEntry>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    onCreateTaskFirst: () -> Unit,
    onCreateProfile: () -> Unit,
    onBrowseTemplates: () -> Unit,
    onPreviewProfileShare: () -> Unit,
    onPreflightProfile: (Profile) -> Unit,
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundleText: () -> Unit,
    openTaskerBundleBusy: Boolean,
    onImportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
    onEditProfile: (Profile) -> Unit,
    onUndoProfileEdit: (Profile) -> Unit = {},
    onRedoProfileEdit: (Profile) -> Unit = {},
    onDeleteProfile: (Profile) -> Unit,
    onToggleProfile: (Profile, Boolean) -> Unit,
    onAddContext: (Profile) -> Unit,
    onEditContextLogic: (Profile) -> Unit,
    onEditContext: (Profile, Int, ContextSpec) -> Unit,
    onDeleteContext: (Profile, Int) -> Unit,
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_first_automation_title),
            body = stringResource(R.string.empty_first_automation_body),
            actionLabel = stringResource(R.string.action_browse_templates),
            onAction = onBrowseTemplates,
            secondaryActionLabel = if (openTaskerBundleBusy) stringResource(R.string.import_reading_bundle) else stringResource(R.string.import_import_json),
            onSecondaryAction = onImportOpenTaskerBundle,
            secondaryActionEnabled = !openTaskerBundleBusy,
            quinaryActionLabel = stringResource(R.string.import_paste_json_action),
            onQuinaryAction = onImportOpenTaskerBundleText,
            quinaryActionEnabled = !openTaskerBundleBusy,
            tertiaryActionLabel = if (taskerImportBusy) stringResource(R.string.import_reading_xml) else stringResource(R.string.action_import_tasker_xml),
            onTertiaryAction = onImportTaskerXml,
            tertiaryActionEnabled = !taskerImportBusy,
            quaternaryActionLabel = stringResource(R.string.action_create_blank_task),
            onQuaternaryAction = onCreateTaskFirst,
            contentPadding = contentPadding,
        )
        return
    }
    if (profiles.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_first_profile_title),
            body = stringResource(R.string.empty_first_profile_body),
            actionLabel = stringResource(R.string.action_browse_templates),
            onAction = onBrowseTemplates,
            secondaryActionLabel = if (openTaskerBundleBusy) stringResource(R.string.import_reading_bundle) else stringResource(R.string.import_import_json),
            onSecondaryAction = onImportOpenTaskerBundle,
            secondaryActionEnabled = !openTaskerBundleBusy,
            quinaryActionLabel = stringResource(R.string.import_paste_json_action),
            onQuinaryAction = onImportOpenTaskerBundleText,
            quinaryActionEnabled = !openTaskerBundleBusy,
            tertiaryActionLabel = if (taskerImportBusy) stringResource(R.string.import_reading_xml) else stringResource(R.string.action_import_tasker_xml),
            onTertiaryAction = onImportTaskerXml,
            tertiaryActionEnabled = !taskerImportBusy,
            quaternaryActionLabel = stringResource(R.string.action_create_blank_profile),
            onQuaternaryAction = onCreateProfile,
            contentPadding = contentPadding,
        )
        return
    }

    var profileSearchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = remember(profiles) {
        profiles.mapNotNull { it.group }.distinct().sorted()
    }
    val filteredProfiles = profiles
        .filter { selectedGroup == null || it.group == selectedGroup }
        .filter { profileSearchQuery.isBlank() || it.name.contains(profileSearchQuery, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        item {
            WorkspaceSummaryCard(
                profiles = profiles,
                tasks = tasks,
                runLogs = runLogs,
                onBrowseTemplates = onBrowseTemplates,
                onPreviewProfileShare = onPreviewProfileShare,
                onExportOpenTaskerBundle = onExportOpenTaskerBundle,
                onImportOpenTaskerBundle = onImportOpenTaskerBundle,
                onImportOpenTaskerBundleText = onImportOpenTaskerBundleText,
                openTaskerBundleBusy = openTaskerBundleBusy,
                onImportTaskerXml = onImportTaskerXml,
                taskerImportBusy = taskerImportBusy,
            )
        }
        item {
            TemplatePromptCard(onBrowseTemplates)
        }
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        item {
            OutlinedTextField(
                value = profileSearchQuery,
                onValueChange = { profileSearchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.workspace_search_profiles)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.variables_search_label)) },
                trailingIcon = if (profileSearchQuery.isNotEmpty()) {
                    { IconButton(onClick = { profileSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.variables_search_clear)) } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            )
        }
        if (groups.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                    item {
                        FilterChip(
                            selected = selectedGroup == null,
                            onClick = { selectedGroup = null },
                            label = { Text(stringResource(R.string.label_all)) },
                        )
                    }
                    items(groups, key = { it }) { group ->
                        FilterChip(
                            selected = selectedGroup == group,
                            onClick = { selectedGroup = if (selectedGroup == group) null else group },
                            label = { Text(group) },
                        )
                    }
                }
            }
        }
        if (filteredProfiles.isEmpty()) {
            item {
                InlineNotice(
                    title = stringResource(R.string.workspace_no_matching_profiles),
                    body = stringResource(R.string.workspace_no_matching_profiles_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredProfiles, key = { it.id }) { profile ->
            val enterTaskName = tasks.firstOrNull { it.id == profile.enterTaskId }?.name ?: stringResource(R.string.workspace_missing_task, profile.enterTaskId)
            ProfileCard(
                profile = profile,
                enterTaskName = enterTaskName,
                onEdit = { onEditProfile(profile) },
                onUndo = { onUndoProfileEdit(profile) },
                onRedo = { onRedoProfileEdit(profile) },
                onDelete = { onDeleteProfile(profile) },
                onToggle = { onToggleProfile(profile, it) },
                onAddContext = { onAddContext(profile) },
                onEditContextLogic = { onEditContextLogic(profile) },
                onEditContext = { index, context -> onEditContext(profile, index, context) },
                onDeleteContext = { index -> onDeleteContext(profile, index) },
                onPreflight = { onPreflightProfile(profile) },
            )
        }
    }
}

@Composable
private fun WorkspaceSummaryCard(
    profiles: List<Profile>,
    tasks: List<Task>,
    runLogs: List<RunLogEntry>,
    onBrowseTemplates: () -> Unit,
    onPreviewProfileShare: () -> Unit,
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundleText: () -> Unit,
    openTaskerBundleBusy: Boolean,
    onImportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
) {
    val enabledProfiles = profiles.count { it.enabled }
    val configuredContexts = profiles.sumOf { it.contexts.size }
    val totalActions = tasks.sumOf { it.actions.size }
    val recentFailure = runLogs.firstOrNull { !it.success }
    val reviewDetails = stringResource(R.string.workspace_review_run_log_details)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_automation_workspace), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.workspace_review_readiness_templates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    label = if (enabledProfiles > 0) stringResource(R.string.label_live_count, enabledProfiles) else stringResource(R.string.label_paused),
                    color = if (enabledProfiles > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                SummaryMetric("${profiles.size}", stringResource(R.string.label_profiles), Modifier.weight(1f))
                SummaryMetric("$configuredContexts", stringResource(R.string.label_contexts), Modifier.weight(1f))
                SummaryMetric("$totalActions", stringResource(R.string.label_actions), Modifier.weight(1f))
            }
            if (recentFailure != null) {
                InlineNotice(
                    title = stringResource(R.string.workspace_recent_failure),
                    body = stringResource(
                        R.string.workspace_recent_failure_detail,
                        recentFailure.taskName,
                        recentFailure.message.ifBlank { reviewDetails },
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onBrowseTemplates, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.workspace_templates))
                }
                OutlinedButton(
                    onClick = onImportTaskerXml,
                    enabled = !taskerImportBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (taskerImportBusy) stringResource(R.string.import_reading_xml) else stringResource(R.string.import_tasker))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onExportOpenTaskerBundle,
                    enabled = !openTaskerBundleBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (openTaskerBundleBusy) stringResource(R.string.action_working) else stringResource(R.string.import_export_json))
                }
                OutlinedButton(
                    onClick = onImportOpenTaskerBundle,
                    enabled = !openTaskerBundleBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (openTaskerBundleBusy) stringResource(R.string.import_reading_json) else stringResource(R.string.import_import_json))
                }
            }
            OutlinedButton(
                onClick = onImportOpenTaskerBundleText,
                enabled = !openTaskerBundleBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.import_paste_json_action))
            }
            OutlinedButton(
                onClick = onPreviewProfileShare,
                enabled = !openTaskerBundleBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (openTaskerBundleBusy) {
                        stringResource(R.string.profile_share_preparing)
                    } else {
                        stringResource(R.string.profile_share_preview_action)
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskLibrarySummaryCard(tasks: List<Task>, onCreateTask: () -> Unit) {
    val totalActions = tasks.sumOf { it.actions.size }
    val emptyTasks = tasks.count { it.actions.isEmpty() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_task_library), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.workspace_task_library_ready_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onCreateTask) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_new))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_task))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                SummaryMetric("${tasks.size}", stringResource(R.string.label_tasks), Modifier.weight(1f))
                SummaryMetric("$totalActions", stringResource(R.string.label_actions), Modifier.weight(1f))
                SummaryMetric("$emptyTasks", stringResource(R.string.label_need_actions), Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun StorageDecodeWarningCard(issues: List<StorageDecodeIssue>) {
    val issueSummary = issues.take(3).joinToString(separator = "; ") { issue ->
        "${issue.recordType.label} \"${issue.recordName}\" #${issue.recordId}: ${issue.fieldName}"
    }
    val remaining = issues.size - 3
    val suffix = if (remaining > 0) "; ${stringResource(R.string.label_more_count, remaining)}" else ""
    InlineNotice(
        title = stringResource(R.string.workspace_stored_data_review),
        body = stringResource(R.string.workspace_stored_data_review_body, issueSummary, suffix),
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun TemplatePromptCard(onBrowseTemplates: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.workspace_templates), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.workspace_templates_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onBrowseTemplates) {
                Text(stringResource(R.string.action_browse))
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    enterTaskName: String,
    onEdit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPreflight: () -> Unit,
    onAddContext: () -> Unit,
    onEditContextLogic: () -> Unit,
    onEditContext: (Int, ContextSpec) -> Unit,
    onDeleteContext: (Int) -> Unit,
) {
    val profileState = when {
        profile.requiresRiskAcknowledgement -> stringResource(R.string.imported_profile_review_required)
        profile.enabled -> stringResource(R.string.label_enabled)
        else -> stringResource(R.string.label_paused)
    }
    val toggleDescription = stringResource(R.string.a11y_profile_status, profile.name)
    val editDescription = stringResource(R.string.a11y_edit_profile, profile.name)
    val addContextDescription = stringResource(R.string.a11y_add_context_to_profile, profile.name)
    val editContextLogicDescription = stringResource(R.string.profile_edit_context_logic)
    val deleteDescription = stringResource(R.string.a11y_delete_profile, profile.name)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (profile.enabled) 0.72f else 0.46f),
        ),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.workspace_runs_task, enterTaskName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = profile.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = toggleDescription
                        stateDescription = profileState
                    },
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item {
                    StatusPill(
                        label = profileState,
                        color = when {
                            profile.requiresRiskAcknowledgement -> MaterialTheme.colorScheme.error
                            profile.enabled -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                item { StatusPill(stringResource(R.string.label_context_count, profile.contexts.size), MaterialTheme.colorScheme.primary) }
                item { StatusPill(stringResource(R.string.label_cooldown_seconds, profile.cooldownSec), MaterialTheme.colorScheme.secondary) }
                item { StatusPill(stringResource(R.string.label_profile_priority, profile.priority), MaterialTheme.colorScheme.primary) }
                if (profile.gracePeriodSec > 0) {
                    item { StatusPill(stringResource(R.string.label_profile_grace_seconds, profile.gracePeriodSec), MaterialTheme.colorScheme.secondary) }
                }
                item { StatusPill(profileLifetimeTitle(profile.lifetime), MaterialTheme.colorScheme.onSurfaceVariant) }
                if (profile.lifetime == com.opentasker.core.model.ProfileLifetime.UNTIL_DATE && profile.expiresAtMs != null) {
                    item {
                        StatusPill(
                            stringResource(R.string.label_profile_expiry_date, formatProfileExpiryDate(profile.expiresAtMs)),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { StatusPill(profile.automationMode.name.lowercase(), MaterialTheme.colorScheme.onSurfaceVariant) }
                profile.group?.let { group ->
                    item { StatusPill(group, MaterialTheme.colorScheme.inversePrimary) }
                }
            }
            if (profile.contexts.isEmpty()) {
                InlineNotice(
                    title = stringResource(R.string.workspace_profile_cannot_match),
                    body = stringResource(R.string.workspace_profile_cannot_match_body),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                profile.contexts.forEachIndexed { index, context ->
                    ContextRow(
                        index = index,
                        context = context,
                        onEdit = { onEditContext(index, context) },
                        onDelete = { onDeleteContext(index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = editDescription },
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = editDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(
                    onClick = onAddContext,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = addContextDescription },
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = addContextDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_add_context))
                }
                OutlinedButton(
                    onClick = onEditContextLogic,
                    enabled = profile.contexts.size >= 2,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = editContextLogicDescription },
                ) {
                    Text(stringResource(R.string.profile_edit_context_logic))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onPreflight) {
                    Text(stringResource(R.string.action_preflight))
                }
                TextButton(
                    onClick = onDelete,
                    modifier = Modifier.semantics { contentDescription = deleteDescription },
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = deleteDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_delete))
                }
            }
            HistoryButtons(onUndo = onUndo, onRedo = onRedo)
        }
    }
}

@Composable
internal fun TasksScreen(
    tasks: List<Task>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onUndoTaskEdit: (Task) -> Unit = {},
    onRedoTaskEdit: (Task) -> Unit = {},
    onDeleteTask: (Task) -> Unit,
    onRunTask: (Task) -> Unit,
    onPreflightTask: (Task) -> Unit,
    onPinTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
    onMoveAction: (Task, Int, Int) -> Unit,
    contentPadding: PaddingValues,
) {
    if (tasks.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.empty_tasks_title),
            body = stringResource(R.string.empty_tasks_create_body),
            actionLabel = stringResource(R.string.action_create_task),
            onAction = onCreateTask,
            contentPadding = contentPadding,
        )
        return
    }
    var taskSearchQuery by rememberSaveable { mutableStateOf("") }
    val filteredTasks = if (taskSearchQuery.isBlank()) tasks
        else tasks.filter { it.name.contains(taskSearchQuery, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        item {
            TaskLibrarySummaryCard(tasks = tasks, onCreateTask = onCreateTask)
        }
        if (storageDecodeIssues.isNotEmpty()) {
            item {
                StorageDecodeWarningCard(storageDecodeIssues)
            }
        }
        item {
            OutlinedTextField(
                value = taskSearchQuery,
                onValueChange = { taskSearchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.workspace_search_tasks)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.run_log_search_label)) },
                trailingIcon = if (taskSearchQuery.isNotEmpty()) {
                    { IconButton(onClick = { taskSearchQuery = "" }) { Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.variables_search_clear)) } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
            )
        }
        if (filteredTasks.isEmpty()) {
            item {
                InlineNotice(
                    title = stringResource(R.string.workspace_no_matching_tasks),
                    body = stringResource(R.string.workspace_no_matching_tasks_body),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        items(filteredTasks, key = { it.id }) { task ->
            TaskCard(
                task = task,
                onEdit = { onEditTask(task) },
                onUndo = { onUndoTaskEdit(task) },
                onRedo = { onRedoTaskEdit(task) },
                onDelete = { onDeleteTask(task) },
                onRun = { onRunTask(task) },
                onPreflight = { onPreflightTask(task) },
                onPin = { onPinTask(task) },
                onAddAction = { onAddAction(task) },
                onEditAction = { index, action -> onEditAction(task, index, action) },
                onDeleteAction = { index -> onDeleteAction(task, index) },
                onMoveAction = { fromIndex, toIndex -> onMoveAction(task, fromIndex, toIndex) },
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    onEdit: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onPreflight: () -> Unit,
    onPin: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
    onMoveAction: (Int, Int) -> Unit,
) {
    val editDescription = stringResource(R.string.a11y_edit_task, task.name)
    val addActionDescription = stringResource(R.string.a11y_add_action_to_task, task.name)
    val runDescription = stringResource(R.string.a11y_run_task, task.name)
    val pinDescription = stringResource(R.string.a11y_pin_task, task.name)
    val deleteDescription = stringResource(R.string.a11y_delete_task, task.name)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = runDescription,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(22.dp)
                            .clearAndSetSemantics { },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.workspace_task_priority, task.priority, task.collisionMode.name.lowercase().replace('_', ' ')),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onRun,
                    modifier = Modifier.semantics { contentDescription = runDescription },
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = runDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_run))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item { StatusPill(stringResource(R.string.label_action_count, task.actions.size), MaterialTheme.colorScheme.primary) }
                item { StatusPill(stringResource(R.string.label_priority_short, task.priority), MaterialTheme.colorScheme.secondary) }
                item { StatusPill(task.collisionMode.name.lowercase().replace('_', ' '), MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (task.actions.isEmpty()) {
                InlineNotice(
                    title = stringResource(R.string.workspace_task_has_no_actions),
                    body = stringResource(R.string.workspace_task_has_no_actions_body),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                task.actions.forEachIndexed { index, action ->
                    ActionRow(
                        index = index,
                        action = action,
                        canMoveUp = index > 0,
                        canMoveDown = index < task.actions.lastIndex,
                        onMoveUp = { onMoveAction(index, index - 1) },
                        onMoveDown = { onMoveAction(index, index + 1) },
                        onEdit = { onEditAction(index, action) },
                        onDelete = { onDeleteAction(index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = editDescription },
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = editDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(
                    onClick = onAddAction,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = addActionDescription },
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = addActionDescription,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.task_add_action))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedButton(onClick = onPreflight) {
                        Text(stringResource(R.string.action_preflight))
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onPin,
                        modifier = Modifier.semantics { contentDescription = pinDescription },
                    ) {
                        Icon(
                            Icons.Filled.PushPin,
                            contentDescription = pinDescription,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_pin))
                    }
                }
                item {
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.semantics { contentDescription = deleteDescription },
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = deleteDescription,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.task_delete))
                    }
                }
            }
            HistoryButtons(onUndo = onUndo, onRedo = onRedo)
        }
    }
}

@Composable
private fun HistoryButtons(
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
    ) {
        OutlinedButton(onClick = onUndo, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_undo))
        }
        OutlinedButton(onClick = onRedo, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.action_redo))
        }
    }
}

@Composable
private fun ActionRow(
    index: Int,
    action: ActionSpec,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    val metadataName = metadata?.let { stringResource(it.nameRes) }
    val metadataDescription = metadata?.let { stringResource(it.descriptionRes) }
    val actionLabel = action.label ?: metadataName ?: action.type
    val editDescription = stringResource(R.string.a11y_edit_action, index + 1, actionLabel)
    val deleteDescription = stringResource(R.string.a11y_delete_action, index + 1, actionLabel)
    val moveUpDescription = stringResource(R.string.a11y_move_action_up, index + 1, actionLabel)
    val moveDownDescription = stringResource(R.string.a11y_move_action_down, index + 1, actionLabel)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            StatusPill("#${index + 1}", MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(actionLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    ActionArgumentSensitivity.summarize(action.type, action.args)
                        .ifBlank { metadataDescription ?: stringResource(R.string.workspace_no_arguments) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (capability.level != CapabilityLevel.Supported) {
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        if (capability.level == CapabilityLevel.Unsupported) stringResource(R.string.label_unsupported) else stringResource(R.string.status_needs_setup),
                        if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                action.condition?.takeIf(String::isNotBlank)?.let { condition ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.action_condition_summary, condition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (action.continueOnError) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.action_continue_on_error_summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.semantics { contentDescription = moveUpDescription },
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = moveUpDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.semantics { contentDescription = moveDownDescription },
            ) {
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = moveDownDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editDescription },
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = editDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteDescription },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = deleteDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ContextRow(
    index: Int,
    context: ContextSpec,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val contextTypeLabel = stringResource(contextTitleRes(context.type))
    val editDescription = stringResource(R.string.a11y_edit_context, index + 1, contextTypeLabel)
    val deleteDescription = stringResource(R.string.a11y_delete_context, index + 1, contextTypeLabel)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(contextTypeLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    contextConfigSummary(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (context.invert) {
                StatusPill(stringResource(R.string.label_inverted), MaterialTheme.colorScheme.secondary)
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics { contentDescription = editDescription },
            ) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = editDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = deleteDescription },
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = deleteDescription,
                    modifier = Modifier.clearAndSetSemantics { },
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
