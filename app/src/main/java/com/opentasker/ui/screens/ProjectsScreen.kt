package com.opentasker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Project
import com.opentasker.core.model.ProjectFilter

/** Color presets offered when creating/editing a project (plus "no color"). */
private val PROJECT_COLOR_PRESETS = listOf(
    0xFFFFFF00.toInt(), // yellow (theme accent)
    0xFFFF6B6B.toInt(), // red
    0xFF4FC3F7.toInt(), // blue
    0xFF81C784.toInt(), // green
    0xFFBA68C8.toInt(), // purple
    0xFFFFB74D.toInt(), // orange
)

/** Top-bar chip + dropdown that picks the active project filter (or opens management). */
@Composable
fun ProjectSwitcher(
    filter: ProjectFilter,
    projects: List<Project>,
    onSelect: (ProjectFilter) -> Unit,
    onManage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (filter) {
        ProjectFilter.All -> "All"
        ProjectFilter.Unfiled -> "Unfiled"
        is ProjectFilter.Of -> projects.firstOrNull { it.id == filter.projectId }?.name ?: "Project"
    }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                label,
                modifier = Modifier.padding(horizontal = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose project")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FilterItem("All", filter == ProjectFilter.All) { onSelect(ProjectFilter.All); expanded = false }
            FilterItem("Unfiled", filter == ProjectFilter.Unfiled) { onSelect(ProjectFilter.Unfiled); expanded = false }
            projects.forEach { project ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorDot(project.color)
                            Text(project.name)
                        }
                    },
                    leadingIcon = {
                        if (filter is ProjectFilter.Of && filter.projectId == project.id) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected")
                        }
                    },
                    onClick = { onSelect(ProjectFilter.Of(project.id)); expanded = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage projects…") },
                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                onClick = { onManage(); expanded = false },
            )
        }
    }
}

@Composable
private fun FilterItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected") },
        onClick = onClick,
    )
}

/** Full-screen management page: create / rename / recolor / reorder / delete projects. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsManagementScreen(
    projects: List<Project>,
    memberCount: (Long) -> Int,
    onBack: () -> Unit,
    onCreate: (String, Int?) -> Unit,
    onUpdate: (Project) -> Unit,
    onDelete: (Project, Boolean) -> Unit,
    onMoveUp: (Project) -> Unit,
    onMoveDown: (Project) -> Unit,
    onExportProject: (Project) -> Unit,
) {
    BackHandler(onBack = onBack)
    var editing by remember { mutableStateOf<Project?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("New", modifier = Modifier.padding(start = 4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No projects yet. Create one to group profiles, tasks, and scenes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    val isFirst = project.id == projects.first().id
                    val isLast = project.id == projects.last().id
                    ProjectManagementRow(
                        project = project,
                        members = memberCount(project.id),
                        isFirst = isFirst,
                        isLast = isLast,
                        onMoveUp = { onMoveUp(project) },
                        onMoveDown = { onMoveDown(project) },
                        onEdit = { editing = project },
                        onDelete = { deleting = project },
                        onExport = { onExportProject(project) },
                    )
                }
            }
        }
    }

    if (creating) {
        ProjectEditDialog(
            initial = null,
            onDismiss = { creating = false },
            onConfirm = { name, color -> onCreate(name, color); creating = false },
        )
    }
    editing?.let { project ->
        ProjectEditDialog(
            initial = project,
            onDismiss = { editing = null },
            onConfirm = { name, color ->
                onUpdate(project.copy(name = name, color = color))
                editing = null
            },
        )
    }
    deleting?.let { project ->
        DeleteProjectDialog(
            project = project,
            members = memberCount(project.id),
            onDismiss = { deleting = null },
            onReassign = { onDelete(project, false); deleting = null },
            onDeleteItems = { onDelete(project, true); deleting = null },
        )
    }
}

@Composable
private fun ProjectManagementRow(
    project: Project,
    members: Int,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorDot(project.color)
        Column(Modifier.weight(1f)) {
            Text(project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$members item${if (members == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onExport) { Icon(Icons.Filled.Upload, contentDescription = "Export project") }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ProjectEditDialog(
    initial: Project?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var color by remember { mutableStateOf(initial?.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New project" else "Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectableDot(color = null, selected = color == null) { color = null }
                    PROJECT_COLOR_PRESETS.forEach { preset ->
                        SelectableDot(color = preset, selected = color == preset) { color = preset }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) {
                Text(if (initial == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteProjectDialog(
    project: Project,
    members: Int,
    onDismiss: () -> Unit,
    onReassign: () -> Unit,
    onDeleteItems: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${project.name}\"?") },
        text = {
            Text(
                if (members == 0) {
                    "This project has no items."
                } else {
                    "This project has $members item${if (members == 1) "" else "s"}. Move them to Unfiled, or delete them too."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onReassign) { Text("Move to Unfiled") }
        },
        dismissButton = {
            if (members > 0) {
                TextButton(
                    onClick = onDeleteItems,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete items too") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Move-to-project picker shown for a single profile / task / scene. */
@Composable
fun ProjectPickerDialog(
    title: String,
    projects: List<Project>,
    currentProjectId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PickerRow("Unfiled", null, currentProjectId == null) { onPick(null) }
                projects.forEach { project ->
                    PickerRow(project.name, project.color, currentProjectId == project.id) { onPick(project.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PickerRow(label: String, color: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ColorDot(color)
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Current", tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ColorDot(color: Int?) {
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(if (color != null) Color(color) else Color.Transparent)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@Composable
private fun SelectableDot(color: Int?, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (color != null) Color(color) else MaterialTheme.colorScheme.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Text("∅", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
