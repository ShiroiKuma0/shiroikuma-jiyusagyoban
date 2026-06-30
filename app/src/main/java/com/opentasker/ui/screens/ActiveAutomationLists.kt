package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.ProjectFilter
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.core.storage.StorageDecodeIssue
import com.opentasker.ui.components.ConfirmDeleteSelected
import com.opentasker.ui.components.GroupMoveDialogs
import com.opentasker.ui.components.GroupOps
import com.opentasker.ui.components.ItemNoteSection
import com.opentasker.ui.components.SelectionBar
import com.opentasker.ui.components.SelectionCheck
import com.opentasker.ui.components.ThemedDropdownMenu
import com.opentasker.ui.components.groupedItems
import com.opentasker.ui.components.rememberGroupDragState
import com.opentasker.ui.components.rememberGroupMoveHost
import com.opentasker.ui.components.selectableItem
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/** A pinned filter-chip row that picks the active project (All / Unfiled / a specific project). */
@Composable
internal fun ProjectFilterChips(
    projects: List<Project>,
    filter: ProjectFilter,
    onSelect: (ProjectFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item {
            FilterChip(
                selected = filter == ProjectFilter.All,
                onClick = { onSelect(ProjectFilter.All) },
                label = { Text(stringResource(R.string.label_all)) },
            )
        }
        item {
            FilterChip(
                selected = filter == ProjectFilter.Unfiled,
                onClick = { onSelect(ProjectFilter.Unfiled) },
                label = { Text("Unfiled") },
            )
        }
        items(projects, key = { it.id }) { project ->
            FilterChip(
                selected = filter is ProjectFilter.Of && filter.projectId == project.id,
                onClick = { onSelect(ProjectFilter.Of(project.id)) },
                label = { Text(project.name) },
            )
        }
    }
}

@Composable
internal fun ProfilesScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    expandedProfiles: SnapshotStateMap<Long, Boolean>,
    runLogs: List<RunLogEntry>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    projects: List<Project>,
    projectFilter: ProjectFilter,
    currentProjectId: Long?,
    onSelectProject: (ProjectFilter) -> Unit,
    groupOps: GroupOps,
    onMoveProfilesToProject: (List<Profile>, Long?) -> Unit,
    onDeleteProfiles: (List<Profile>) -> Unit,
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
    loaded: Boolean,
) {
    // Item + group multi-selection live here (re-mounting the screen on a tab switch resets them). Set<Long>
    // has no Saver, so plain remember — not rememberSaveable — is correct.
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var confirmDeleteItems by remember { mutableStateOf(false) }
    var confirmDeleteGroups by remember { mutableStateOf(false) }
    val selectionActive = selectedIds.isNotEmpty()
    val groupSelectionActive = selectedGroupIds.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (projects.isNotEmpty()) {
            ProjectFilterChips(projects, projectFilter, onSelectProject, Modifier.padding(vertical = 8.dp))
        }
        if (selectionActive) {
            SelectionBar(
                count = selectedIds.size,
                total = profiles.size,
                onSelectAll = { selectedIds = profiles.map { it.id }.toSet() },
                onClear = { selectedIds = emptySet() },
                onDelete = { confirmDeleteItems = true },
                onMoveToProject = if (projects.isNotEmpty()) ({ showMoveDialog = true }) else null,
            )
        }
        if (groupSelectionActive) {
            SelectionBar(
                count = selectedGroupIds.size,
                total = groupOps.groups.size,
                onSelectAll = { selectedGroupIds = groupOps.groups.map { it.id }.toSet() },
                onClear = { selectedGroupIds = emptySet() },
                onDelete = { confirmDeleteGroups = true },
                noun = "groups",
            )
        }
        when {
            !loaded -> Box(Modifier.weight(1f)) {}  // brief blank during initial DB load — no empty-state flash
            tasks.isEmpty() -> Box(Modifier.weight(1f)) {
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
                    contentPadding = PaddingValues(0.dp),
                )
            }
            profiles.isEmpty() -> Box(Modifier.weight(1f)) {
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
                    contentPadding = PaddingValues(0.dp),
                )
            }
            else -> {
                var profileSearchQuery by rememberSaveable { mutableStateOf("") }
                var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
                val stringGroups = remember(profiles) { profiles.mapNotNull { it.group }.distinct().sorted() }
                val filteredProfiles = profiles
                    .filter { selectedGroup == null || it.group == selectedGroup }
                    .filter { profileSearchQuery.isBlank() || it.name.contains(profileSearchQuery, ignoreCase = true) }
                val moveHost = rememberGroupMoveHost()
                val dragState = rememberGroupDragState()
                val profileCard: @Composable (Profile) -> Unit = { profile ->
                    val enterTaskName = tasks.firstOrNull { it.id == profile.enterTaskId }?.name
                        ?: stringResource(R.string.workspace_missing_task, profile.enterTaskId)
                    ProfileCard(
                        profile = profile,
                        enterTaskName = enterTaskName,
                        selectionActive = selectionActive,
                        selected = profile.id in selectedIds,
                        expanded = expandedProfiles[profile.id] == true,
                        onToggleExpanded = { expandedProfiles[profile.id] = expandedProfiles[profile.id] != true },
                        onLongPress = { selectedIds = selectedIds + profile.id },
                        onToggleSelect = { selectedIds = if (profile.id in selectedIds) selectedIds - profile.id else selectedIds + profile.id },
                        onEdit = { onEditProfile(profile) },
                        onDelete = { onDeleteProfile(profile) },
                        onToggle = { onToggleProfile(profile, it) },
                        onAddContext = { onAddContext(profile) },
                        onEditContext = { index, context -> onEditContext(profile, index, context) },
                        onDeleteContext = { index -> onDeleteContext(profile, index) },
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    // Reserve clearance for the bottom-right "+" FAB so the last row is never hidden under it.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
                ) {
                    if (storageDecodeIssues.isNotEmpty()) {
                        item { StorageDecodeWarningCard(storageDecodeIssues) }
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
                    if (stringGroups.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                                item {
                                    FilterChip(
                                        selected = selectedGroup == null,
                                        onClick = { selectedGroup = null },
                                        label = { Text(stringResource(R.string.label_all)) },
                                    )
                                }
                                items(stringGroups, key = { it }) { group ->
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
                    if (groupOps.groups.isEmpty()) {
                        items(filteredProfiles, key = { it.id }) { profile -> profileCard(profile) }
                    } else {
                        groupedItems(
                            filteredProfiles, { it.id.toString() }, groupOps, dragState,
                            onMoveItem = { moveHost.movingItemKey = it },
                            onMoveGroup = { moveHost.movingGroup = it },
                            selectedGroupIds = selectedGroupIds,
                            onLongPressGroup = { selectedGroupIds = selectedGroupIds + it.id },
                            onToggleSelectGroup = { g -> selectedGroupIds = if (g.id in selectedGroupIds) selectedGroupIds - g.id else selectedGroupIds + g.id },
                        ) { profile -> profileCard(profile) }
                    }
                }
                GroupMoveDialogs(groupOps, moveHost)
            }
        }
    }

    if (showMoveDialog) {
        ProjectPickerDialog(
            title = "Move ${selectedIds.size} profile${plural(selectedIds.size)}",
            projects = projects,
            currentProjectId = currentProjectId,
            onPick = { pid ->
                onMoveProfilesToProject(profiles.filter { it.id in selectedIds }, pid)
                selectedIds = emptySet()
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }
    if (confirmDeleteItems) {
        ConfirmDeleteSelected(
            count = selectedIds.size,
            noun = "profile",
            onConfirm = {
                onDeleteProfiles(profiles.filter { it.id in selectedIds })
                selectedIds = emptySet()
                confirmDeleteItems = false
            },
            onDismiss = { confirmDeleteItems = false },
        )
    }
    if (confirmDeleteGroups) {
        ConfirmDeleteSelected(
            count = selectedGroupIds.size,
            noun = "group",
            onConfirm = {
                groupOps.groups.filter { it.id in selectedGroupIds }.forEach { groupOps.deleteGroup(it) }
                selectedGroupIds = emptySet()
                confirmDeleteGroups = false
            },
            onDismiss = { confirmDeleteGroups = false },
        )
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
internal fun TaskLibrarySummaryCard(tasks: List<Task>, onCreateTask: () -> Unit) {
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
internal fun TemplatePromptCard(onBrowseTemplates: () -> Unit) {
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
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
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
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (profile.enabled) 0.72f else 0.46f),
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            when {
                selected -> MaterialTheme.colorScheme.primary
                profile.enabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
            },
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableItem(
                    selectionActive = selectionActive,
                    onLongPress = onLongPress,
                    onToggleSelect = onToggleSelect,
                    onTapNormal = onToggleExpanded,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Collapsed: name + a one-line status summary (state + context count). Expanded: just the task.
                    val statusWord = if (profile.enabled) stringResource(R.string.label_enabled) else stringResource(R.string.label_paused)
                    val contextsText = stringResource(R.string.label_context_count, profile.contexts.size)
                    Text(
                        text = stringResource(R.string.workspace_runs_task, enterTaskName) +
                            if (!expanded) " - $statusWord, $contextsText" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = profile.enabled, onCheckedChange = onToggle)
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse profile" else "Expand profile",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                ItemNoteSection("profiles", profile.id.toString())
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
}

@Composable
internal fun TasksScreen(
    tasks: List<Task>,
    expandedTasks: SnapshotStateMap<Long, Boolean>,
    storageDecodeIssues: List<StorageDecodeIssue>,
    projects: List<Project>,
    projectFilter: ProjectFilter,
    currentProjectId: Long?,
    onSelectProject: (ProjectFilter) -> Unit,
    groupOps: GroupOps,
    onMoveTasksToProject: (List<Task>, Long?) -> Unit,
    onDeleteTasks: (List<Task>) -> Unit,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onRunTask: (Task) -> Unit,
    onSetTaskFreeze: (Task, Boolean) -> Unit,
    onPinTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
    onApplyActions: (Task, List<ActionSpec>) -> Unit,
    onPickTaskIcon: (Task) -> Unit,
    contentPadding: PaddingValues,
    loaded: Boolean,
) {
    val themePrefs by ThemeStore.state.collectAsState()
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var confirmDeleteItems by remember { mutableStateOf(false) }
    var confirmDeleteGroups by remember { mutableStateOf(false) }
    val selectionActive = selectedIds.isNotEmpty()
    val groupSelectionActive = selectedGroupIds.isNotEmpty()

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (projects.isNotEmpty()) {
            ProjectFilterChips(projects, projectFilter, onSelectProject, Modifier.padding(vertical = 8.dp))
        }
        if (selectionActive) {
            SelectionBar(
                count = selectedIds.size,
                total = tasks.size,
                onSelectAll = { selectedIds = tasks.map { it.id }.toSet() },
                onClear = { selectedIds = emptySet() },
                onDelete = { confirmDeleteItems = true },
                onMoveToProject = if (projects.isNotEmpty()) ({ showMoveDialog = true }) else null,
            )
        }
        if (groupSelectionActive) {
            SelectionBar(
                count = selectedGroupIds.size,
                total = groupOps.groups.size,
                onSelectAll = { selectedGroupIds = groupOps.groups.map { it.id }.toSet() },
                onClear = { selectedGroupIds = emptySet() },
                onDelete = { confirmDeleteGroups = true },
                noun = "groups",
            )
        }
        if (!loaded) {
            Box(Modifier.weight(1f)) {}  // brief blank during initial DB load — no empty-state flash
        } else if (tasks.isEmpty()) {
            Box(Modifier.weight(1f)) {
                EmptyState(
                    title = stringResource(R.string.empty_tasks_title),
                    body = stringResource(R.string.empty_tasks_create_body),
                    actionLabel = stringResource(R.string.action_create_task),
                    onAction = onCreateTask,
                    contentPadding = PaddingValues(0.dp),
                )
            }
        } else {
            var taskSearchQuery by rememberSaveable { mutableStateOf("") }
            val filteredTasks = if (taskSearchQuery.isBlank()) tasks
                else tasks.filter { it.name.contains(taskSearchQuery, ignoreCase = true) }
            val moveHost = rememberGroupMoveHost()
            val dragState = rememberGroupDragState()
            val taskCard: @Composable (Task) -> Unit = { task ->
                TaskCard(
                    task = task,
                    selectionActive = selectionActive,
                    selected = task.id in selectedIds,
                    expanded = expandedTasks[task.id] == true,
                    onToggleExpanded = { expandedTasks[task.id] = expandedTasks[task.id] != true },
                    onLongPress = { selectedIds = selectedIds + task.id },
                    onToggleSelect = { selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id },
                    onEdit = { onEditTask(task) },
                    onDelete = { onDeleteTask(task) },
                    onRun = { onRunTask(task) },
                    onToggleFreeze = { onSetTaskFreeze(task, it) },
                    onPin = { onPinTask(task) },
                    onAddAction = { onAddAction(task) },
                    onEditAction = { index, action -> onEditAction(task, index, action) },
                    onDeleteAction = { index -> onDeleteAction(task, index) },
                    onApplyActions = { newActions -> onApplyActions(task, newActions) },
                    onPickIcon = { onPickTaskIcon(task) },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                // Reserve clearance for the bottom-right "+" FAB so the last row is never hidden under it.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(themePrefs.taskCardGapDp.dp),
            ) {
                if (storageDecodeIssues.isNotEmpty()) {
                    item { StorageDecodeWarningCard(storageDecodeIssues) }
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
                if (groupOps.groups.isEmpty()) {
                    items(filteredTasks, key = { it.id }) { task -> taskCard(task) }
                } else {
                    groupedItems(
                        filteredTasks, { it.id.toString() }, groupOps, dragState,
                        onMoveItem = { moveHost.movingItemKey = it },
                        onMoveGroup = { moveHost.movingGroup = it },
                        selectedGroupIds = selectedGroupIds,
                        onLongPressGroup = { selectedGroupIds = selectedGroupIds + it.id },
                        onToggleSelectGroup = { g -> selectedGroupIds = if (g.id in selectedGroupIds) selectedGroupIds - g.id else selectedGroupIds + g.id },
                    ) { task -> taskCard(task) }
                }
            }
            GroupMoveDialogs(groupOps, moveHost)
        }
    }

    if (showMoveDialog) {
        ProjectPickerDialog(
            title = "Move ${selectedIds.size} task${plural(selectedIds.size)}",
            projects = projects,
            currentProjectId = currentProjectId,
            onPick = { pid ->
                onMoveTasksToProject(tasks.filter { it.id in selectedIds }, pid)
                selectedIds = emptySet()
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }
    if (confirmDeleteItems) {
        ConfirmDeleteSelected(
            count = selectedIds.size,
            noun = "task",
            onConfirm = {
                onDeleteTasks(tasks.filter { it.id in selectedIds })
                selectedIds = emptySet()
                confirmDeleteItems = false
            },
            onDismiss = { confirmDeleteItems = false },
        )
    }
    if (confirmDeleteGroups) {
        ConfirmDeleteSelected(
            count = selectedGroupIds.size,
            noun = "group",
            onConfirm = {
                groupOps.groups.filter { it.id in selectedGroupIds }.forEach { groupOps.deleteGroup(it) }
                selectedGroupIds = emptySet()
                confirmDeleteGroups = false
            },
            onDismiss = { confirmDeleteGroups = false },
        )
    }
}

/**
 * App-wide clipboard for task actions — Copy/Cut from one task's action list and Paste into another (or
 * the same) task. Holds immutable [ActionSpec] copies and survives across cards while the app is open.
 */
object ActionClipboard {
    private val _actions = MutableStateFlow<List<ActionSpec>>(emptyList())
    val actions: StateFlow<List<ActionSpec>> = _actions.asStateFlow()
    fun put(items: List<ActionSpec>) { _actions.value = items.map { it.copy() } }
}

@Composable
private fun TaskCard(
    task: Task,
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    onToggleFreeze: (Boolean) -> Unit,
    onPin: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
    onApplyActions: (List<ActionSpec>) -> Unit,
    onPickIcon: () -> Unit,
) {
    // Action multi-select + clipboard: long-press a row to select it (and open its menu); the menu's
    // Clone/Copy/Cut/Delete act on the whole selection, Paste drops the clipboard before/after that row.
    var selectedActions by remember(task.id) { mutableStateOf<Set<Int>>(emptySet()) }
    var actionMenuIndex by remember(task.id) { mutableStateOf<Int?>(null) }
    val clipboard by ActionClipboard.actions.collectAsState()
    val themePrefs by ThemeStore.state.collectAsState()
    val actionSelectionActive = selectedActions.isNotEmpty()
    // Apply a new action list (persists via onApplyActions) and reset the selection/menu.
    val applyActions: (List<ActionSpec>) -> Unit = { newActions ->
        onApplyActions(newActions); selectedActions = emptySet(); actionMenuIndex = null
    }
    // The indices to act on: the current selection, or just the long-pressed row if nothing is selected.
    val targetActions: (Int) -> List<Int> = { i ->
        (if (selectedActions.isEmpty()) listOf(i) else selectedActions.toList()).sorted().filter { it in task.actions.indices }
    }
    val listIcon = remember(task.iconPath) { TaskIconStore.loadBitmap(task.iconPath) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = themePrefs.taskCardVPadDp.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableItem(
                    selectionActive = selectionActive,
                    onLongPress = onLongPress,
                    onToggleSelect = onToggleSelect,
                    onTapNormal = onToggleExpanded,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                    Spacer(Modifier.width(8.dp))
                } else {
                    // Run sits on the LEFT, CENTERED in the leading gutter so it has equal whitespace on both
                    // sides (白い熊): a 32dp box centers the icon at card-pad + 16dp, then a 16dp spacer keeps
                    // the app icon at its original indent.
                    Box(
                        modifier = Modifier.size(width = 32.dp, height = 40.dp).clickable(onClick = onRun),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.action_run),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    if (listIcon != null) {
                        Image(
                            bitmap = listIcon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPickIcon() },
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        // No icon yet → a tappable "add icon" affordance (thin outline + small +).
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                                .clickable { onPickIcon() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add task icon",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Collapsed: just the task name. Expanded: the priority / collision line.
                    if (expanded) {
                        Text(
                            text = stringResource(R.string.workspace_task_priority, task.priority, task.collisionMode.name.lowercase().replace('_', ' ')),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse task" else "Expand task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
            ItemNoteSection("tasks", task.id.toString())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                item { StatusPill(stringResource(R.string.label_action_count, task.actions.size), MaterialTheme.colorScheme.primary) }
                item { StatusPill(stringResource(R.string.label_priority_short, task.priority), MaterialTheme.colorScheme.secondary) }
                item { StatusPill(task.collisionMode.name.lowercase().replace('_', ' '), MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // Freeze bubble toggle — editable inline on the card without opening the editor (白い熊).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Freeze bubble", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = task.freezeBubble, onCheckedChange = onToggleFreeze)
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
                        selected = index in selectedActions,
                        selectionActive = actionSelectionActive,
                        menuExpanded = actionMenuIndex == index,
                        clipboardEmpty = clipboard.isEmpty(),
                        onTap = {
                            if (actionSelectionActive) {
                                selectedActions = if (index in selectedActions) selectedActions - index else selectedActions + index
                            }
                        },
                        onLongPress = { selectedActions = selectedActions + index; actionMenuIndex = index },
                        onMenuDismiss = { actionMenuIndex = null },
                        onClone = {
                            val sel = targetActions(index)
                            val copies = sel.map { task.actions[it].copy() }
                            applyActions(task.actions.toMutableList().apply { addAll((sel.maxOrNull() ?: index) + 1, copies) })
                        },
                        onCopy = {
                            ActionClipboard.put(targetActions(index).map { task.actions[it] })
                            selectedActions = emptySet(); actionMenuIndex = null
                        },
                        onCut = {
                            val sel = targetActions(index).toSet()
                            ActionClipboard.put(sel.sorted().map { task.actions[it] })
                            applyActions(task.actions.filterIndexed { i, _ -> i !in sel })
                        },
                        onDeleteSelection = {
                            val sel = targetActions(index).toSet()
                            applyActions(task.actions.filterIndexed { i, _ -> i !in sel })
                        },
                        onPasteBefore = { applyActions(task.actions.toMutableList().apply { addAll(index, clipboard) }) },
                        onPaste = { applyActions(task.actions.toMutableList().apply { addAll(index + 1, clipboard) }) },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionRow(
    index: Int,
    action: ActionSpec,
    selected: Boolean,
    selectionActive: Boolean,
    menuExpanded: Boolean,
    clipboardEmpty: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onMenuDismiss: () -> Unit,
    onClone: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDeleteSelection: () -> Unit,
    onPasteBefore: () -> Unit,
    onPaste: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
        ),
    ) {
        Box {
            Row(
                // Tap selects (while selecting); long-press selects + opens the clone/copy/cut/paste menu.
                // The Edit/Delete icon buttons keep their own taps (children intercept the combined click).
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                if (selectionActive && selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.label_selected),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    StatusPill("#${index + 1}", MaterialTheme.colorScheme.secondary)
                }
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
            // Long-press menu — Clone/Copy/Cut/Delete act on the whole selection; Paste drops the
            // clipboard relative to this action (shown only when something has been copied/cut).
            ThemedDropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                DropdownMenuItem(text = { Text("Clone") }, onClick = onClone)
                DropdownMenuItem(text = { Text("Copy") }, onClick = onCopy)
                DropdownMenuItem(text = { Text("Cut") }, onClick = onCut)
                DropdownMenuItem(text = { Text("Delete") }, onClick = onDeleteSelection)
                if (!clipboardEmpty) {
                    DropdownMenuItem(text = { Text("Paste before") }, onClick = onPasteBefore)
                    DropdownMenuItem(text = { Text("Paste after") }, onClick = onPaste)
                }
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
