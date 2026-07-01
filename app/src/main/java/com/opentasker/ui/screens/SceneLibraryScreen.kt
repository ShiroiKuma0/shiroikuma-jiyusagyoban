package com.opentasker.ui.screens

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.components.ConfirmDeleteSelected
import com.opentasker.ui.components.GroupMoveDialogs
import com.opentasker.ui.components.GroupOps
import com.opentasker.ui.components.ItemNoteSection
import com.opentasker.ui.components.RgbaColorPickerDialog
import com.opentasker.ui.components.SelectionBar
import com.opentasker.ui.components.SelectionCheck
import com.opentasker.ui.components.groupedItems
import com.opentasker.ui.components.rememberGroupDragState
import com.opentasker.ui.components.rememberGroupMoveHost
import com.opentasker.ui.components.selectableItem
import com.opentasker.core.model.Project
import com.opentasker.core.model.ProjectFilter
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.AlignmentGuide
import com.opentasker.core.scenes.GuideOrientation
import com.opentasker.core.scenes.SceneAlignmentGuides
import com.opentasker.core.scenes.SceneCanvasProjector
import com.opentasker.core.scenes.SceneOverlayService
import com.opentasker.core.scenes.SceneElementDrafts
import com.opentasker.core.scenes.SceneIssue
import com.opentasker.core.scenes.SceneIssueSeverity
import com.opentasker.core.scenes.SceneValidator

@Composable
fun SceneLibraryScreen(
    scenes: List<Scene>,
    expandedScenes: SnapshotStateMap<Long, Boolean>,
    tasks: List<Task>,
    projects: List<Project>,
    projectFilter: ProjectFilter,
    currentProjectId: Long?,
    onSelectProject: (ProjectFilter) -> Unit,
    groupOps: GroupOps,
    onMoveScenesToProject: (List<Scene>, Long?) -> Unit,
    onDeleteScenes: (List<Scene>) -> Unit,
    createSignal: Int,
    onCreateScene: (name: String, widthDp: Int, heightDp: Int, bgColor: String?, cornerRadiusDp: Int, scrimAlpha: Int, borderColor: String?, borderWidth: Int, defaultPosition: String, defaultModal: Boolean, defaultDismissOnOutside: Boolean) -> Unit,
    onUpdateScene: (Scene, String) -> Unit,
    onDeleteScene: (Scene) -> Unit,
    contentPadding: PaddingValues,
) {
    // Item + group multi-selection live here (re-mounting the screen on a tab switch resets them). Set<Long>
    // has no Saver, so plain remember — not rememberSaveable — is correct. Mirrors TasksScreen.
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedGroupIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var confirmDeleteItems by remember { mutableStateOf(false) }
    var confirmDeleteGroups by remember { mutableStateOf(false) }
    val selectionActive = selectedIds.isNotEmpty()
    val groupSelectionActive = selectedGroupIds.isNotEmpty()

    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var editSceneTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var elementEditorSceneId by rememberSaveable { mutableStateOf<Long?>(null) }
    var elementEditorIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingElementDeleteSceneId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingElementDeleteIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val sortedScenes = remember(scenes) { scenes.sortedBy { it.name.lowercase() } }
    val elementEditor = remember(scenes, elementEditorSceneId, elementEditorIndex) {
        sceneElementEditorState(scenes, elementEditorSceneId, elementEditorIndex, allowNew = true)
    }
    val pendingElementDelete = remember(scenes, pendingElementDeleteSceneId, pendingElementDeleteIndex) {
        sceneElementEditorState(scenes, pendingElementDeleteSceneId, pendingElementDeleteIndex, allowNew = false)
    }

    // "New scene" lives in the tab's "+" menu (TabActionsFab); a tick of [createSignal] opens the dialog.
    LaunchedEffect(createSignal) {
        if (createSignal > 0) showCreateDialog = true
    }

    LaunchedEffect(elementEditorSceneId, elementEditor) {
        if (elementEditorSceneId != null && elementEditor == null) {
            elementEditorSceneId = null
            elementEditorIndex = null
        }
    }
    LaunchedEffect(pendingElementDeleteSceneId, pendingElementDelete) {
        if (pendingElementDeleteSceneId != null && pendingElementDelete == null) {
            pendingElementDeleteSceneId = null
            pendingElementDeleteIndex = null
        }
    }

    if (showCreateDialog) {
        SceneEditorDialog(
            siblingNames = scenes.mapTo(mutableSetOf()) { it.name.trim().lowercase() },
            onDismiss = { showCreateDialog = false },
            onSave = { name, widthDp, heightDp, bgColor, corner, scrim, borderColor, borderWidth, defaultPosition, defaultModal, defaultDismissOnOutside ->
                onCreateScene(name, widthDp, heightDp, bgColor, corner, scrim, borderColor, borderWidth, defaultPosition, defaultModal, defaultDismissOnOutside)
                showCreateDialog = false
            },
        )
    }

    scenes.firstOrNull { it.id == editSceneTargetId }?.let { target ->
        SceneEditorDialog(
            initial = target,
            siblingNames = scenes.filter { (it.projectId ?: 0L) == (target.projectId ?: 0L) && it.id != target.id }
                .mapTo(mutableSetOf()) { it.name.trim().lowercase() },
            onDismiss = { editSceneTargetId = null },
            onSave = { name, widthDp, heightDp, bgColor, corner, scrim, borderColor, borderWidth, defaultPosition, defaultModal, defaultDismissOnOutside ->
                onUpdateScene(
                    target.copy(
                        name = name, widthDp = widthDp, heightDp = heightDp,
                        bgColor = bgColor, cornerRadiusDp = corner, scrimAlpha = scrim,
                        borderColor = borderColor, borderWidth = borderWidth,
                        defaultPosition = defaultPosition, defaultModal = defaultModal,
                        defaultDismissOnOutside = defaultDismissOnOutside,
                    ),
                    "Scene updated",
                )
                editSceneTargetId = null
            },
        )
    }

    elementEditor?.let { state ->
        SceneElementEditorDialog(
            state = state,
            tasks = tasks,
            onDismiss = {
                elementEditorSceneId = null
                elementEditorIndex = null
            },
            onSave = { element ->
                val updatedScene = if (state.index == null) {
                    state.scene.copy(elements = state.scene.elements + element)
                } else {
                    state.scene.copy(
                        elements = state.scene.elements.mapIndexed { index, existing ->
                            if (index == state.index) element else existing
                        },
                    )
                }
                onUpdateScene(updatedScene, if (state.index == null) "Element added" else "Element updated")
                elementEditorSceneId = null
                elementEditorIndex = null
            },
        )
    }

    pendingElementDelete?.let { state ->
        SceneElementDeleteDialog(
            state = state,
            onDismiss = {
                pendingElementDeleteSceneId = null
                pendingElementDeleteIndex = null
            },
            onConfirm = {
                val index = state.index
                if (index != null) {
                    onUpdateScene(
                        state.scene.copy(elements = state.scene.elements.filterIndexed { i, _ -> i != index }),
                        "Element removed",
                    )
                }
                pendingElementDeleteSceneId = null
                pendingElementDeleteIndex = null
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (projects.isNotEmpty()) {
            ProjectFilterChips(projects, projectFilter, onSelectProject, Modifier.padding(vertical = 8.dp))
        }
        if (selectionActive) {
            SelectionBar(
                count = selectedIds.size,
                total = sortedScenes.size,
                onSelectAll = { selectedIds = sortedScenes.map { it.id }.toSet() },
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
        if (sortedScenes.isEmpty()) {
            Box(Modifier.weight(1f)) {
                SceneEmptyState(
                    contentPadding = PaddingValues(0.dp),
                    onCreateScene = { showCreateDialog = true },
                )
            }
        } else {
            val moveHost = rememberGroupMoveHost()
            val dragState = rememberGroupDragState()
            val sceneCard: @Composable (Scene) -> Unit = { scene ->
                val sceneContext = LocalContext.current
                SceneCard(
                    scene = scene,
                    tasks = tasks,
                    selectionActive = selectionActive,
                    selected = scene.id in selectedIds,
                    expanded = expandedScenes[scene.id] == true,
                    onToggleExpanded = { expandedScenes[scene.id] = expandedScenes[scene.id] != true },
                    onLongPress = { selectedIds = selectedIds + scene.id },
                    onToggleSelect = { selectedIds = if (scene.id in selectedIds) selectedIds - scene.id else selectedIds + scene.id },
                    onAddElement = {
                        elementEditorSceneId = scene.id
                        elementEditorIndex = null
                    },
                    onEditElement = { index, _ ->
                        elementEditorSceneId = scene.id
                        elementEditorIndex = index
                    },
                    onDeleteElement = { index, _ ->
                        pendingElementDeleteSceneId = scene.id
                        pendingElementDeleteIndex = index
                    },
                    onMoveElement = { index, element ->
                        onUpdateScene(
                            scene.copy(
                                elements = scene.elements.mapIndexed { i, existing ->
                                    if (i == index) element else existing
                                },
                            ),
                            "Element moved",
                        )
                    },
                    onEditScene = { editSceneTargetId = scene.id },
                    onDelete = { onDeleteScene(scene) },
                    onShowOverlay = { SceneOverlayService.show(sceneContext, scene) },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().weight(1f),
                // Reserve clearance for the bottom-right "+" FAB so the last row is never hidden under it.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                if (groupOps.groups.isEmpty()) {
                    items(sortedScenes, key = { it.id }) { scene -> sceneCard(scene) }
                } else {
                    groupedItems(
                        sortedScenes, { it.id.toString() }, groupOps, dragState,
                        onMoveItem = { moveHost.movingItemKey = it },
                        onMoveGroup = { moveHost.movingGroup = it },
                        selectedGroupIds = selectedGroupIds,
                        onLongPressGroup = { selectedGroupIds = selectedGroupIds + it.id },
                        onToggleSelectGroup = { g -> selectedGroupIds = if (g.id in selectedGroupIds) selectedGroupIds - g.id else selectedGroupIds + g.id },
                        onReorder = { movedKey, gid, ordered -> groupOps.reorder(movedKey, gid, ordered) },
                    ) { scene -> sceneCard(scene) }
                }
            }
            GroupMoveDialogs(groupOps, moveHost)
        }
    }

    if (showMoveDialog) {
        ProjectPickerDialog(
            title = "Move ${selectedIds.size} scene${plural(selectedIds.size)}",
            projects = projects,
            currentProjectId = currentProjectId,
            onPick = { pid ->
                onMoveScenesToProject(sortedScenes.filter { it.id in selectedIds }, pid)
                selectedIds = emptySet()
                showMoveDialog = false
            },
            onDismiss = { showMoveDialog = false },
        )
    }
    if (confirmDeleteItems) {
        ConfirmDeleteSelected(
            count = selectedIds.size,
            noun = "scene",
            onConfirm = {
                onDeleteScenes(sortedScenes.filter { it.id in selectedIds })
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

private fun sceneElementEditorState(
    scenes: List<Scene>,
    sceneId: Long?,
    index: Int?,
    allowNew: Boolean,
): SceneElementEditorState? {
    val scene = scenes.firstOrNull { it.id == sceneId } ?: return null
    return if (index == null) {
        if (allowNew) SceneElementEditorState(scene = scene) else null
    } else {
        SceneElementEditorState(
            scene = scene,
            index = index,
            element = scene.elements.getOrNull(index) ?: return null,
        )
    }
}

private fun SceneElement.nudgedWithin(scene: Scene, deltaX: Int, deltaY: Int): SceneElement {
    val maxX = (scene.widthDp - widthDp).coerceAtLeast(0)
    val maxY = (scene.heightDp - heightDp).coerceAtLeast(0)
    return copy(
        xDp = (xDp + deltaX).coerceIn(0, maxX),
        yDp = (yDp + deltaY).coerceIn(0, maxY),
    )
}

private data class SceneElementEditorState(
    val scene: Scene,
    val index: Int? = null,
    val element: SceneElement? = null,
)

@Composable
private fun SceneEmptyState(
    contentPadding: PaddingValues,
    onCreateScene: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f),
            shape = RoundedCornerShape(DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.scenes_empty_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(R.string.empty_scenes_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.empty_scenes_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Button(
                    onClick = onCreateScene,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(DesignSystem.Radii.lg),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scenes_create))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scenes_create))
                }
            }
        }
    }
}

@Composable
internal fun SceneOverviewCard(
    scenes: List<Scene>,
    tasks: List<Task>,
    onCreateScene: () -> Unit,
) {
    val context = LocalContext.current
    val overlayReady = Settings.canDrawOverlays(context)
    val issues = remember(scenes, tasks) {
        scenes.flatMap { scene -> SceneValidator.validate(scene, tasks) }
    }
    val errorCount = issues.count { it.severity == SceneIssueSeverity.ERROR }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_scene_library), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.scenes_overview_summary, scenes.sumOf { it.elements.size }, scenes.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SceneStatusPill(
                    label = if (overlayReady) stringResource(R.string.status_overlay_ready) else stringResource(R.string.status_needs_setup),
                    color = if (overlayReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                SceneMetric("${scenes.size}", stringResource(R.string.label_scenes), Modifier.weight(1f))
                SceneMetric("${scenes.sumOf { it.elements.size }}", stringResource(R.string.label_elements), Modifier.weight(1f))
                SceneMetric("$errorCount", stringResource(R.string.label_errors), Modifier.weight(1f))
            }
            Button(onClick = onCreateScene, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(DesignSystem.Radii.lg)) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scenes_create))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.scenes_create))
            }
        }
    }
}

@Composable
private fun SceneCard(
    scene: Scene,
    tasks: List<Task>,
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onAddElement: () -> Unit,
    onEditElement: (Int, SceneElement) -> Unit,
    onDeleteElement: (Int, SceneElement) -> Unit,
    onMoveElement: (Int, SceneElement) -> Unit,
    onEditScene: () -> Unit,
    onDelete: () -> Unit,
    onShowOverlay: () -> Unit = {},
) {
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val issues = remember(scene, tasks) { SceneValidator.validate(scene, tasks) }
    var selectedIndices by remember(scene.id) { mutableStateOf(emptySet<Int>()) }
    val context = LocalContext.current
    val overlayReady = Settings.canDrawOverlays(context)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
        ),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
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
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                }
                Column(Modifier.weight(1f)) {
                    Text(scene.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Collapsed: size + element count, plus an issue count when there is anything wrong.
                    Text(
                        text = stringResource(R.string.scenes_card_summary, scene.widthDp, scene.heightDp, scene.elements.size) +
                            if (!expanded && issues.isNotEmpty()) " - ${issues.size} issue${plural(issues.size)}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded) {
                    if (overlayReady && scene.elements.isNotEmpty()) {
                        OutlinedButton(onClick = onShowOverlay) {
                            Text(stringResource(R.string.action_show), maxLines = 1)
                        }
                    }
                    IconButton(onClick = onEditScene) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit scene properties")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scenes_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse scene" else "Expand scene",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                ItemNoteSection("scenes", scene.id.toString())

                ScenePreviewBox(
                    scene = scene,
                    onMoveElement = { index, xDp, yDp ->
                        scene.elements.getOrNull(index)?.let { element ->
                            onMoveElement(index, element.copy(xDp = xDp, yDp = yDp))
                        }
                    },
                    onResizeElement = { index, widthDp, heightDp ->
                        scene.elements.getOrNull(index)?.let { element ->
                            onMoveElement(index, element.copy(widthDp = widthDp, heightDp = heightDp))
                        }
                    },
                    selectedIndices = selectedIndices,
                    onToggleSelect = { index ->
                        selectedIndices = if (index in selectedIndices) selectedIndices - index else selectedIndices + index
                    },
                    onMoveSelected = { dx, dy ->
                        val updated = scene.elements.mapIndexed { i, el ->
                            if (i in selectedIndices) el.nudgedWithin(scene, dx, dy) else el
                        }
                        val updatedScene = scene.copy(elements = updated)
                        updated.forEachIndexed { i, el ->
                            if (i in selectedIndices && el != scene.elements[i]) {
                                onMoveElement(i, el)
                            }
                        }
                    },
                )

                OutlinedButton(onClick = onAddElement, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.scenes_add_element_content_description))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_add_element))
                }

                if (scene.elements.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
                        scene.elements.forEachIndexed { index, element ->
                            SceneElementRow(
                                scene = scene,
                                element = element,
                                taskNames = taskNames,
                                onNudge = { deltaX, deltaY ->
                                    onMoveElement(index, element.nudgedWithin(scene, deltaX, deltaY))
                                },
                                onEdit = { onEditElement(index, element) },
                                onDelete = { onDeleteElement(index, element) },
                            )
                        }
                    }
                }

                if (issues.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        issues.take(4).forEach { issue ->
                            SceneIssueText(issue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenePreviewBox(
    scene: Scene,
    onMoveElement: (Int, Int, Int) -> Unit,
    onResizeElement: (Int, Int, Int) -> Unit = { _, _, _ -> },
    selectedIndices: Set<Int> = emptySet(),
    onToggleSelect: (Int) -> Unit = {},
    onMoveSelected: (Int, Int) -> Unit = { _, _ -> },
) {
    var activeGuides by remember { mutableStateOf<List<AlignmentGuide>>(emptyList()) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
            Text(stringResource(R.string.scenes_canvas_size, scene.widthDp, scene.heightDp), style = MaterialTheme.typography.labelLarge)
            if (scene.elements.isEmpty()) {
                Text(stringResource(R.string.empty_scene_elements), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val canvasWidth = maxWidth.value
                    val canvasHeight = SceneCanvasProjector.projectedHeight(
                        scene = scene,
                        canvasWidth = canvasWidth,
                        minHeight = 96f,
                        maxHeight = 280f,
                    )
                    val projections = SceneCanvasProjector.project(scene, canvasWidth, canvasHeight)
                    val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(width = maxWidth, height = canvasHeight.dp)
                            .clipToBounds(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
                        shape = RoundedCornerShape(DesignSystem.Radii.lg),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            projections.forEachIndexed { index, projection ->
                                SceneCanvasElement(
                                    scene = scene,
                                    index = index,
                                    projection = projection,
                                    canvasWidth = canvasWidth,
                                    canvasHeight = canvasHeight,
                                    onMoveElement = { idx, xDp, yDp ->
                                        if (selectedIndices.size > 1 && idx in selectedIndices) {
                                            val origElement = scene.elements[idx]
                                            val dx = xDp - origElement.xDp
                                            val dy = yDp - origElement.yDp
                                            onMoveSelected(dx, dy)
                                        } else {
                                            onMoveElement(idx, xDp, yDp)
                                        }
                                    },
                                    onResizeElement = onResizeElement,
                                    selected = index in selectedIndices,
                                    onSelect = { onToggleSelect(index) },
                                    onAlignmentGuidesChanged = { guides -> activeGuides = guides },
                                )
                            }
                            if (activeGuides.isNotEmpty()) {
                                val scaleX = canvasWidth / (scene.widthDp.takeIf { it > 0 } ?: 1).toFloat()
                                val scaleY = canvasHeight / (scene.heightDp.takeIf { it > 0 } ?: 1).toFloat()
                                Canvas(Modifier.fillMaxSize()) {
                                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                                    activeGuides.forEach { guide ->
                                        when (guide.orientation) {
                                            GuideOrientation.VERTICAL -> {
                                                val x = guide.position * scaleX
                                                drawLine(
                                                    color = guideColor,
                                                    start = Offset(x, 0f),
                                                    end = Offset(x, size.height),
                                                    strokeWidth = 1.5f,
                                                    pathEffect = dash,
                                                )
                                            }
                                            GuideOrientation.HORIZONTAL -> {
                                                val y = guide.position * scaleY
                                                drawLine(
                                                    color = guideColor,
                                                    start = Offset(0f, y),
                                                    end = Offset(size.width, y),
                                                    strokeWidth = 1.5f,
                                                    pathEffect = dash,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneCanvasElement(
    scene: Scene,
    index: Int,
    projection: com.opentasker.core.scenes.SceneCanvasElementProjection,
    canvasWidth: Float,
    canvasHeight: Float,
    onMoveElement: (Int, Int, Int) -> Unit,
    onResizeElement: (Int, Int, Int) -> Unit = { _, _, _ -> },
    selected: Boolean = false,
    onSelect: () -> Unit = {},
    onAlignmentGuidesChanged: (List<AlignmentGuide>) -> Unit = {},
) {
    val element = projection.element
    val density = LocalDensity.current
    var dragX by remember(scene.id, element.id, projection.x, projection.y) { mutableFloatStateOf(0f) }
    var dragY by remember(scene.id, element.id, projection.x, projection.y) { mutableFloatStateOf(0f) }
    var resizeDx by remember(scene.id, element.id, projection.width, projection.height) { mutableFloatStateOf(0f) }
    var resizeDy by remember(scene.id, element.id, projection.width, projection.height) { mutableFloatStateOf(0f) }
    val color = when (element.type) {
        SceneElementType.BUTTON -> MaterialTheme.colorScheme.primary
        SceneElementType.TEXT -> MaterialTheme.colorScheme.tertiary
        SceneElementType.SLIDER -> MaterialTheme.colorScheme.secondary
        SceneElementType.IMAGE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else color.copy(alpha = 0.52f)
    val borderWidth = if (selected) 2.dp else 1.dp
    val currentWidth = (projection.width + resizeDx).coerceAtLeast(12f)
    val currentHeight = (projection.height + resizeDy).coerceAtLeast(12f)
    Box(
        modifier = Modifier
            .offset {
                with(density) {
                    IntOffset(
                        x = (projection.x + dragX).dp.roundToPx(),
                        y = (projection.y + dragY).dp.roundToPx(),
                    )
                }
            }
            .size(width = currentWidth.dp, height = currentHeight.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(scene.id, element.id, projection.x, projection.y, canvasWidth, canvasHeight, density.density) {
                    // Element move requires a deliberate LONG-PRESS first, so fling-scrolling the list/canvas
                    // no longer accidentally drags an element (白い熊's report). Resize handle is unchanged.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onSelect() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragX += dragAmount.x / density.density
                            dragY += dragAmount.y / density.density
                            val (candidateX, candidateY) = SceneCanvasProjector.scenePositionForCanvasOffset(
                                scene = scene,
                                element = element,
                                canvasX = projection.x + dragX,
                                canvasY = projection.y + dragY,
                                canvasWidth = canvasWidth,
                                canvasHeight = canvasHeight,
                            )
                            val alignment = SceneAlignmentGuides.findGuides(
                                scene = scene,
                                movingIndex = index,
                                candidateX = candidateX,
                                candidateY = candidateY,
                                candidateW = element.widthDp,
                                candidateH = element.heightDp,
                            )
                            onAlignmentGuidesChanged(alignment.guides)
                        },
                        onDragEnd = {
                            val (candidateX, candidateY) = SceneCanvasProjector.scenePositionForCanvasOffset(
                                scene = scene,
                                element = element,
                                canvasX = projection.x + dragX,
                                canvasY = projection.y + dragY,
                                canvasWidth = canvasWidth,
                                canvasHeight = canvasHeight,
                            )
                            val alignment = SceneAlignmentGuides.findGuides(
                                scene = scene,
                                movingIndex = index,
                                candidateX = candidateX,
                                candidateY = candidateY,
                                candidateW = element.widthDp,
                                candidateH = element.heightDp,
                            )
                            dragX = 0f
                            dragY = 0f
                            onAlignmentGuidesChanged(emptyList())
                            onMoveElement(index, alignment.snappedX, alignment.snappedY)
                        },
                        onDragCancel = {
                            dragX = 0f
                            dragY = 0f
                            onAlignmentGuidesChanged(emptyList())
                        },
                    )
                },
            color = color.copy(alpha = if (selected) 0.22f else 0.14f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(borderWidth, borderColor),
        ) {
            Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = sceneElementSummary(element) ?: sceneElementTypeLabel(element.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(14.dp)
                .pointerInput(scene.id, element.id, projection.width, projection.height, canvasWidth, canvasHeight, density.density) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            resizeDx += dragAmount.x / density.density
                            resizeDy += dragAmount.y / density.density
                        },
                        onDragEnd = {
                            val scale = scene.widthDp / canvasWidth
                            val newW = ((element.widthDp + (resizeDx * scale).toInt()).coerceIn(MIN_ELEMENT_SIZE, scene.widthDp))
                            val newH = ((element.heightDp + (resizeDy * scale).toInt()).coerceIn(MIN_ELEMENT_SIZE, scene.heightDp))
                            resizeDx = 0f
                            resizeDy = 0f
                            onResizeElement(index, newW, newH)
                        },
                        onDragCancel = {
                            resizeDx = 0f
                            resizeDy = 0f
                        },
                    )
                },
            color = color.copy(alpha = 0.62f),
            shape = RoundedCornerShape(4.dp),
        ) {}
    }
}

private const val MIN_ELEMENT_SIZE = 8

@Composable
private fun SceneElementRow(
    scene: Scene,
    element: SceneElement,
    taskNames: Map<Long, String>,
    onNudge: (Int, Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(sceneElementTypeLabel(element.type), style = MaterialTheme.typography.labelLarge)
                    sceneElementSummary(element)?.let { summary ->
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        stringResource(
                            R.string.scenes_element_bounds,
                            element.xDp,
                            element.yDp,
                            element.widthDp,
                            element.heightDp,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    listOfNotNull(
                        element.tapTaskId?.let {
                            stringResource(
                                R.string.scenes_binding_tap,
                                taskNames[it] ?: stringResource(R.string.scenes_missing_task_id, it),
                            )
                        },
                        element.longPressTaskId?.let {
                            stringResource(
                                R.string.scenes_binding_long_press,
                                taskNames[it] ?: stringResource(R.string.scenes_missing_task_id, it),
                            )
                        },
                    ).forEach { binding ->
                        Text(binding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.scenes_edit_element_content_description))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scenes_delete_element_content_description), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            SceneElementNudgeControls(
                scene = scene,
                element = element,
                onNudge = onNudge,
            )
        }
    }
}

@Composable
private fun SceneElementNudgeControls(
    scene: Scene,
    element: SceneElement,
    onNudge: (Int, Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            enabled = element.xDp > 0,
            onClick = { onNudge(-1, 0) },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.scenes_move_left_content_description))
        }
        IconButton(
            enabled = element.yDp > 0,
            onClick = { onNudge(0, -1) },
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.scenes_move_up_content_description))
        }
        IconButton(
            enabled = element.yDp < (scene.heightDp - element.heightDp).coerceAtLeast(0),
            onClick = { onNudge(0, 1) },
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.scenes_move_down_content_description))
        }
        IconButton(
            enabled = element.xDp < (scene.widthDp - element.widthDp).coerceAtLeast(0),
            onClick = { onNudge(1, 0) },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.scenes_move_right_content_description))
        }
    }
}

@Composable
private fun SceneElementDeleteDialog(
    state: SceneElementEditorState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val element = state.element
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Delete,
                contentDescription = stringResource(R.string.scenes_remove_element_content_description),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.dialog_remove_element)) },
        text = {
            val elementLabel = element?.type?.let { sceneElementTypeLabel(it) }
                ?: stringResource(R.string.label_selected).lowercase()
            Text(
                stringResource(
                    R.string.scenes_remove_element_body,
                    elementLabel,
                    state.scene.name,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.action_remove_element))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SceneElementEditorDialog(
    state: SceneElementEditorState,
    tasks: List<Task>,
    onDismiss: () -> Unit,
    onSave: (SceneElement) -> Unit,
) {
    val initial = remember(state) {
        state.element ?: SceneElementDrafts.defaultElement(state.scene, SceneElementType.BUTTON)
    }
    var type by rememberSaveable(state.scene.id, state.index) {
        mutableStateOf(initial.type.takeIf { it in SceneElementDrafts.editableTypes } ?: SceneElementType.BUTTON)
    }
    var x by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.xDp.toString()) }
    var y by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.yDp.toString()) }
    var width by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.widthDp.toString()) }
    var height by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.heightDp.toString()) }
    var label by rememberSaveable(state.scene.id, state.index) {
        mutableStateOf(initial.config["label"] ?: initial.config["text"] ?: "")
    }
    var sliderMin by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.config["min"] ?: "0") }
    var sliderMax by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.config["max"] ?: "100") }
    var sliderValue by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.config["value"] ?: "50") }
    var imageSource by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.config["source"] ?: "") }
    var tapTaskId by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.tapTaskId) }
    var longPressTaskId by rememberSaveable(state.scene.id, state.index) { mutableStateOf(initial.longPressTaskId) }

    val parsedX = x.toIntOrNull()
    val parsedY = y.toIntOrNull()
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    val parsedSliderMin = sliderMin.toIntOrNull()
    val parsedSliderMax = sliderMax.toIntOrNull()
    val parsedSliderValue = sliderValue.toIntOrNull()
    val sliderValid = type != SceneElementType.SLIDER ||
        (parsedSliderMin != null && parsedSliderMax != null && parsedSliderValue != null && parsedSliderMin <= parsedSliderMax)
    val canSave = parsedX != null &&
        parsedY != null &&
        parsedWidth != null &&
        parsedHeight != null &&
        parsedX >= 0 &&
        parsedY >= 0 &&
        parsedWidth > 0 &&
        parsedHeight > 0 &&
        sliderValid
    val defaultTextLabel = stringResource(R.string.scene_element_type_text)
    val defaultButtonLabel = stringResource(R.string.scene_element_type_button)
    val defaultSliderLabel = stringResource(R.string.scene_element_type_slider)
    val defaultImageLabel = stringResource(R.string.scene_element_type_image)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.index == null) "Add Element" else "Edit Element") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
            ) {
                SceneElementTypeSelector(
                    selected = type,
                    onSelect = { selected ->
                        type = selected
                        val defaults = SceneElementDrafts.defaultElement(state.scene, selected)
                        width = defaults.widthDp.toString()
                        height = defaults.heightDp.toString()
                        label = defaults.config["label"] ?: defaults.config["text"] ?: ""
                        sliderMin = defaults.config["min"] ?: "0"
                        sliderMax = defaults.config["max"] ?: "100"
                        sliderValue = defaults.config["value"] ?: "50"
                        imageSource = defaults.config["source"] ?: ""
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    NumberField(stringResource(R.string.label_x_dp), x, { x = it.filter(Char::isDigit).take(4) }, parsedX == null, Modifier.weight(1f))
                    NumberField(stringResource(R.string.label_y_dp), y, { y = it.filter(Char::isDigit).take(4) }, parsedY == null, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    NumberField(
                        label = stringResource(R.string.label_width_dp),
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit).take(4) },
                        isError = parsedWidth == null || parsedWidth <= 0,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = stringResource(R.string.label_height_dp),
                        value = height,
                        onValueChange = { height = it.filter(Char::isDigit).take(4) },
                        isError = parsedHeight == null || parsedHeight <= 0,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (type) {
                    SceneElementType.TEXT -> OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(80) },
                        label = { Text(stringResource(R.string.label_text)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SceneElementType.BUTTON -> OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(48) },
                        label = { Text(stringResource(R.string.label_button_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SceneElementType.SLIDER -> {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(48) },
                            label = { Text(stringResource(R.string.label_slider_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                            NumberField(stringResource(R.string.label_min), sliderMin, { sliderMin = it.filter(Char::isDigit).take(5) }, parsedSliderMin == null, Modifier.weight(1f))
                            NumberField(stringResource(R.string.label_max), sliderMax, { sliderMax = it.filter(Char::isDigit).take(5) }, parsedSliderMax == null || (parsedSliderMin != null && parsedSliderMax < parsedSliderMin), Modifier.weight(1f))
                            NumberField(stringResource(R.string.label_value), sliderValue, { sliderValue = it.filter(Char::isDigit).take(5) }, parsedSliderValue == null, Modifier.weight(1f))
                        }
                    }

                    SceneElementType.IMAGE -> OutlinedTextField(
                        value = imageSource,
                        onValueChange = { imageSource = it.take(160) },
                        label = { Text(stringResource(R.string.label_image_label_or_uri)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    else -> Unit
                }
                SceneTaskBindingSelector(
                    label = stringResource(R.string.label_tap_task),
                    tasks = tasks,
                    selectedTaskId = tapTaskId,
                    onSelect = { tapTaskId = it },
                )
                SceneTaskBindingSelector(
                    label = stringResource(R.string.label_long_press_task),
                    tasks = tasks,
                    selectedTaskId = longPressTaskId,
                    onSelect = { longPressTaskId = it },
                )
            }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        SceneElement(
                            id = initial.id,
                            type = type,
                            xDp = parsedX ?: 0,
                            yDp = parsedY ?: 0,
                            widthDp = parsedWidth ?: 1,
                            heightDp = parsedHeight ?: 1,
                            config = elementConfig(
                                type = type,
                                label = label,
                                sliderMin = sliderMin,
                                sliderMax = sliderMax,
                                sliderValue = sliderValue,
                                imageSource = imageSource,
                                defaultTextLabel = defaultTextLabel,
                                defaultButtonLabel = defaultButtonLabel,
                                defaultSliderLabel = defaultSliderLabel,
                                defaultImageLabel = defaultImageLabel,
                            ),
                            tapTaskId = tapTaskId,
                            longPressTaskId = longPressTaskId,
                            // Persist the linked task NAME too, so the link survives a re-import that re-ids the task.
                            tapTaskName = tasks.firstOrNull { it.id == tapTaskId }?.name ?: "",
                            longPressTaskName = tasks.firstOrNull { it.id == longPressTaskId }?.name ?: "",
                        ),
                    )
                },
            ) {
                Text(if (state.index == null) stringResource(R.string.action_add_element) else stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SceneElementTypeSelector(
    selected: SceneElementType,
    onSelect: (SceneElementType) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(sceneElementTypeLabel(selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SceneElementDrafts.editableTypes.forEach { type ->
                DropdownMenuItem(
                    text = { Text(sceneElementTypeLabel(type)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SceneTaskBindingSelector(
    label: String,
    tasks: List<Task>,
    selectedTaskId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val selectedTaskLabel = selectedTaskId?.let { taskId ->
        taskNames[taskId] ?: stringResource(R.string.scenes_missing_task_id, taskId)
    } ?: stringResource(R.string.label_none)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.scenes_task_binding_value, label, selectedTaskLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.label_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            tasks.sortedBy { it.name.lowercase() }.forEach { task ->
                DropdownMenuItem(
                    text = { Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(task.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun SceneIssueText(issue: SceneIssue) {
    val color = when (issue.severity) {
        SceneIssueSeverity.ERROR -> MaterialTheme.colorScheme.error
        SceneIssueSeverity.WARNING -> if (androidx.compose.foundation.isSystemInDarkTheme()) {
            DesignSystem.SemanticColor.warningDark
        } else {
            DesignSystem.SemanticColor.warningLight
        }
    }
    Text(issue.message, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun SceneMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SceneStatusPill(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.34f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SceneEditorDialog(
    initial: Scene? = null,
    siblingNames: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (name: String, widthDp: Int, heightDp: Int, bgColor: String?, cornerRadiusDp: Int, scrimAlpha: Int, borderColor: String?, borderWidth: Int, defaultPosition: String, defaultModal: Boolean, defaultDismissOnOutside: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var width by remember { mutableStateOf((initial?.widthDp ?: 320).toString()) }
    var height by remember { mutableStateOf((initial?.heightDp ?: 240).toString()) }
    var bgColor by remember { mutableStateOf(initial?.bgColor ?: "") }
    var corner by remember { mutableStateOf((initial?.cornerRadiusDp ?: 16).toString()) }
    var scrim by remember { mutableStateOf((initial?.scrimAlpha ?: 55).toString()) }
    var borderColor by remember { mutableStateOf(initial?.borderColor ?: "") }
    var border by remember { mutableStateOf((initial?.borderWidth ?: 0).toString()) }
    var defaultPosition by remember { mutableStateOf(initial?.defaultPosition?.lowercase() ?: "center") }
    var defaultModal by remember { mutableStateOf(initial?.defaultModal ?: true) }
    var defaultDismissOnOutside by remember { mutableStateOf(initial?.defaultDismissOnOutside ?: true) }
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    // Scene names are unique within a project (siblingNames = other scenes in the same project).
    val nameClash = name.isNotBlank() && name.trim().lowercase() in siblingNames
    val canSave = name.isNotBlank() && !nameClash && parsedWidth != null && parsedHeight != null && parsedWidth > 0 && parsedHeight > 0

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Create Scene" else "Edit Scene") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Scene name") },
                    isError = nameClash,
                    supportingText = if (nameClash) {
                        { Text("Another scene in this project already has that name.") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumberField("Width dp", width, { width = it.filter(Char::isDigit).take(4) }, parsedWidth == null || parsedWidth <= 0, Modifier.weight(1f))
                    NumberField("Height dp", height, { height = it.filter(Char::isDigit).take(4) }, parsedHeight == null || parsedHeight <= 0, Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Panel", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SceneColorField(label = "Background colour", value = bgColor, onChange = { bgColor = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumberField("Corner (dp)", corner, { corner = it.filter(Char::isDigit).take(2) }, isError = false, modifier = Modifier.weight(1f))
                    NumberField("Scrim %", scrim, { scrim = it.filter(Char::isDigit).take(3) }, isError = false, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SceneColorField(label = "Border colour", value = borderColor, onChange = { borderColor = it })
                    }
                    NumberField("Border (dp)", border, { border = it.filter(Char::isDigit).take(2) }, isError = false, modifier = Modifier.weight(1f))
                }
                Text(
                    "Blanks use the theme: background = black, text/borders = yellow. Scrim is the dim behind a modal scene (0 = clear, 100 = black); border 0 = none.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Default presentation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom").forEach { (key, text) ->
                        FilterChip(
                            selected = defaultPosition == key,
                            onClick = { defaultPosition = key },
                            label = { Text(text) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Modal (block underneath)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = defaultModal, onCheckedChange = { defaultModal = it })
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tap outside dismisses", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = defaultDismissOnOutside, onCheckedChange = { defaultDismissOnOutside = it }, enabled = defaultModal)
                }
                Text(
                    "How this scene shows when scene.show omits position/modal/dismiss. An explicit action argument still wins. Non-modal is a tap-through HUD (can't take text input).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            OutlinedButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        name.trim(),
                        parsedWidth ?: 320,
                        parsedHeight ?: 240,
                        bgColor.trim().ifBlank { null },
                        corner.toIntOrNull()?.coerceAtLeast(0) ?: 16,
                        (scrim.toIntOrNull() ?: 55).coerceIn(0, 100),
                        borderColor.trim().ifBlank { null },
                        border.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        defaultPosition,
                        defaultModal,
                        defaultDismissOnOutside,
                    )
                },
            ) {
                Text(if (initial == null) "Create" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SceneColorField(label: String, value: String, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val parsed = remember(value) {
        runCatching { if (value.isBlank()) null else android.graphics.Color.parseColor(value) }.getOrNull()
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { showPicker = true }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (parsed == null) "Default" else value.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .size(28.dp)
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

@Composable
private fun sceneElementTypeLabel(type: SceneElementType): String = when (type) {
    SceneElementType.BUTTON -> stringResource(R.string.scene_element_type_button)
    SceneElementType.TEXT -> stringResource(R.string.scene_element_type_text)
    SceneElementType.SLIDER -> stringResource(R.string.scene_element_type_slider)
    SceneElementType.IMAGE -> stringResource(R.string.scene_element_type_image)
    else -> type.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
}

@Composable
private fun sceneElementSummary(element: SceneElement): String? = when (element.type) {
    SceneElementType.TEXT -> element.config["text"]?.takeIf { it.isNotBlank() }
    SceneElementType.BUTTON -> element.config["label"]?.takeIf { it.isNotBlank() }
    SceneElementType.SLIDER -> {
        val label = element.config["label"].orEmpty().ifBlank { stringResource(R.string.scene_element_type_slider) }
        val value = element.config["value"].orEmpty().ifBlank { "0" }
        val min = element.config["min"].orEmpty().ifBlank { "0" }
        val max = element.config["max"].orEmpty().ifBlank { "100" }
        stringResource(R.string.scenes_slider_summary, label, value, min, max)
    }
    SceneElementType.IMAGE -> element.config["source"]?.takeIf { it.isNotBlank() }
    else -> null
}

private fun elementConfig(
    type: SceneElementType,
    label: String,
    sliderMin: String,
    sliderMax: String,
    sliderValue: String,
    imageSource: String,
    defaultTextLabel: String,
    defaultButtonLabel: String,
    defaultSliderLabel: String,
    defaultImageLabel: String,
): Map<String, String> = when (type) {
    SceneElementType.TEXT -> mapOf("text" to label.ifBlank { defaultTextLabel })
    SceneElementType.BUTTON -> mapOf("label" to label.ifBlank { defaultButtonLabel })
    SceneElementType.SLIDER -> {
        val min = sliderMin.toIntOrNull() ?: 0
        val max = (sliderMax.toIntOrNull() ?: 100).coerceAtLeast(min)
        val value = (sliderValue.toIntOrNull() ?: min).coerceIn(min, max)
        mapOf(
            "label" to label.ifBlank { defaultSliderLabel },
            "min" to min.toString(),
            "max" to max.toString(),
            "value" to value.toString(),
        )
    }
    SceneElementType.IMAGE -> mapOf("source" to imageSource.ifBlank { defaultImageLabel })
    else -> emptyMap()
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
