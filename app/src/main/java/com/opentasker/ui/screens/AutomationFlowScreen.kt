package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.flow.AutomationFlowGraph
import com.opentasker.core.flow.AutomationFlowGraphBuilder
import com.opentasker.core.flow.AutomationFlowNode
import com.opentasker.core.flow.AutomationFlowNodeKind
import com.opentasker.core.flow.AutomationFlowTarget
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task

@Composable
fun AutomationFlowScreen(
    profiles: List<Profile>,
    tasks: List<Task>,
    contentPadding: PaddingValues,
    expandedFlows: SnapshotStateMap<Long, Boolean>,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit = {},
    onAddContext: (Long) -> Unit = {},
    onAddAction: (Long) -> Unit = {},
) {
    val graphs = remember(profiles, tasks) {
        val tasksById = tasks.associateBy { it.id }
        profiles.map { profile -> AutomationFlowGraphBuilder.build(profile, tasksById) }
    }

    if (profiles.isEmpty()) {
        FlowEmptyState(contentPadding)
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Fork: the "Flow overview" summary card is not shown at the top of the Flow tab (de9f47a had none).
        items(graphs, key = { it.profileId }) { graph ->
            FlowGraphCard(
                graph = graph,
                expanded = expandedFlows[graph.profileId] == true,
                onToggleExpanded = { expandedFlows[graph.profileId] = expandedFlows[graph.profileId] != true },
                onNodeTargetSelected = onNodeTargetSelected,
                onAddContext = onAddContext,
                onAddAction = onAddAction,
            )
        }
    }
}

@Composable
private fun FlowEmptyState(contentPadding: PaddingValues) {
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
                    contentDescription = stringResource(R.string.flow_empty_content_description),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Text(stringResource(R.string.flow_empty_profile_graph_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.flow_empty_profile_graph_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                FlowStatusPill(
                    label = stringResource(R.string.flow_waiting_for_profile),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FlowOverviewCard(
    profiles: List<Profile>,
    tasks: List<Task>,
    graphs: List<AutomationFlowGraph>,
) {
    val contextCount = profiles.sumOf { it.contexts.size }
    val actionCount = tasks.sumOf { it.actions.size }
    val warningCount = graphs.sumOf { it.warnings.size }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.64f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.flow_overview_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.flow_overview_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowStatusPill(
                    label = if (warningCount == 0) stringResource(R.string.status_ready) else stringResource(R.string.label_issue_count, warningCount),
                    color = if (warningCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FlowMetric("${profiles.size}", stringResource(R.string.label_profiles), Modifier.weight(1f))
                FlowMetric("$contextCount", stringResource(R.string.label_contexts), Modifier.weight(1f))
                FlowMetric("$actionCount", stringResource(R.string.label_actions), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlowGraphCard(
    graph: AutomationFlowGraph,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
    onAddContext: (Long) -> Unit,
    onAddAction: (Long) -> Unit,
) {
    val profileNode = graph.nodes.firstOrNull { it.kind == AutomationFlowNodeKind.PROFILE }
        ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics { contentDescription = graph.accessibilitySummary() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Collapsed: profile name + a one-line context/action summary + a chevron; tap to expand into
            // the full graph. Mirrors the per-card folding on the Tasks / Profiles / Scenes tabs.
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(graph.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(
                            R.string.flow_context_action_summary,
                            graph.contextNodes.size,
                            graph.nodes.count { it.kind == AutomationFlowNodeKind.ACTION },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlowStatusPill(
                    label = if (profileNode.muted) stringResource(R.string.status_disabled) else stringResource(R.string.label_enabled),
                    color = if (profileNode.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse flow" else "Expand flow",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                FlowCanvasOverview(
                    graph = graph,
                    profileNode = profileNode,
                    onNodeTargetSelected = onNodeTargetSelected,
                )

                if (graph.contextNodes.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        items(graph.contextNodes, key = { it.id }) { node ->
                            FlowNodeView(
                                node = node,
                                onNodeTargetSelected = onNodeTargetSelected,
                                modifier = Modifier.widthIn(min = 220.dp, max = 280.dp),
                            )
                        }
                    }
                    FlowEdgeLabel(stringResource(R.string.flow_all_context_rules))
                }
                FlowInlineCommand(label = stringResource(R.string.profile_add_context), onClick = { onAddContext(graph.profileId) })

                FlowNodeView(profileNode, onNodeTargetSelected)
                FlowTaskLane(graph, graph.enterTaskNode, stringResource(R.string.flow_enter), onNodeTargetSelected, onAddAction)
                FlowTaskLane(graph, graph.exitTaskNode, stringResource(R.string.flow_exit), onNodeTargetSelected, onAddAction)

                val missingNodes = graph.nodes.filter { it.kind == AutomationFlowNodeKind.MISSING }
                missingNodes.forEach { missingNode ->
                    FlowEdgeLabel(stringResource(R.string.flow_missing_reference))
                    FlowNodeView(missingNode, onNodeTargetSelected)
                }

                if (graph.warnings.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        graph.warnings.forEach { warning ->
                            Text(
                                warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowInlineCommand(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.flow_add_content_description))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FlowCanvasOverview(
    graph: AutomationFlowGraph,
    profileNode: AutomationFlowNode,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    @Suppress("DEPRECATION")
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 2.5f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    val edgeColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
    val lanes = buildList {
        if (graph.contextNodes.isNotEmpty()) add(FlowLaneData(stringResource(R.string.flow_lane_contexts), graph.contextNodes))
        add(FlowLaneData(stringResource(R.string.flow_lane_profile), listOf(profileNode)))
        graph.enterTaskNode?.let { add(FlowLaneData(stringResource(R.string.flow_lane_enter), listOf(it) + graph.actionNodesFor(it.id))) }
        graph.exitTaskNode?.let { add(FlowLaneData(stringResource(R.string.flow_lane_exit), listOf(it) + graph.actionNodesFor(it.id))) }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clipToBounds()
            .transformable(transformState),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
        shape = RoundedCornerShape(com.opentasker.ui.theme.DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val laneHeight = size.height / lanes.size.coerceAtLeast(1)
                val labelWidth = 72f
                for (laneIdx in 0 until lanes.size - 1) {
                    val fromY = laneHeight * laneIdx + laneHeight / 2
                    val toY = laneHeight * (laneIdx + 1) + laneHeight / 2
                    val midX = labelWidth + 40f
                    drawLine(edgeColor, Offset(midX, fromY), Offset(midX, toY), strokeWidth = 2f)
                    drawCircle(edgeColor, 3f, Offset(midX, toY))
                }
                lanes.forEachIndexed { laneIdx, lane ->
                    val y = laneHeight * laneIdx + laneHeight / 2
                    val nodeWidth = (size.width - labelWidth - 16f) / lane.nodes.size.coerceAtLeast(1)
                    for (nodeIdx in 0 until lane.nodes.size - 1) {
                        val fromX = labelWidth + nodeWidth * nodeIdx + nodeWidth / 2
                        val toX = labelWidth + nodeWidth * (nodeIdx + 1) + nodeWidth / 2
                        drawLine(edgeColor, Offset(fromX, y), Offset(toX, y), strokeWidth = 1.5f)
                        drawCircle(edgeColor, 2.5f, Offset(toX, y))
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(4.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                lanes.forEach { lane ->
                    FlowCanvasLane(
                        label = lane.label,
                        nodes = lane.nodes,
                        onNodeTargetSelected = onNodeTargetSelected,
                    )
                }
            }
        }
    }
}

private data class FlowLaneData(
    val label: String,
    val nodes: List<AutomationFlowNode>,
)

@Composable
private fun FlowCanvasLane(
    label: String,
    nodes: List<AutomationFlowNode>,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
) {
    if (nodes.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(68.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        nodes.forEach { node ->
            FlowCanvasNode(node = node, onNodeTargetSelected = onNodeTargetSelected)
        }
    }
}

@Composable
private fun FlowCanvasNode(
    node: AutomationFlowNode,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
) {
    val color = nodeColor(node.kind, node.muted)
    val target = node.target
    Surface(
        modifier = Modifier
            .widthIn(min = 128.dp, max = 184.dp)
            .semantics { contentDescription = node.accessibilityLabel() }
            .then(
                if (target != null) {
                    Modifier.clickable(role = Role.Button) { onNodeTargetSelected(target) }
                } else {
                    Modifier
                }
            ),
        color = color.copy(alpha = if (node.muted) 0.07f else 0.10f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, color.copy(alpha = if (node.muted) 0.20f else 0.30f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                displayTitle(node),
                style = MaterialTheme.typography.labelMedium,
                color = if (node.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            node.condition?.let {
                Text(
                    stringResource(R.string.flow_if),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun FlowTaskLane(
    graph: AutomationFlowGraph,
    taskNode: AutomationFlowNode?,
    label: String,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
    onAddAction: (Long) -> Unit,
) {
    if (taskNode == null) return
    Spacer(Modifier.height(2.dp))
    FlowEdgeLabel(label)
    FlowNodeView(taskNode, onNodeTargetSelected)
    graph.actionNodesFor(taskNode.id).forEachIndexed { index, actionNode ->
        FlowEdgeLabel(graph.incomingEdgeLabel(actionNode.id) ?: if (index == 0) stringResource(R.string.flow_step_label, index + 1) else stringResource(R.string.flow_then))
        FlowNodeView(actionNode, onNodeTargetSelected)
    }
    (taskNode.target as? AutomationFlowTarget.Task)?.taskId?.let { taskId ->
        FlowInlineCommand(label = stringResource(R.string.action_add_step), onClick = { onAddAction(taskId) })
    }
}

@Composable
private fun FlowNodeView(
    node: AutomationFlowNode,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = nodeColor(node.kind, node.muted)
    val target = node.target
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = node.accessibilityLabel() }
            .then(
                if (target != null) {
                    Modifier.clickable(role = Role.Button) { onNodeTargetSelected(target) }
                } else {
                    Modifier
                }
            ),
        color = color.copy(alpha = if (node.muted) 0.08f else 0.12f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, color.copy(alpha = if (node.muted) 0.22f else 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                displayTitle(node),
                style = MaterialTheme.typography.titleSmall,
                color = if (node.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            node.detail?.takeUnless { it.isBlank() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (node.detail?.contains("sub-task") == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.SubdirectoryArrowRight,
                        contentDescription = stringResource(R.string.flow_sub_task_reference),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    FlowStatusPill(
                        label = stringResource(R.string.flow_subflow),
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            node.condition?.let { condition ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.CallSplit,
                        contentDescription = stringResource(R.string.flow_branch_condition),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    FlowStatusPill(
                        label = stringResource(R.string.flow_branch),
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    stringResource(R.string.flow_if_condition, condition),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FlowEdgeLabel(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f)),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FlowMetric(value: String, label: String, modifier: Modifier = Modifier) {
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
private fun FlowStatusPill(
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
private fun nodeColor(kind: AutomationFlowNodeKind, muted: Boolean): Color {
    if (muted) return MaterialTheme.colorScheme.onSurfaceVariant
    return when (kind) {
        AutomationFlowNodeKind.PROFILE -> MaterialTheme.colorScheme.primary
        AutomationFlowNodeKind.CONTEXT -> MaterialTheme.colorScheme.tertiary
        AutomationFlowNodeKind.ENTER_TASK -> MaterialTheme.colorScheme.secondary
        AutomationFlowNodeKind.EXIT_TASK -> MaterialTheme.colorScheme.secondary
        AutomationFlowNodeKind.ACTION -> MaterialTheme.colorScheme.primary
        AutomationFlowNodeKind.MISSING -> MaterialTheme.colorScheme.error
    }
}

private fun displayTitle(node: AutomationFlowNode): String {
    if (node.kind != AutomationFlowNodeKind.ACTION) return node.title
    val actionType = node.detail?.substringBefore(" - ") ?: return node.title
    val metadataName = ActionMetadataRegistry.get(actionType)?.name ?: return node.title
    return if (node.title.startsWith("Step ")) node.title.replace(actionType, metadataName) else node.title
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
