package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        item {
            FlowOverviewCard(
                profiles = profiles,
                tasks = tasks,
                graphs = graphs,
            )
        }
        items(graphs, key = { it.profileId }) { graph ->
            FlowGraphCard(
                graph = graph,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No profiles to graph", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create or install a profile before opening the flow view.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Flow overview", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Read-only profile graph generated from the active Room data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowStatusPill(
                    label = if (warningCount == 0) "Ready" else "$warningCount issue${plural(warningCount)}",
                    color = if (warningCount == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FlowMetric("${profiles.size}", "Profiles", Modifier.weight(1f))
                FlowMetric("$contextCount", "Contexts", Modifier.weight(1f))
                FlowMetric("$actionCount", "Actions", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlowGraphCard(
    graph: AutomationFlowGraph,
    onNodeTargetSelected: (AutomationFlowTarget) -> Unit,
    onAddContext: (Long) -> Unit,
    onAddAction: (Long) -> Unit,
) {
    val profileNode = graph.nodes.first { it.kind == AutomationFlowNodeKind.PROFILE }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = graph.accessibilitySummary() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(graph.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${graph.contextNodes.size} context${plural(graph.contextNodes.size)} - ${graph.nodes.count { it.kind == AutomationFlowNodeKind.ACTION }} action${plural(graph.nodes.count { it.kind == AutomationFlowNodeKind.ACTION })}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowStatusPill(
                    label = if (profileNode.muted) "Disabled" else "Enabled",
                    color = if (profileNode.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }

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
                FlowEdgeLabel("all context rules")
            }
            FlowInlineCommand(label = "Add Context", onClick = { onAddContext(graph.profileId) })

            FlowNodeView(profileNode, onNodeTargetSelected)
            FlowTaskLane(graph, graph.enterTaskNode, "enter", onNodeTargetSelected, onAddAction)
            FlowTaskLane(graph, graph.exitTaskNode, "exit", onNodeTargetSelected, onAddAction)

            val missingNodes = graph.nodes.filter { it.kind == AutomationFlowNodeKind.MISSING }
            missingNodes.forEach { missingNode ->
                FlowEdgeLabel("missing reference")
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

@Composable
private fun FlowInlineCommand(
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = "Add")
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
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowCanvasLane(
            label = "Contexts",
            nodes = graph.contextNodes,
            onNodeTargetSelected = onNodeTargetSelected,
        )
        FlowCanvasLane(
            label = "Profile",
            nodes = listOf(profileNode),
            onNodeTargetSelected = onNodeTargetSelected,
        )
        graph.enterTaskNode?.let { taskNode ->
            FlowCanvasLane(
                label = "Enter",
                nodes = listOf(taskNode) + graph.actionNodesFor(taskNode.id),
                onNodeTargetSelected = onNodeTargetSelected,
            )
        }
        graph.exitTaskNode?.let { taskNode ->
            FlowCanvasLane(
                label = "Exit",
                nodes = listOf(taskNode) + graph.actionNodesFor(taskNode.id),
                onNodeTargetSelected = onNodeTargetSelected,
            )
        }
    }
}

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
                    Modifier.clickable { onNodeTargetSelected(target) }
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
                    "if",
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
        FlowEdgeLabel(graph.incomingEdgeLabel(actionNode.id) ?: if (index == 0) "step ${index + 1}" else "then")
        FlowNodeView(actionNode, onNodeTargetSelected)
    }
    (taskNode.target as? AutomationFlowTarget.Task)?.taskId?.let { taskId ->
        FlowInlineCommand(label = "Add Step", onClick = { onAddAction(taskId) })
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
                    Modifier.clickable { onNodeTargetSelected(target) }
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
            node.condition?.let { condition ->
                FlowStatusPill(
                    label = "Conditional",
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    "if $condition",
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
