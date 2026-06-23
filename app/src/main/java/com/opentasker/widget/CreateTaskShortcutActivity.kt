package com.opentasker.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutManagerCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.Project
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point for the launcher's "add a shortcut" flow (Intent.ACTION_CREATE_SHORTCUT). Shows a foldable
 * projects → tasks picker; choosing a task returns a home-screen shortcut that runs that task directly,
 * using the task's saved icon (or 自由作業盤's launcher icon when none is set).
 */
class CreateTaskShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out without picking cancels the shortcut creation cleanly.
        setResult(RESULT_CANCELED)
        enableEdgeToEdge()

        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TaskPickerScreen(onPick = ::finishWithShortcut)
                }
            }
        }
    }

    private fun finishWithShortcut(task: Task) {
        val shortcut = TaskShortcutHelper.buildShortcut(this, task)
        setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcut))
        finish()
    }
}

private data class ProjectGroup(val title: String, val key: Long, val tasks: List<Task>)

@Composable
private fun TaskPickerScreen(onPick: (Task) -> Unit) {
    val context = LocalContext.current
    val groups by produceState<List<ProjectGroup>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val db = OpenTaskerApp_NoHilt.db
            val projects: List<Project> = db.projectDao().getAll().map { it.toDomain() }
            val tasks: List<Task> = db.taskDao().getAll().map { it.toDomain() }
            val knownProjectIds = projects.map { it.id }.toSet()
            buildList {
                projects.forEach { project ->
                    val owned = tasks.filter { it.projectId == project.id }
                    if (owned.isNotEmpty()) add(ProjectGroup(project.name, project.id, owned))
                }
                // Tasks with no project, or pointing at a project that no longer exists.
                val unfiled = tasks.filter { it.projectId == null || it.projectId !in knownProjectIds }
                if (unfiled.isNotEmpty()) add(ProjectGroup("Unfiled", UNFILED_KEY, unfiled))
            }
        }
    }

    // All projects start folded; tap a project header to expand it.
    var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val list = groups

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Pick a task", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(12.dp))
        when {
            list == null -> Unit
            list.isEmpty() -> Text(
                "No tasks yet. Create one in 白い熊 自由作業盤 first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.forEach { group ->
                    val isOpen = group.key in expanded
                    item(key = "group_${group.key}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expanded = if (group.key in expanded) expanded - group.key else expanded + group.key
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${group.tasks.size}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                if (isOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (isOpen) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isOpen) {
                        items(group.tasks, key = { "task_${group.key}_${it.id}" }) { task ->
                            TaskPickerRow(task = task, onClick = { onPick(task) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskPickerRow(task: Task, onClick: () -> Unit) {
    val bitmap = remember(task.iconPath) { TaskIconStore.loadBitmap(task.iconPath) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(task.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val UNFILED_KEY = -1L
