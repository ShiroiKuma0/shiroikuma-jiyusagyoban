package com.opentasker.ui.screens

import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import com.opentasker.ui.components.ReorderableRow
import com.opentasker.ui.components.RgbaColorPickerDialog
import com.opentasker.ui.components.SelectionBar
import com.opentasker.ui.components.SelectionCheck
import com.opentasker.ui.components.rememberListReorderState
import com.opentasker.ui.components.selectableItem
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.SceneCanvasProjector
import com.opentasker.core.scenes.SceneElementDrafts
import com.opentasker.core.scenes.SceneIssue
import com.opentasker.core.scenes.SceneIssueSeverity
import com.opentasker.core.scenes.SceneValidator

@Composable
fun SceneLibraryScreen(
    scenes: List<Scene>,
    tasks: List<Task>,
    onCreateScene: (String, Int, Int) -> Unit,
    onUpdateScene: (Scene, String) -> Unit,
    onDeleteScene: (Scene) -> Unit,
    onMoveScene: (Scene) -> Unit,
    onExportScene: (Scene) -> Unit,
    manualSort: Boolean,
    onReorder: (List<Scene>) -> Unit,
    selectedIds: Set<Long>,
    onLongPressScene: (Scene) -> Unit,
    onToggleSelectScene: (Scene) -> Unit,
    onSelectAllScenes: () -> Unit,
    onClearSceneSelection: () -> Unit,
    onDeleteSelectedScenes: () -> Unit,
    onMoveSelectedToProject: () -> Unit,
    createSignal: Int,
    hiddenByFilter: Int,
    expandedScenes: SnapshotStateMap<Long, Boolean>,
    contentPadding: PaddingValues,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var elementEditor by remember { mutableStateOf<SceneElementEditorState?>(null) }
    var pendingElementDelete by remember { mutableStateOf<SceneElementEditorState?>(null) }
    // Order comes from the ViewModel (Alphabetical or Manual); don't re-sort here.
    val sortedScenes = scenes

    // "New scene" lives in the tab's + menu (TabActionsFab); a tick of [createSignal] opens the dialog.
    LaunchedEffect(createSignal) {
        if (createSignal > 0) showCreateDialog = true
    }

    if (showCreateDialog) {
        SceneEditorDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { name, widthDp, heightDp ->
                onCreateScene(name, widthDp, heightDp)
                showCreateDialog = false
            },
        )
    }

    elementEditor?.let { state ->
        SceneElementEditorDialog(
            state = state,
            tasks = tasks,
            onDismiss = { elementEditor = null },
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
                elementEditor = null
            },
        )
    }

    pendingElementDelete?.let { state ->
        SceneElementDeleteDialog(
            state = state,
            onDismiss = { pendingElementDelete = null },
            onConfirm = {
                val index = state.index
                if (index != null) {
                    onUpdateScene(
                        state.scene.copy(elements = state.scene.elements.filterIndexed { i, _ -> i != index }),
                        "Element removed",
                    )
                }
                pendingElementDelete = null
            },
        )
    }

    if (sortedScenes.isEmpty()) {
        SceneEmptyState(
            contentPadding = contentPadding,
            hiddenByFilter = hiddenByFilter,
            onCreateScene = { showCreateDialog = true },
        )
        return
    }

    val listState = rememberLazyListState()
    val reorder = rememberListReorderState()
    val selectionActive = selectedIds.isNotEmpty()
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (selectionActive) {
            SelectionBar(
                count = selectedIds.size,
                total = sortedScenes.size,
                onSelectAll = onSelectAllScenes,
                onClear = onClearSceneSelection,
                onDelete = onDeleteSelectedScenes,
                onMoveToProject = onMoveSelectedToProject,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sortedScenes, key = { it.id }) { scene ->
                ReorderableRow(reorder, listState, sortedScenes, scene, { it.id }, manualSort && !selectionActive, onReorder) {
                    SceneCard(
                        scene = scene,
                        tasks = tasks,
                        selectionActive = selectionActive,
                        selected = scene.id in selectedIds,
                        expanded = expandedScenes[scene.id] == true,
                        onToggleExpanded = { expandedScenes[scene.id] = expandedScenes[scene.id] != true },
                        onLongPress = { onLongPressScene(scene) },
                        onToggleSelect = { onToggleSelectScene(scene) },
                    onAddElement = { elementEditor = SceneElementEditorState(scene = scene) },
                    onEditElement = { index, element -> elementEditor = SceneElementEditorState(scene, index, element) },
                    onDeleteElement = { index, element -> pendingElementDelete = SceneElementEditorState(scene, index, element) },
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
                    onDelete = { onDeleteScene(scene) },
                    onMoveToProject = { onMoveScene(scene) },
                    onExportToBundle = { onExportScene(scene) },
                    )
                }
            }
        }
    }
}

private data class SceneElementEditorState(
    val scene: Scene,
    val index: Int? = null,
    val element: SceneElement? = null,
)

@Composable
private fun SceneEmptyState(
    contentPadding: PaddingValues,
    hiddenByFilter: Int,
    onCreateScene: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No scenes yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create a panel before adding overlay elements.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hiddenByFilter > 0) {
                Text(
                    "$hiddenByFilter scene${plural(hiddenByFilter)} ${if (hiddenByFilter == 1) "is" else "are"} filed under another project — switch the project filter (top-right) to see ${if (hiddenByFilter == 1) "it" else "them"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(onClick = onCreateScene) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Create Scene")
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
    onDelete: () -> Unit,
    onMoveToProject: () -> Unit,
    onExportToBundle: () -> Unit,
) {
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val issues = remember(scene, tasks) { SceneValidator.validate(scene, tasks) }

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().selectableItem(
                    selectionActive = selectionActive,
                    onLongPress = onLongPress,
                    onToggleSelect = onToggleSelect,
                    onTapNormal = onToggleExpanded,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                }
                Column(Modifier.weight(1f)) {
                    Text(scene.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${scene.widthDp} x ${scene.heightDp} dp - ${scene.elements.size} element${plural(scene.elements.size)}" +
                            if (!expanded && issues.isNotEmpty()) " - ${issues.size} issue${plural(issues.size)}" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    IconButton(onClick = onExportToBundle) {
                        Icon(Icons.Filled.Upload, contentDescription = "Export scene")
                    }
                    IconButton(onClick = onMoveToProject) {
                        Icon(Icons.Filled.Folder, contentDescription = "Move scene to project")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete scene", tint = MaterialTheme.colorScheme.error)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse scene" else "Expand scene",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                ScenePreviewBox(
                    scene = scene,
                    onMoveElement = { index, xDp, yDp ->
                        scene.elements.getOrNull(index)?.let { element ->
                            onMoveElement(index, element.copy(xDp = xDp, yDp = yDp))
                        }
                    },
                )

                OutlinedButton(onClick = onAddElement, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Element")
                }

                if (scene.elements.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scene.elements.forEachIndexed { index, element ->
                            SceneElementRow(
                                element = element,
                                taskNames = taskNames,
                                onEdit = { onEditElement(index, element) },
                                onDelete = { onDeleteElement(index, element) },
                            )
                        }
                    }
                }

                if (issues.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Canvas ${scene.widthDp} x ${scene.heightDp} dp", style = MaterialTheme.typography.labelLarge)
            if (scene.elements.isEmpty()) {
                Text("No elements", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .size(width = maxWidth, height = canvasHeight.dp)
                            .clipToBounds(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.70f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            projections.forEachIndexed { index, projection ->
                                SceneCanvasElement(
                                    scene = scene,
                                    index = index,
                                    projection = projection,
                                    canvasWidth = canvasWidth,
                                    canvasHeight = canvasHeight,
                                    onMoveElement = onMoveElement,
                                )
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
) {
    val element = projection.element
    val density = LocalDensity.current
    var dragX by remember(scene.id, element.id, projection.x, projection.y) { mutableStateOf(0f) }
    var dragY by remember(scene.id, element.id, projection.x, projection.y) { mutableStateOf(0f) }
    val color = when (element.type) {
        SceneElementType.BUTTON -> MaterialTheme.colorScheme.primary
        SceneElementType.TEXT -> MaterialTheme.colorScheme.tertiary
        SceneElementType.SLIDER -> MaterialTheme.colorScheme.secondary
        SceneElementType.IMAGE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier
            .offset(x = (projection.x + dragX).dp, y = (projection.y + dragY).dp)
            .size(
                width = projection.width.coerceAtLeast(12f).dp,
                height = projection.height.coerceAtLeast(12f).dp,
            )
            .pointerInput(scene.id, element.id, projection.x, projection.y, canvasWidth, canvasHeight, density.density) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragX += dragAmount.x / density.density
                        dragY += dragAmount.y / density.density
                    },
                    onDragEnd = {
                        val (xDp, yDp) = SceneCanvasProjector.scenePositionForCanvasOffset(
                            scene = scene,
                            element = element,
                            canvasX = projection.x + dragX,
                            canvasY = projection.y + dragY,
                            canvasWidth = canvasWidth,
                            canvasHeight = canvasHeight,
                        )
                        dragX = 0f
                        dragY = 0f
                        onMoveElement(index, xDp, yDp)
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                    },
                )
            },
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.52f)),
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
}

@Composable
private fun SceneElementRow(
    element: SceneElement,
    taskNames: Map<Long, String>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(sceneElementTypeLabel(element.type), style = MaterialTheme.typography.labelLarge)
                sceneElementSummary(element)?.let { summary ->
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "Bounds ${element.xDp},${element.yDp} ${element.widthDp}x${element.heightDp} dp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOfNotNull(
                    element.tapTaskId?.let { "Tap: ${taskNames[it] ?: "missing #$it"}" },
                    element.longPressTaskId?.let { "Long press: ${taskNames[it] ?: "missing #$it"}" },
                ).forEach { binding ->
                    Text(binding, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit element")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete element", tint = MaterialTheme.colorScheme.error)
                }
            }
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
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Remove element?") },
        text = {
            Text(
                "This removes the ${element?.type?.let(::sceneElementTypeLabel) ?: "selected"} element from \"${state.scene.name}\".",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            OutlinedButton(onClick = onConfirm) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
    var type by remember(state) { mutableStateOf(initial.type.takeIf { it in SceneElementDrafts.editableTypes } ?: SceneElementType.BUTTON) }
    var x by remember(state) { mutableStateOf(initial.xDp.toString()) }
    var y by remember(state) { mutableStateOf(initial.yDp.toString()) }
    var width by remember(state) { mutableStateOf(initial.widthDp.toString()) }
    var height by remember(state) { mutableStateOf(initial.heightDp.toString()) }
    var label by remember(state) {
        mutableStateOf(initial.config["label"] ?: initial.config["text"] ?: "")
    }
    var sliderMin by remember(state) { mutableStateOf(initial.config["min"] ?: "0") }
    var sliderMax by remember(state) { mutableStateOf(initial.config["max"] ?: "100") }
    var sliderValue by remember(state) { mutableStateOf(initial.config["value"] ?: "50") }
    var sliderVar by remember(state) { mutableStateOf(initial.config["var"] ?: "") }
    var sliderVertical by remember(state) { mutableStateOf(initial.config["orientation"].equals("vertical", ignoreCase = true)) }
    // Checkbox/Toggle initial on/off state.
    var boolValue by remember(state) { mutableStateOf((initial.config["value"] ?: "false").trim().lowercase() in setOf("true", "1", "on", "yes")) }
    // Edit-text initial text.
    var textValue by remember(state) { mutableStateOf(initial.config["value"] ?: "") }
    var imageSource by remember(state) { mutableStateOf(initial.config["source"] ?: "") }
    // Style (Text / Button): colours as "#AARRGGBB" (blank = element default), size in sp, bold, align.
    var styleTextColor by remember(state) { mutableStateOf(initial.config["textColor"] ?: "") }
    var styleBgColor by remember(state) { mutableStateOf(initial.config["bgColor"] ?: "") }
    var styleSize by remember(state) { mutableStateOf(initial.config["textSize"] ?: "") }
    var styleBold by remember(state) { mutableStateOf((initial.config["bold"] ?: "").trim().lowercase() in setOf("true", "1", "on", "yes")) }
    var styleAlign by remember(state) { mutableStateOf(initial.config["align"]?.trim()?.lowercase() ?: "start") }
    var styleBorderColor by remember(state) { mutableStateOf(initial.config["borderColor"] ?: "") }
    var styleBorderWidth by remember(state) { mutableStateOf(initial.config["borderWidth"] ?: "") }
    var tapTaskId by remember(state) { mutableStateOf(initial.tapTaskId) }
    var longPressTaskId by remember(state) { mutableStateOf(initial.longPressTaskId) }

    val parsedX = x.toIntOrNull()
    val parsedY = y.toIntOrNull()
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    val parsedSliderMin = sliderMin.toIntOrNull()
    val parsedSliderMax = sliderMax.toIntOrNull()
    // Value may be a number (the start position) or a %var that resolves at show time.
    val sliderValueIsVar = sliderValue.trim().startsWith("%")
    val parsedSliderValue = if (sliderValueIsVar) null else sliderValue.toIntOrNull()
    val sliderValid = type != SceneElementType.SLIDER ||
        (parsedSliderMin != null && parsedSliderMax != null && parsedSliderMin <= parsedSliderMax &&
            (sliderValueIsVar || parsedSliderValue != null))
    val canSave = parsedX != null &&
        parsedY != null &&
        parsedWidth != null &&
        parsedHeight != null &&
        parsedX >= 0 &&
        parsedY >= 0 &&
        parsedWidth > 0 &&
        parsedHeight > 0 &&
        sliderValid

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text(if (state.index == null) "Add Element" else "Edit Element") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        sliderVar = defaults.config["var"] ?: ""
                        sliderVertical = defaults.config["orientation"].equals("vertical", ignoreCase = true)
                        boolValue = (defaults.config["value"] ?: "false").trim().lowercase() in setOf("true", "1", "on", "yes")
                        textValue = if (selected == SceneElementType.EDIT_TEXT) (defaults.config["value"] ?: "") else ""
                        imageSource = defaults.config["source"] ?: ""
                        styleTextColor = ""
                        styleBgColor = ""
                        styleSize = ""
                        styleBold = false
                        styleAlign = "start"
                        styleBorderColor = ""
                        styleBorderWidth = ""
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumberField("X dp", x, { x = it.filter(Char::isDigit).take(4) }, parsedX == null, Modifier.weight(1f))
                    NumberField("Y dp", y, { y = it.filter(Char::isDigit).take(4) }, parsedY == null, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    NumberField(
                        label = "Width dp",
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit).take(4) },
                        isError = parsedWidth == null || parsedWidth <= 0,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = "Height dp",
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
                        label = { Text("Text") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SceneElementType.BUTTON -> OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(48) },
                        label = { Text("Button label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SceneElementType.EDIT_TEXT -> {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(48) },
                            label = { Text("Field label / hint") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = textValue,
                            onValueChange = { textValue = it.take(160) },
                            label = { Text("Initial text") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = sliderVar,
                            onValueChange = { sliderVar = it.take(40) },
                            label = { Text("Store value in variable") },
                            placeholder = { Text("%NAME") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "When you press Done or leave the field, the text is written to this variable and the Tap task runs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SceneElementType.SLIDER -> {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(48) },
                            label = { Text("Slider label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            NumberField("Min", sliderMin, { sliderMin = it.filter(Char::isDigit).take(5) }, parsedSliderMin == null, Modifier.weight(1f))
                            NumberField("Max", sliderMax, { sliderMax = it.filter(Char::isDigit).take(5) }, parsedSliderMax == null || (parsedSliderMin != null && parsedSliderMax < parsedSliderMin), Modifier.weight(1f))
                            OutlinedTextField(
                                value = sliderValue,
                                onValueChange = { sliderValue = it.take(16) },
                                label = { Text("Start") },
                                singleLine = true,
                                isError = !(sliderValueIsVar || parsedSliderValue != null),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = sliderVar,
                            onValueChange = { sliderVar = it.take(40) },
                            label = { Text("Store value in variable") },
                            placeholder = { Text("%VOL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Vertical", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = sliderVertical, onCheckedChange = { sliderVertical = it })
                        }
                        Text(
                            "On release the value is written to this variable and the Tap task runs. Set Start to a %var (e.g. %VOL) to open at that variable's current value. Vertical fills the element's height (size it tall).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SceneElementType.CHECKBOX, SceneElementType.TOGGLE -> {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(48) },
                            label = { Text("Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = sliderVar,
                            onValueChange = { sliderVar = it.take(40) },
                            label = { Text("Store value in variable") },
                            placeholder = { Text("%FLAG") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Initially on", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Switch(checked = boolValue, onCheckedChange = { boolValue = it })
                        }
                        Text(
                            "On change the value (true/false) is written to this variable and the Tap task runs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    SceneElementType.IMAGE -> OutlinedTextField(
                        value = imageSource,
                        onValueChange = { imageSource = it.take(160) },
                        label = { Text("Image label or URI") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    else -> Unit
                }
                val boxStyled = type == SceneElementType.TEXT || type == SceneElementType.BUTTON
                val textStyled = boxStyled ||
                    type == SceneElementType.SLIDER || type == SceneElementType.CHECKBOX || type == SceneElementType.TOGGLE
                if (textStyled) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SceneColorField(
                        label = if (type == SceneElementType.TEXT) "Text colour" else "Label colour",
                        value = styleTextColor,
                        onChange = { styleTextColor = it },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        NumberField("Size (sp)", styleSize, { styleSize = it.filter(Char::isDigit).take(3) }, isError = false, modifier = Modifier.weight(1f))
                        Text("Bold", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = styleBold, onCheckedChange = { styleBold = it })
                    }
                    if (boxStyled) {
                        SceneColorField(
                            label = if (type == SceneElementType.BUTTON) "Button colour" else "Background colour",
                            value = styleBgColor,
                            onChange = { styleBgColor = it },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("start" to "Left", "center" to "Center", "end" to "Right").forEach { (key, text) ->
                                FilterChip(
                                    selected = styleAlign == key,
                                    onClick = { styleAlign = key },
                                    label = { Text(text) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                SceneColorField(label = "Border colour", value = styleBorderColor, onChange = { styleBorderColor = it })
                            }
                            NumberField("Border (dp)", styleBorderWidth, { styleBorderWidth = it.filter(Char::isDigit).take(2) }, isError = false, modifier = Modifier.weight(1f))
                        }
                    }
                }
                SceneTaskBindingSelector(
                    label = "Tap task",
                    tasks = tasks,
                    selectedTaskId = tapTaskId,
                    onSelect = { tapTaskId = it },
                )
                SceneTaskBindingSelector(
                    label = "Long-press task",
                    tasks = tasks,
                    selectedTaskId = longPressTaskId,
                    onSelect = { longPressTaskId = it },
                )
            }
        },
        confirmButton = {
            OutlinedButton(
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
                                type, label, sliderMin, sliderMax, sliderValue, sliderVar, sliderVertical, boolValue, textValue, imageSource,
                                SceneElementStyle(styleTextColor, styleBgColor, styleSize, styleBold, styleAlign, styleBorderColor, styleBorderWidth),
                            ),
                            tapTaskId = tapTaskId,
                            longPressTaskId = longPressTaskId,
                        ),
                    )
                },
            ) {
                Text(if (state.index == null) "Add" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SceneElementTypeSelector(
    selected: SceneElementType,
    onSelect: (SceneElementType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
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
    var expanded by remember { mutableStateOf(false) }
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                "$label: ${selectedTaskId?.let { taskNames[it] ?: "missing #$it" } ?: "None"}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("None") },
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
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun SceneIssueText(issue: SceneIssue) {
    val color = when (issue.severity) {
        SceneIssueSeverity.ERROR -> MaterialTheme.colorScheme.error
        SceneIssueSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
    }
    Text(issue.message, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun SceneEditorDialog(
    onDismiss: () -> Unit,
    onSave: (String, Int, Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("320") }
    var height by remember { mutableStateOf("240") }
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    val canSave = name.isNotBlank() && parsedWidth != null && parsedHeight != null && parsedWidth > 0 && parsedHeight > 0

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("Create Scene") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Scene name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = width,
                    onValueChange = { width = it.filter(Char::isDigit).take(4) },
                    label = { Text("Width dp") },
                    isError = parsedWidth == null || parsedWidth <= 0,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = { height = it.filter(Char::isDigit).take(4) },
                    label = { Text("Height dp") },
                    isError = parsedHeight == null || parsedHeight <= 0,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            OutlinedButton(
                enabled = canSave,
                onClick = { onSave(name.trim(), parsedWidth ?: 320, parsedHeight ?: 240) },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun sceneElementTypeLabel(type: SceneElementType): String = when (type) {
    SceneElementType.BUTTON -> "Button"
    SceneElementType.TEXT -> "Text"
    SceneElementType.SLIDER -> "Slider"
    SceneElementType.IMAGE -> "Image"
    else -> type.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
}

private fun sceneElementSummary(element: SceneElement): String? = when (element.type) {
    SceneElementType.TEXT -> element.config["text"]?.takeIf { it.isNotBlank() }
    SceneElementType.BUTTON -> element.config["label"]?.takeIf { it.isNotBlank() }
    SceneElementType.SLIDER -> {
        val label = element.config["label"].orEmpty().ifBlank { "Slider" }
        val value = element.config["value"].orEmpty().ifBlank { "0" }
        val min = element.config["min"].orEmpty().ifBlank { "0" }
        val max = element.config["max"].orEmpty().ifBlank { "100" }
        "$label: $value ($min-$max)"
    }
    SceneElementType.CHECKBOX, SceneElementType.TOGGLE -> {
        val label = element.config["label"].orEmpty().ifBlank { sceneElementTypeLabel(element.type) }
        val on = element.config["value"].orEmpty().trim().lowercase() in setOf("true", "1", "on", "yes")
        "$label (${if (on) "on" else "off"})"
    }
    SceneElementType.EDIT_TEXT -> {
        val label = element.config["label"].orEmpty().ifBlank { "Text field" }
        val value = element.config["value"].orEmpty()
        if (value.isBlank()) label else "$label: $value"
    }
    SceneElementType.IMAGE -> element.config["source"]?.takeIf { it.isNotBlank() }
    else -> null
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

/** Style fields shared by Text/Button elements. Colours are "#AARRGGBB" (blank = default). */
private data class SceneElementStyle(
    val textColor: String,
    val bgColor: String,
    val size: String,
    val bold: Boolean,
    val align: String,
    val borderColor: String,
    val borderWidth: String,
)

/** Adds the non-default style keys to a config builder. */
private fun MutableMap<String, String>.putStyle(style: SceneElementStyle) {
    style.textColor.trim().takeIf { it.isNotBlank() }?.let { put("textColor", it) }
    style.bgColor.trim().takeIf { it.isNotBlank() }?.let { put("bgColor", it) }
    style.size.trim().toIntOrNull()?.takeIf { it > 0 }?.let { put("textSize", it.toString()) }
    if (style.bold) put("bold", "true")
    if (style.align.isNotBlank() && style.align != "start") put("align", style.align)
    style.borderColor.trim().takeIf { it.isNotBlank() }?.let { put("borderColor", it) }
    style.borderWidth.trim().toIntOrNull()?.takeIf { it > 0 }?.let { put("borderWidth", it.toString()) }
}

private fun elementConfig(
    type: SceneElementType,
    label: String,
    sliderMin: String,
    sliderMax: String,
    sliderValue: String,
    sliderVar: String,
    sliderVertical: Boolean,
    boolValue: Boolean,
    textValue: String,
    imageSource: String,
    style: SceneElementStyle,
): Map<String, String> = when (type) {
    SceneElementType.TEXT -> buildMap { put("text", label.ifBlank { "Text" }); putStyle(style) }
    SceneElementType.BUTTON -> buildMap { put("label", label.ifBlank { "Button" }); putStyle(style) }
    SceneElementType.EDIT_TEXT -> buildMap {
        put("label", label.ifBlank { "Text" })
        put("value", textValue)
        sliderVar.trim().removePrefix("%").takeIf { it.isNotBlank() }?.let { put("var", it) }
    }
    SceneElementType.CHECKBOX, SceneElementType.TOGGLE -> buildMap {
        put("label", label.ifBlank { sceneElementTypeLabel(type) })
        put("value", boolValue.toString())
        sliderVar.trim().removePrefix("%").takeIf { it.isNotBlank() }?.let { put("var", it) }
        putStyle(style)
    }
    SceneElementType.SLIDER -> {
        val min = sliderMin.toIntOrNull() ?: 0
        val max = (sliderMax.toIntOrNull() ?: 100).coerceAtLeast(min)
        // A %var start value is kept literal (resolved at show time); a number is clamped to range.
        val value = if (sliderValue.trim().startsWith("%")) sliderValue.trim()
        else (sliderValue.toIntOrNull() ?: min).coerceIn(min, max).toString()
        buildMap {
            put("label", label.ifBlank { "Slider" })
            put("min", min.toString())
            put("max", max.toString())
            put("value", value)
            sliderVar.trim().removePrefix("%").takeIf { it.isNotBlank() }?.let { put("var", it) }
            if (sliderVertical) put("orientation", "vertical")
            putStyle(style)
        }
    }
    SceneElementType.IMAGE -> mapOf("source" to imageSource.ifBlank { "Image" })
    else -> emptyMap()
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
