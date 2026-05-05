package com.opentasker.ui.screens

import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Scene
import com.opentasker.core.model.SceneElement
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.SceneIssue
import com.opentasker.core.scenes.SceneIssueSeverity
import com.opentasker.core.scenes.SceneValidator

@Composable
fun SceneLibraryScreen(
    scenes: List<Scene>,
    tasks: List<Task>,
    onCreateScene: (String, Int, Int) -> Unit,
    onDeleteScene: (Scene) -> Unit,
    contentPadding: PaddingValues,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val sortedScenes = remember(scenes) { scenes.sortedBy { it.name.lowercase() } }

    if (showCreateDialog) {
        SceneEditorDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { name, widthDp, heightDp ->
                onCreateScene(name, widthDp, heightDp)
                showCreateDialog = false
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
                onDelete = { onDeleteScene(scene) },
            )
        }
    }
}

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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No scenes yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create a panel before adding overlay elements.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreateScene) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Create Scene")
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
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Scene library", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${scenes.sumOf { it.elements.size }} element${plural(scenes.sumOf { it.elements.size })} across ${scenes.size} scene${plural(scenes.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SceneStatusPill(
                    label = if (overlayReady) "Overlay ready" else "Setup needed",
                    color = if (overlayReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SceneMetric("${scenes.size}", "Scenes", Modifier.weight(1f))
                SceneMetric("${scenes.sumOf { it.elements.size }}", "Elements", Modifier.weight(1f))
                SceneMetric("$errorCount", "Errors", Modifier.weight(1f))
            }
            Button(onClick = onCreateScene, modifier = Modifier.fillMaxWidth()) {
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
    onDelete: () -> Unit,
) {
    val taskNames = remember(tasks) { tasks.associate { it.id to it.name } }
    val issues = remember(scene, tasks) { SceneValidator.validate(scene, tasks) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
        shape = RoundedCornerShape(18.dp),
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
                    Icon(Icons.Filled.Delete, contentDescription = "Delete scene", tint = MaterialTheme.colorScheme.error)
                }
            }

            ScenePreviewBox(scene)

            if (scene.elements.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scene.elements.take(6).forEach { element ->
                        SceneElementRow(element, taskNames)
                    }
                    if (scene.elements.size > 6) {
                        Text(
                            "${scene.elements.size - 6} more element${plural(scene.elements.size - 6)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ScenePreviewBox(scene: Scene) {
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
                scene.elements.take(3).forEach { element ->
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)),
                    ) {
                        Text(
                            "${element.type.name.lowercase()} at ${element.xDp},${element.yDp} size ${element.widthDp}x${element.heightDp}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneElementRow(
    element: SceneElement,
    taskNames: Map<Long, String>,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(element.type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
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
    }
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
        shape = RoundedCornerShape(999.dp),
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
    var name by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("320") }
    var height by remember { mutableStateOf("240") }
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
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun plural(count: Int): String = if (count == 1) "" else "s"
