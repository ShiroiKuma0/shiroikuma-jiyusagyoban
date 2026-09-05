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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
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
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.rememberCoroutineScope
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.storage.ItemMetaEntity
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionMetadataRegistry
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel
import com.opentasker.core.capabilities.CapabilityRequirement
import com.opentasker.core.capabilities.CapabilityState
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
import com.opentasker.ui.theme.isNarrowScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.opentasker.core.engine.RunLogOutcome
import com.opentasker.core.engine.SingleActionRun
import com.opentasker.core.engine.outcome
/**
 * Increments every time the activity resumes — remember() permission/Shizuku checks against this so
 * they re-evaluate after the user returns from a settings screen or the Shizuku grant dialog.
 */
@Composable
internal fun rememberResumeTick(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return tick
}

/** A chip label with the optional red ❗ health mark in front. */
@Composable
private fun ChipLabelWithAlert(text: String, alert: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (alert) HealthAlertIcon(size = 16.dp)
        Text(text)
    }
}

/** A small red ❗ marking a blocked task / project chip / nav tab — the workspace-health mark. */
@Composable
internal fun HealthAlertIcon(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    Icon(
        Icons.Filled.Error,
        contentDescription = "Contains blocked tasks",
        tint = MaterialTheme.colorScheme.error,
        modifier = modifier.size(size),
    )
}

/** A pinned filter-chip row that picks the active project (All / Unfiled / a specific project). */
@Composable
internal fun ProjectFilterChips(
    projects: List<Project>,
    filter: ProjectFilter,
    onSelect: (ProjectFilter) -> Unit,
    onReorder: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
    // Project ids (null = Unfiled) containing a blocked task — those chips get the red ❗ so broken
    // automations are visible from the top level without opening anything. Empty = no marks.
    alertProjectIds: Set<Long?> = emptySet(),
) {
    // Tap a chip to filter; LONG-PRESS a project chip then drag left/right to reorder — it swaps with its
    // neighbour once dragged past half that neighbour's width, and the order persists on drop (switching the
    // Projects sort to Manual). All / Unfiled stay pinned at the front. 白い熊
    val order = remember(projects) { mutableStateListOf<Project>().also { it.addAll(projects) } }
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val widths = remember { mutableStateMapOf<Long, Float>() }
    val spacingPx = with(LocalDensity.current) { DesignSystem.Spacing.sm.toPx() }
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item {
            FilterChip(
                selected = filter == ProjectFilter.All,
                onClick = { onSelect(ProjectFilter.All) },
                label = { ChipLabelWithAlert(stringResource(R.string.label_all), alertProjectIds.isNotEmpty()) },
            )
        }
        item {
            FilterChip(
                selected = filter == ProjectFilter.Unfiled,
                onClick = { onSelect(ProjectFilter.Unfiled) },
                label = { ChipLabelWithAlert("Unfiled", null in alertProjectIds) },
            )
        }
        items(order, key = { it.id }) { project ->
            val isDragging = project.id == dragId
            FilterChip(
                selected = filter is ProjectFilter.Of && filter.projectId == project.id,
                onClick = { onSelect(ProjectFilter.Of(project.id)) },
                label = { ChipLabelWithAlert(project.name, project.id in alertProjectIds) },
                modifier = Modifier
                    .onGloballyPositioned { widths[project.id] = it.size.width.toFloat() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (isDragging) dragOffsetX else 0f
                        if (isDragging) { shadowElevation = 12f; alpha = 0.95f }
                    }
                    .pointerInput(project.id) {
                        detectDragGesturesAfterLongPress(
                            // Haptic the instant the long-press latches → "grabbed, you can drag now". 白い熊
                            onDragStart = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); dragId = project.id; dragOffsetX = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetX += amount.x
                                val di = order.indexOfFirst { it.id == dragId }
                                if (di in order.indices) {
                                    if (dragOffsetX > 0 && di < order.lastIndex) {
                                        val nw = widths[order[di + 1].id]
                                        if (nw != null) {
                                            val step = nw + spacingPx
                                            if (dragOffsetX > step / 2) { order.add(di + 1, order.removeAt(di)); dragOffsetX -= step }
                                        }
                                    } else if (dragOffsetX < 0 && di > 0) {
                                        val nw = widths[order[di - 1].id]
                                        if (nw != null) {
                                            val step = nw + spacingPx
                                            if (dragOffsetX < -step / 2) { order.add(di - 1, order.removeAt(di)); dragOffsetX += step }
                                        }
                                    }
                                }
                            },
                            onDragEnd = { onReorder(order.map { it.id }); dragId = null; dragOffsetX = 0f },
                            onDragCancel = { dragId = null; dragOffsetX = 0f },
                        )
                    },
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
    onReorderProjects: (List<Long>) -> Unit,
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
    // Workspace-health marks (from ActiveAutomationUi): profiles whose enter/exit task is blocked get
    // the red ❗ on their rows; project ids (null = Unfiled) with such profiles get it on their chips.
    brokenProfileIds: Set<Long> = emptySet(),
    alertProjectIds: Set<Long?> = emptySet(),
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
            ProjectFilterChips(projects, projectFilter, onSelectProject, onReorderProjects, Modifier.padding(vertical = 8.dp), alertProjectIds)
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
                        broken = profile.id in brokenProfileIds,
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
                    // Narrow (folded cover): shrink the side gutters — width is precious there.
                    contentPadding = PaddingValues(
                        start = if (isNarrowScreen()) 6.dp else 16.dp,
                        end = if (isNarrowScreen()) 6.dp else 16.dp,
                        top = 4.dp, bottom = 88.dp,
                    ),
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
                            onReorder = { movedKey, gid, ordered -> groupOps.reorder(movedKey, gid, ordered) },
                            onReorderGroups = { ordered -> groupOps.reorderGroups(ordered) },
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
    // outcome(), not the raw success flag — a Skipped or Cancelled run is stored with success = false
    // but neither is a failure, and the workspace card must not cry wolf over a skipped slider fire.
    val recentFailure = runLogs.firstOrNull { it.outcome() == RunLogOutcome.Failed }
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
    broken: Boolean,
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (broken) HealthAlertIcon()
                        Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
    onReorderProjects: (List<Long>) -> Unit,
    groupOps: GroupOps,
    onMoveTasksToProject: (List<Task>, Long?) -> Unit,
    onDeleteTasks: (List<Task>) -> Unit,
    onCreateTask: () -> Unit,
    onEditTask: (Task) -> Unit,
    onRenameTask: (Task, String) -> Unit,
    onDuplicateTasks: (List<Task>) -> Unit,
    onPasteTasks: (List<Task>, Long?, List<Long>) -> Unit,
    onDeleteTask: (Task) -> Unit,
    onRunTask: (Task) -> Unit,
    // True while a manual run or a held replay is in flight. Greys out every Run arrow, because the
    // guard behind onRunTask drops a second run silently — an arrow that still looks live but does
    // nothing is worse than one that plainly says "not now".
    runBusy: Boolean = false,
    onSetTaskFreeze: (Task, Boolean) -> Unit,
    onPinTask: (Task) -> Unit,
    onAddAction: (Task) -> Unit,
    onEditAction: (Task, Int, ActionSpec) -> Unit,
    onDeleteAction: (Task, Int) -> Unit,
    onRunAction: (Task, Int) -> Unit,
    onApplyActions: (Task, List<ActionSpec>) -> Unit,
    onPickTaskIcon: (Task) -> Unit,
    contentPadding: PaddingValues,
    loaded: Boolean,
    // Workspace-health marks (computed over ALL tasks in ActiveAutomationUi, not just the filtered
    // subset shown here): blocked task ids → red ❗ on their rows; project ids → ❗ on their chips.
    brokenTaskIds: Set<Long> = emptySet(),
    alertProjectIds: Set<Long?> = emptySet(),
) {
    val themePrefs by ThemeStore.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var confirmDeleteItems by remember { mutableStateOf(false) }
    var confirmDeleteGroups by remember { mutableStateOf(false) }
    val selectionActive = selectedIds.isNotEmpty()
    val groupSelectionActive = selectedGroupIds.isNotEmpty()

    Column(
        Modifier.fillMaxSize().padding(contentPadding)
            // A tap on empty space (outside any card) clears text-field focus, closing an open inline edit.
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        if (projects.isNotEmpty()) {
            ProjectFilterChips(projects, projectFilter, onSelectProject, onReorderProjects, Modifier.padding(vertical = 8.dp), alertProjectIds)
        }
        if (selectionActive) {
            val clipboardTasks by TaskClipboard.tasks.collectAsState()
            SelectionBar(
                count = selectedIds.size,
                total = tasks.size,
                onSelectAll = { selectedIds = tasks.map { it.id }.toSet() },
                onClear = { selectedIds = emptySet() },
                onDelete = { confirmDeleteItems = true },
                onMoveToProject = if (projects.isNotEmpty()) ({ showMoveDialog = true }) else null,
                onClone = { onDuplicateTasks(tasks.filter { it.id in selectedIds }); selectedIds = emptySet() },
                onCopy = { TaskClipboard.copy(tasks.filter { it.id in selectedIds }); selectedIds = emptySet() },
                onCut = { TaskClipboard.cut(tasks.filter { it.id in selectedIds }); selectedIds = emptySet() },
                onPaste = if (clipboardTasks.isNotEmpty()) ({
                    onPasteTasks(clipboardTasks, currentProjectId, TaskClipboard.cutIds)
                    if (TaskClipboard.cutIds.isNotEmpty()) TaskClipboard.clear()
                    selectedIds = emptySet()
                }) else null,
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
                    broken = task.id in brokenTaskIds,
                    selectionActive = selectionActive,
                    selected = task.id in selectedIds,
                    expanded = expandedTasks[task.id] == true,
                    onToggleExpanded = { expandedTasks[task.id] = expandedTasks[task.id] != true },
                    onLongPress = { selectedIds = selectedIds + task.id },
                    onToggleSelect = { selectedIds = if (task.id in selectedIds) selectedIds - task.id else selectedIds + task.id },
                    onEdit = { onEditTask(task) },
                    onRename = { newName -> onRenameTask(task, newName) },
                    onDelete = { onDeleteTask(task) },
                    onRun = { onRunTask(task) },
                    runBusy = runBusy,
                    onToggleFreeze = { onSetTaskFreeze(task, it) },
                    onPin = { onPinTask(task) },
                    onAddAction = { onAddAction(task) },
                    onEditAction = { index, action -> onEditAction(task, index, action) },
                    onDeleteAction = { index -> onDeleteAction(task, index) },
                    onRunAction = { index -> onRunAction(task, index) },
                    onApplyActions = { newActions -> onApplyActions(task, newActions) },
                    onPickIcon = { onPickTaskIcon(task) },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                // Reserve clearance for the bottom-right "+" FAB so the last row is never hidden under it.
                // Narrow (folded cover): shrink the side gutters — width is precious there.
                contentPadding = PaddingValues(
                    start = if (isNarrowScreen()) 6.dp else 16.dp,
                    end = if (isNarrowScreen()) 6.dp else 16.dp,
                    top = 4.dp, bottom = 88.dp,
                ),
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
                        onReorder = { movedKey, gid, ordered -> groupOps.reorder(movedKey, gid, ordered) },
                        onReorderGroups = { ordered -> groupOps.reorderGroups(ordered) },
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

/**
 * App-wide task clipboard for the Tasks-tab multiselect Copy/Cut → Paste. Holds Task copies; a Cut also
 * remembers the original ids so Paste can complete the move by deleting them.
 */
object TaskClipboard {
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    var cutIds: List<Long> = emptyList(); private set
    fun copy(items: List<Task>) { _tasks.value = items.map { it.copy() }; cutIds = emptyList() }
    fun cut(items: List<Task>) { _tasks.value = items.map { it.copy() }; cutIds = items.map { it.id } }
    fun clear() { _tasks.value = emptyList(); cutIds = emptyList() }
}

@Composable
private fun TaskCard(
    task: Task,
    broken: Boolean,
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit,
    runBusy: Boolean = false,
    onToggleFreeze: (Boolean) -> Unit,
    onPin: () -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Int, ActionSpec) -> Unit,
    onDeleteAction: (Int) -> Unit,
    onRunAction: (Int) -> Unit,
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
    var taskMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(themePrefs.selectionColor)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            Modifier.padding(
                horizontal = if (isNarrowScreen()) 10.dp else 16.dp,   // narrow: keep the content wide
                vertical = themePrefs.taskCardVPadDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
        ) {
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
                        modifier = Modifier
                            .size(width = 32.dp, height = 36.dp)
                            .clickable(enabled = !runBusy, onClick = onRun),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.action_run),
                            tint = if (runBusy) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    // Icon size honours the UI "task icon size" setting (was hardcoded 28dp — the rebase
                    // left the slider wired to nothing; 白い熊 regression audit).
                    val taskIconDp = themePrefs.taskIconSizeDp.dp
                    if (listIcon != null) {
                        Image(
                            bitmap = listIcon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(taskIconDp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPickIcon() },
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        // No icon yet → a tappable "add icon" affordance (thin outline + small +).
                        Box(
                            modifier = Modifier
                                // Empty "add icon" affordance stays compact + fixed so an icon-less task honours
                                // the card padding — the icon-SIZE slider sizes ACTUAL icons, not this placeholder
                                // (白い熊: a large icon size was inflating every icon-less card to a giant box).
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                // Subdued: the empty placeholder's border + "+" are more transparent than the
                                // card's own border, so an icon-less task's affordance recedes (白い熊).
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
                                .clickable { onPickIcon() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add task icon",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (broken) HealthAlertIcon()
                        Text(task.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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
                if (!selectionActive) {
                    Box {
                        IconButton(onClick = { taskMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Task menu", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ThemedDropdownMenu(expanded = taskMenu, onDismissRequest = { taskMenu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { taskMenu = false; showRename = true })
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { taskMenu = false; onEdit() })
                        }
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse task" else "Expand task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showRename) {
                var name by remember { mutableStateOf(task.name) }
                AlertDialog(
                    modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
                    onDismissRequest = { showRename = false },
                    title = { Text("Rename task") },
                    text = { OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
                    confirmButton = { TextButton(onClick = { onRename(name); showRename = false }) { Text("Save") } },
                    dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
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
                        taskId = task.id,
                        action = action,
                        // Inline pill edit: replace one arg's value and persist the whole action list in
                        // place (applyActions saves directly; onEditAction would re-open the full dialog).
                        onSetArg = { key, value ->
                            applyActions(task.actions.toMutableList().apply { this[index] = action.copy(args = action.args + (key to value)) })
                        },
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
                        onRun = { onRunAction(index) },
                        runBusy = runBusy,
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
    taskId: Long,
    action: ActionSpec,
    onSetArg: (String, String) -> Unit,
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
    onRun: () -> Unit,
    runBusy: Boolean,
) {
    val metadata = ActionMetadataRegistry.get(action.type)
    val capability = ActionCapabilityRegistry.get(action.type)
    val themePrefs by ThemeStore.state.collectAsState()
    // Per-action label fold, persisted in item_meta (tab "action_label", key "<taskId>:<index>") — reuses
    // the Note fold's store + noteExpanded boolean. Default folded: only the first line of the label shows.
    val dao = remember { OpenTaskerApp_NoHilt.db.itemMetaDao() }
    val foldKey = "$taskId:$index"
    val foldMeta by remember(foldKey) { dao.getAsFlow("action_label", foldKey) }.collectAsState(initial = null)
    val labelExpanded = foldMeta?.noteExpanded ?: false
    val scope = rememberCoroutineScope()
    val toggleFold: () -> Unit = {
        scope.launch {
            val cur = dao.get("action_label", foldKey) ?: ItemMetaEntity(tab = "action_label", itemKey = foldKey)
            dao.upsert(cur.copy(noteExpanded = !labelExpanded))
        }
    }
    var editingKey by remember(action) { mutableStateOf<String?>(null) }
    Surface(
        color = if (selected) Color(themePrefs.selectionColor)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.64f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            if (selected) 2.dp else themePrefs.actionBorderWidthDp.dp,
            if (selected) MaterialTheme.colorScheme.primary else Color(themePrefs.actionBorderColor),
        ),
    ) {
        Box {
            Column(
                // Tap selects (while selecting); long-press selects + opens the clone/copy/cut/paste menu.
                // Children (label frame, arg values, icon buttons) intercept their own taps. The vertical
                // padding + the label↔args gap are the settable "Padding inside action rows" (default 2 dp).
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    .padding(horizontal = 12.dp, vertical = themePrefs.actionRowPadDp.dp),
                verticalArrangement = Arrangement.spacedBy(themePrefs.actionRowPadDp.dp),
            ) {
                // Header: index · the folded label in a (yellow) rounded frame · edit / delete.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                    if (selectionActive && selected) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.label_selected), tint = MaterialTheme.colorScheme.primary)
                    } else {
                        StatusPill("#${index + 1}", MaterialTheme.colorScheme.secondary)
                    }
                    Row(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(themePrefs.actionLabelFrameWidthDp.dp, Color(themePrefs.actionLabelFrameColor), RoundedCornerShape(8.dp))
                            // In selection mode a tap toggles this action's selection; otherwise it folds.
                            // Long-press always (de)selects + opens the clone/copy/cut/paste menu.
                            .combinedClickable(onClick = { if (selectionActive) onTap() else toggleFold() }, onLongClick = onLongPress)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            action.label ?: metadata?.name ?: action.type,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = themePrefs.actionLabelSizeSp.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (labelExpanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (labelExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (labelExpanded) "Collapse label" else "Expand label",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit)) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error) }
                }
                // Args — flush-left with the index. Label expanded → all args on ONE line (flow); folded →
                // TWO lines (stacked). Tap a value to edit it in place.
                if (action.args.isEmpty()) {
                    Text(
                        metadata?.description ?: stringResource(R.string.workspace_no_arguments),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                } else if (isNarrowScreen()) {
                    // Narrow (folded cover) reflow: EACH arg gets its own full-width line — the (key)
                    // pill + a value that takes the whole rest and wraps to 2 lines, instead of the
                    // one-line row that crushed var names to "Ong…" (白い熊 2026-07-11).
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(themePrefs.actionRowPadDp.dp)) {
                        action.args.entries.forEach { e ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ArgPill(
                                    argKey = e.key, value = e.value, valueWeight = true,
                                    maxValueLines = 2,
                                    editing = editingKey == e.key,
                                    selectionActive = selectionActive,
                                    onStartEdit = { editingKey = e.key },
                                    onSelectToggle = onTap,
                                    onLongPress = onLongPress,
                                    onCommit = { nv -> onSetArg(e.key, nv); editingKey = null },
                                    onCancel = { editingKey = null },
                                )
                            }
                        }
                    }
                } else {
                    // name + value (every arg) on ONE line: each (key) pill + its value. The last value
                    // takes the remaining width and ellipsises — tap it to edit / see the whole thing.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val entries = action.args.entries.toList()
                        entries.forEachIndexed { i, e ->
                            ArgPill(
                                argKey = e.key, value = e.value, valueWeight = i == entries.lastIndex,
                                editing = editingKey == e.key,
                                selectionActive = selectionActive,
                                onStartEdit = { editingKey = e.key },
                                onSelectToggle = onTap,
                                onLongPress = onLongPress,
                                onCommit = { nv -> onSetArg(e.key, nv); editingKey = null },
                                onCancel = { editingKey = null },
                            )
                        }
                    }
                }
                if (capability.level != CapabilityLevel.Supported) {
                    // Live capability pill — same checks as the Setup tab, re-evaluated on every resume.
                    // Requirement MET → no pill at all (the action just works — a standing "Needs setup"
                    // there was a lie). Requirement UNMET → red pill naming what's missing; tapping it
                    // deep-links straight to the settings screen / app that grants it.
                    val pillContext = LocalContext.current
                    val resumeTick = rememberResumeTick()
                    val reqMet = remember(capability.requirement, resumeTick) {
                        capability.requirement == CapabilityRequirement.None ||
                            CapabilityState.isMetLive(capability.requirement, pillContext)
                    }
                    when {
                        capability.level == CapabilityLevel.Unsupported ->
                            StatusPill(stringResource(R.string.label_unsupported), MaterialTheme.colorScheme.error)
                        !reqMet -> StatusPill(
                            "${stringResource(R.string.status_needs_setup)} — ${CapabilityState.statusLabel(capability.requirement, false)}",
                            MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable {
                                CapabilityState.settingsIntent(capability.requirement, pillContext)
                                    ?.let { runCatching { pillContext.startActivity(it) } }
                            },
                        )
                        // Informational RequiresSetup with no checkable requirement (e.g. clipboard
                        // background limits): keep the neutral pill, nothing to verify or fix.
                        capability.requirement == CapabilityRequirement.None ->
                            StatusPill(stringResource(R.string.status_needs_setup), MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // Long-press menu — Clone/Copy/Cut/Delete act on the whole selection; Paste drops the
            // clipboard relative to this action (shown only when something has been copied/cut).
            ThemedDropdownMenu(expanded = menuExpanded, onDismissRequest = onMenuDismiss) {
                // Run this one action on its own (upstream 0.2.93), to tune an HTTP call or a
                // variable write without re-running everything before it. Flow-control markers are
                // excluded: an `if` without its `end if` is not a smaller program, it is a broken one.
                if (SingleActionRun.isRunnableAlone(action)) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (runBusy) R.string.action_run_alone_busy else R.string.action_run_alone))
                        },
                        enabled = !runBusy,
                        onClick = { onMenuDismiss(); onRun() },
                    )
                }
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

/**
 * One action arg emitted INTO a Row (RowScope) so name + value share one line: a rounded (key) pill in a
 * muted grey-blue + its value (the variable NAME blue, values bold, bigger than the folded label).
 * [valueWeight] makes the value fill the remaining width (the last arg) vs a natural cap. Tap a value to
 * edit it in place — ✓ / keyboard-Done saves; tapping away closes if unchanged, else asks to Save/Discard.
 * (Colours/sizes/the label frame become UI-customization settings in the next step.)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ArgPill(
    argKey: String,
    value: String,
    valueWeight: Boolean,
    editing: Boolean,
    selectionActive: Boolean,
    onStartEdit: () -> Unit,
    onSelectToggle: () -> Unit,
    onLongPress: () -> Unit,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    maxValueLines: Int = 1,   // narrow-screen rows pass 2 so long values wrap instead of ellipsising
) {
    val themePrefs by ThemeStore.state.collectAsState()
    val nameColor = Color(themePrefs.actionNameColor)   // the variable name (settable)
    val valueColor = Color(themePrefs.actionValueColor) // action value (settable)
    val dataSize = themePrefs.actionValueSizeSp.sp
    val pillColor = Color(0xFF9AA7B4)   // the (key) pill — muted (pill colour moves to the theme step)
    Text(
        argKey,
        style = MaterialTheme.typography.labelMedium,
        color = pillColor,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(pillColor.copy(alpha = 0.14f))
            .border(1.dp, pillColor.copy(alpha = 0.40f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
    if (editing) {
        var text by remember(value) { mutableStateOf(value) }
        var showConfirm by remember { mutableStateOf(false) }
        var committing by remember { mutableStateOf(false) }
        var hadFocus by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = dataSize),
            maxLines = 6,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { committing = true; onCommit(text) }),
            trailingIcon = { IconButton(onClick = { committing = true; onCommit(text) }) { Icon(Icons.Filled.Check, contentDescription = "Save") } },
            modifier = (if (valueWeight) Modifier.weight(1f) else Modifier.widthIn(min = 120.dp, max = 220.dp))
                .focusRequester(focusRequester)
                .onFocusChanged { fs ->
                    // Tapped away (not via ✓/Done): unchanged → just close; changed → ask Save/Discard.
                    if (fs.isFocused) hadFocus = true
                    else if (hadFocus && !committing) {
                        if (text == value) onCancel() else showConfirm = true
                    }
                },
        )
        if (showConfirm) {
            AlertDialog(
                modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
                onDismissRequest = { showConfirm = false; onCancel() },
                title = { Text("Save changes?") },
                text = { Text("“$argKey” was edited but not saved.") },
                confirmButton = { TextButton(onClick = { showConfirm = false; onCommit(text) }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { showConfirm = false; onCancel() }) { Text("Discard") } },
            )
        }
    } else {
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            fontSize = dataSize,
            fontWeight = FontWeight.Bold,
            color = if (argKey == "name") nameColor else valueColor,
            maxLines = maxValueLines,
            overflow = TextOverflow.Ellipsis,
            // Last arg: fill whatever is left (and ellipsise there). Non-last (e.g. a var.set NAME): take
            // the NATURAL width so the name always shows completely — the hard 160dp cap truncated most
            // %Ongaku_*-length names on a wide screen (白い熊). fill=false keeps it natural-sized while the
            // 3:1 weights still guarantee the last value at least ~25% of the flexible width, so a
            // pathological name ellipsises at ~75% instead of pushing the value off the row.
            modifier = (if (valueWeight) Modifier.weight(1f) else Modifier.weight(3f, fill = false))
                .clip(RoundedCornerShape(6.dp))
                // In selection mode a tap toggles this action; otherwise it edits the value. Long-press
                // always (de)selects + opens the menu.
                .combinedClickable(onClick = { if (selectionActive) onSelectToggle() else onStartEdit() }, onLongClick = onLongPress)
                .padding(vertical = 1.dp, horizontal = 2.dp),
        )
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
