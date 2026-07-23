package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElementType
import com.opentasker.core.scenes.AlignmentGuide
import com.opentasker.core.scenes.GuideOrientation
import com.opentasker.core.scenes.SceneAlignmentGuides
import com.opentasker.core.scenes.SceneCanvasElementProjection
import com.opentasker.core.scenes.SceneCanvasProjector
import com.opentasker.core.scenes.SceneEditorMutations
import com.opentasker.ui.theme.DesignSystem

@Composable
internal fun ScenePreviewBox(
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
                Text(
                    stringResource(R.string.empty_scene_elements),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                                            val originalElement = scene.elements[idx]
                                            onMoveSelected(xDp - originalElement.xDp, yDp - originalElement.yDp)
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
    projection: SceneCanvasElementProjection,
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
                    detectDragGestures(
                        // Only add to the selection if this member isn't already selected; toggling
                        // here would deselect a selected element the moment the user starts to drag
                        // it, silently dropping it from a multi-selection group move.
                        onDragStart = { if (!selected) onSelect() },
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
        // A 48dp transparent hit area meets the touch-target minimum while the visible resize glyph
        // stays a compact 14dp square in the corner.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(DesignSystem.ComponentSize.touchTargetMin)
                .pointerInput(scene.id, element.id, projection.width, projection.height, canvasWidth, canvasHeight, density.density) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            resizeDx += dragAmount.x / density.density
                            resizeDy += dragAmount.y / density.density
                        },
                        onDragEnd = {
                            val resized = SceneEditorMutations.resizeElementFromCanvasDelta(
                                scene = scene,
                                element = element,
                                deltaCanvasX = resizeDx,
                                deltaCanvasY = resizeDy,
                                canvasWidth = canvasWidth,
                                canvasHeight = canvasHeight,
                                minimumSizeDp = MIN_ELEMENT_SIZE,
                            )
                            resizeDx = 0f
                            resizeDy = 0f
                            onResizeElement(index, resized.widthDp, resized.heightDp)
                        },
                        onDragCancel = {
                            resizeDx = 0f
                            resizeDy = 0f
                        },
                    )
                },
            contentAlignment = Alignment.BottomEnd,
        ) {
            Surface(
                modifier = Modifier.size(14.dp),
                color = color.copy(alpha = 0.62f),
                shape = RoundedCornerShape(4.dp),
            ) {}
        }
    }
}

private const val MIN_ELEMENT_SIZE = 8
