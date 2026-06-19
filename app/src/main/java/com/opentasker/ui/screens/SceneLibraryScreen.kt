package com.opentasker.ui.screens

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
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
    contentPadding: PaddingValues,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
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

    if (sortedScenes.isEmpty()) {
        SceneEmptyState(
            contentPadding = contentPadding,
            onCreateScene = { showCreateDialog = true },
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SceneOverviewCard(
                scenes = sortedScenes,
                tasks = tasks,
                onCreateScene = { showCreateDialog = true },
            )
        }
        items(sortedScenes, key = { it.id }) { scene ->
            SceneCard(
                scene = scene,
                tasks = tasks,
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
                onDelete = { onDeleteScene(scene) },
            )
        }
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
            shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Scene library empty",
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
                    shape = RoundedCornerShape(12.dp),
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
private fun SceneOverviewCard(
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
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_scene_library), style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${scenes.sumOf { it.elements.size }} element${plural(scenes.sumOf { it.elements.size })} across ${scenes.size} scene${plural(scenes.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SceneStatusPill(
                    label = if (overlayReady) stringResource(R.string.status_overlay_ready) else stringResource(R.string.status_needs_setup),
                    color = if (overlayReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SceneMetric("${scenes.size}", "Scenes", Modifier.weight(1f))
                SceneMetric("${scenes.sumOf { it.elements.size }}", "Elements", Modifier.weight(1f))
                SceneMetric("$errorCount", "Errors", Modifier.weight(1f))
            }
            Button(onClick = onCreateScene, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
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
    onAddElement: () -> Unit,
    onEditElement: (Int, SceneElement) -> Unit,
    onDeleteElement: (Int, SceneElement) -> Unit,
    onMoveElement: (Int, SceneElement) -> Unit,
    onDelete: () -> Unit,
) {
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val issues = remember(scene, tasks) { SceneValidator.validate(scene, tasks) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(scene.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${scene.widthDp} x ${scene.heightDp} dp - ${scene.elements.size} element${plural(scene.elements.size)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.scenes_delete), tint = MaterialTheme.colorScheme.error)
                }
            }

            ScenePreviewBox(
                scene = scene,
                onMoveElement = { index, xDp, yDp ->
                    scene.elements.getOrNull(index)?.let { element ->
                        onMoveElement(index, element.copy(xDp = xDp, yDp = yDp))
                    }
                },
            )

            OutlinedButton(onClick = onAddElement, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = "Add element")
                Spacer(Modifier.width(6.dp))
                Text("Add Element")
            }

            if (scene.elements.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
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
    var dragX by remember(scene.id, element.id, projection.x, projection.y) { mutableFloatStateOf(0f) }
    var dragY by remember(scene.id, element.id, projection.x, projection.y) { mutableFloatStateOf(0f) }
    val color = when (element.type) {
        SceneElementType.BUTTON -> MaterialTheme.colorScheme.primary
        SceneElementType.TEXT -> MaterialTheme.colorScheme.tertiary
        SceneElementType.SLIDER -> MaterialTheme.colorScheme.secondary
        SceneElementType.IMAGE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier
            .offset {
                with(density) {
                    IntOffset(
                        x = (projection.x + dragX).dp.roundToPx(),
                        y = (projection.y + dragY).dp.roundToPx(),
                    )
                }
            }
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
    scene: Scene,
    element: SceneElement,
    taskNames: Map<Long, String>,
    onNudge: (Int, Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
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
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move element left 1 dp")
        }
        IconButton(
            enabled = element.yDp > 0,
            onClick = { onNudge(0, -1) },
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move element up 1 dp")
        }
        IconButton(
            enabled = element.yDp < (scene.heightDp - element.heightDp).coerceAtLeast(0),
            onClick = { onNudge(0, 1) },
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move element down 1 dp")
        }
        IconButton(
            enabled = element.xDp < (scene.widthDp - element.widthDp).coerceAtLeast(0),
            onClick = { onNudge(1, 0) },
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move element right 1 dp")
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
                contentDescription = "Remove element",
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Remove element?") },
        text = {
            Text(
                "This removes the ${element?.type?.let(::sceneElementTypeLabel) ?: "selected"} element from \"${state.scene.name}\".",
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
                Text("Remove Element")
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

    AlertDialog(
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
                        imageSource = defaults.config["source"] ?: ""
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
                            NumberField("Value", sliderValue, { sliderValue = it.filter(Char::isDigit).take(5) }, parsedSliderValue == null, Modifier.weight(1f))
                        }
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
                            config = elementConfig(type, label, sliderMin, sliderMax, sliderValue, imageSource),
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
            com.opentasker.ui.theme.DesignSystem.SemanticColor.warningDark
        } else {
            com.opentasker.ui.theme.DesignSystem.SemanticColor.warningLight
        }
    }
    Text(issue.message, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun SceneMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        shape = RoundedCornerShape(12.dp),
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
    onDismiss: () -> Unit,
    onSave: (String, Int, Int) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var width by rememberSaveable { mutableStateOf("320") }
    var height by rememberSaveable { mutableStateOf("240") }
    val parsedWidth = width.toIntOrNull()
    val parsedHeight = height.toIntOrNull()
    val canSave = name.isNotBlank() && parsedWidth != null && parsedHeight != null && parsedWidth > 0 && parsedHeight > 0

    AlertDialog(
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
            Button(
                enabled = canSave,
                onClick = { onSave(name.trim(), parsedWidth ?: 320, parsedHeight ?: 240) },
            ) {
                Text(stringResource(R.string.action_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
): Map<String, String> = when (type) {
    SceneElementType.TEXT -> mapOf("text" to label.ifBlank { "Text" })
    SceneElementType.BUTTON -> mapOf("label" to label.ifBlank { "Button" })
    SceneElementType.SLIDER -> {
        val min = sliderMin.toIntOrNull() ?: 0
        val max = (sliderMax.toIntOrNull() ?: 100).coerceAtLeast(min)
        val value = (sliderValue.toIntOrNull() ?: min).coerceIn(min, max)
        mapOf(
            "label" to label.ifBlank { "Slider" },
            "min" to min.toString(),
            "max" to max.toString(),
            "value" to value.toString(),
        )
    }
    SceneElementType.IMAGE -> mapOf("source" to imageSource.ifBlank { "Image" })
    else -> emptyMap()
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
