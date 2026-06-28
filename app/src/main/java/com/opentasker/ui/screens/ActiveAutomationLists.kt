package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
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
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
    openTaskerBundleBusy: Boolean,
    onImportTaskerXml: () -> Unit,
    taskerImportBusy: Boolean,
    onEditProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onToggleProfile: (Profile, Boolean) -> Unit,
    onAddContext: (Profile) -> Unit,
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
                onExportOpenTaskerBundle = onExportOpenTaskerBundle,
                onImportOpenTaskerBundle = onImportOpenTaskerBundle,
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
                onDelete = { onDeleteProfile(profile) },
                onToggle = { onToggleProfile(profile, it) },
                onAddContext = { onAddContext(profile) },
                onEditContext = { index, context -> onEditContext(profile, index, context) },
                onDeleteContext = { index -> onDeleteContext(profile, index) },
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
    onExportOpenTaskerBundle: () -> Unit,
    onImportOpenTaskerBundle: () -> Unit,
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
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
                    body = "${recentFailure.taskName}: ${recentFailure.message.ifBlank { reviewDetails }}",
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
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
private fun StorageDecodeWarningCard(issues: List<StorageDecodeIssue>) {
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        shape = RoundedCornerShape(16.dp),
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
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAddContext: () -> Unit,
    onEditContext: (Int, ContextSpec) -> Unit,
    onDeleteContext: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (profile.enabled) 0.72f else 0.46f),
        ),
        border = BorderStroke(
            1.dp,
            if (profile.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.workspace_runs_task, enterTaskName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = profile.enabled, onCheckedChange = onToggle)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item {
                    StatusPill(
                        label = if (profile.enabled) stringResource(R.string.label_enabled) else stringResource(R.string.label_paused),
                        color = if (profile.enabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item { StatusPill(stringResource(R.string.label_context_count, profile.contexts.size), MaterialTheme.colorScheme.primary) }
                item { StatusPill(stringResource(R.string.label_cooldown_seconds, profile.cooldownSec), MaterialTheme.colorScheme.secondary) }
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
                        context = context,
                        onEdit = { onEditContext(index, context) },
                        onDelete = { onDeleteContext(index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(onClick = onAddContext, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.profile_add_context))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_add_context))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.profile_delete))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.profile_delete))
                }
            }
        }
    }
}

@Composable
internal fun TasksScreen(
    tasks: List<Task>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onRunTask: (Task) -> Unit,
    onPinTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
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
                onDelete = { onDeleteTask(task) },
                onRun = { onRunTask(task) },
                onPin = { onPinTask(task) },
                onAddAction = { onAddAction(task) },
                onEditAction = { index, action -> onEditAction(task, index, action) },
                onDeleteAction = { index -> onDeleteAction(task, index) },
            )
        }
    }
}

@Composable
private fun TaskCard(
    task: Task,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onPin: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.workspace_task_priority, task.priority, task.collisionMode.name.lowercase().replace('_', ' ')),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        onEdit = { onEditAction(index, action) },
                        onDelete = { onDeleteAction(index) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(onClick = onAddAction, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_add_action))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.task_add_action))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedButton(onClick = onRun) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.action_run))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_run))
                    }
                }
                item {
                    OutlinedButton(onClick = onPin) {
                        Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.action_pin))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_pin))
                    }
                }
                item {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.task_delete))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.task_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    index: Int,
    action: ActionSpec,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            StatusPill("#${index + 1}", MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f)) {
                Text(action.label ?: metadata?.name ?: action.type, style = MaterialTheme.typography.titleSmall)
                Text(
                    action.args.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { metadata?.description ?: stringResource(R.string.workspace_no_arguments) },
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
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ContextRow(
    context: ContextSpec,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Column(Modifier.weight(1f)) {
                Text(context.type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
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
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
