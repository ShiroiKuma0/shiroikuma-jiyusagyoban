package com.opentasker.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.ShortcutManagerCompat
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.model.Project
import com.opentasker.core.model.Task
import com.opentasker.core.storage.ItemGroupEntity
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemePrefs
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point for the launcher's "add a shortcut" flow (Intent.ACTION_CREATE_SHORTCUT). Shows a foldable
 * projects → groups → tasks picker; choosing a task returns a home-screen shortcut that runs that task,
 * using the task's saved icon (or 自由作業盤's launcher icon when none is set). Layout (font, spacing,
 * indent, group-box look) is tunable under UI customization → Shortcut picker.
 */
class CreateTaskShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)  // backing out without picking cancels cleanly
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                // A large floating dialog: a dimmed scrim (tap outside = cancel) with a tall+wide,
                // yellow-rounded-framed card in the middle — not a fullscreen page.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .fillMaxHeight(0.9f)
                            // Absorb taps so tapping inside the card doesn't fall through to the scrim.
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        TaskPickerScreen(onPick = ::finishWithShortcut, onCancel = ::finish)
                    }
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

// A project's contents as a tree: groups (with nested groups + member tasks) then ungrouped tasks.
private sealed interface PickerEntry
private data class GroupEntry(val group: ItemGroupEntity, val children: List<PickerEntry>, val taskCount: Int) : PickerEntry
private data class TaskEntry(val task: Task) : PickerEntry
private data class ProjectNode(val title: String, val key: Long, val entries: List<PickerEntry>, val taskCount: Int)

@Composable
private fun TaskPickerScreen(onPick: (Task) -> Unit, onCancel: () -> Unit) {
    val prefs by ThemeStore.state.collectAsState()
    val font = remember(prefs.pickerFontFileName) { ThemeStore.fontFamily(prefs.pickerFontFileName) }

    val nodes by produceState<List<ProjectNode>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val db = OpenTaskerApp_NoHilt.db
            val projects: List<Project> = db.projectDao().getAll().map { it.toDomain() }
            val tasks: List<Task> = db.taskDao().getAll().map { it.toDomain() }   // ordered by position, id
            val groups = db.itemGroupDao().getForTab("tasks")                      // ordered by position, name
            val groupIdByKey = db.itemMetaDao().getForTab("tasks").associate { it.itemKey to it.groupId }
            val knownProjectIds = projects.mapTo(HashSet()) { it.id }

            // A project's tree: groups (by position) first — each with sub-groups then member tasks —
            // then the project's ungrouped tasks. Mirrors the app's grouped list; empty groups are
            // skipped; order is preserved (tasks already sorted by position).
            fun entriesFor(projTasks: List<Task>, projGroups: List<ItemGroupEntity>): List<PickerEntry> {
                val liveIds = projGroups.mapTo(HashSet()) { it.id }
                fun groupOf(t: Task): Long? = groupIdByKey[t.id.toString()]?.takeIf { it in liveIds }
                fun parentOf(g: ItemGroupEntity): Long? = g.parentGroupId?.takeIf { it in liveIds }
                fun childrenOf(parent: Long?): List<PickerEntry> {
                    val subs = projGroups.filter { parentOf(it) == parent }.sortedBy { it.position }
                        .mapNotNull { g ->
                            val kids = childrenOf(g.id)
                            val n = kids.sumOf { if (it is GroupEntry) it.taskCount else 1 }
                            if (n > 0) GroupEntry(g, kids, n) else null
                        }
                    val ts = projTasks.filter { groupOf(it) == parent }.map { TaskEntry(it) }
                    return subs + ts
                }
                return childrenOf(null)
            }

            buildList {
                projects.forEach { project ->
                    val projTasks = tasks.filter { it.projectId == project.id }
                    if (projTasks.isNotEmpty()) {
                        val projGroups = groups.filter { it.projectId == project.id }
                        add(ProjectNode(project.name, project.id, entriesFor(projTasks, projGroups), projTasks.size))
                    }
                }
                val unfiled = tasks.filter { it.projectId == null || it.projectId !in knownProjectIds }
                if (unfiled.isNotEmpty()) {
                    add(ProjectNode("Unfiled", UNFILED_KEY, unfiled.map { TaskEntry(it) }, unfiled.size))
                }
            }
        }
    }

    // Projects AND groups start folded; tap a header to expand. Keys: "p:<id>" / "g:<id>".
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    val toggle: (String) -> Unit = { key -> expanded = if (key in expanded) expanded - key else expanded + key }
    val list = nodes

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text("Pick a task", style = MaterialTheme.typography.titleLarge, fontFamily = font)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                list == null -> Unit
                list.isEmpty() -> Text(
                    "No tasks yet. Create one in 白い熊 自由作業盤 first.",
                    fontFamily = font, fontSize = prefs.pickerFontSizeSp.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(prefs.pickerRowPadDp.dp),
                ) {
                    items(list, key = { "p_${it.key}" }) { node ->
                        ProjectBlock(node, expanded, prefs, font, toggle, onPick)
                    }
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),   // yellow rounded frame
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.background,        // black back
                contentColor = MaterialTheme.colorScheme.primary,            // yellow label
            ),
        ) {
            Text("Cancel", fontFamily = font, fontSize = prefs.pickerFontSizeSp.sp)
        }
    }
}

@Composable
private fun ProjectBlock(
    node: ProjectNode, expanded: Set<String>, prefs: ThemePrefs, font: FontFamily?,
    onToggle: (String) -> Unit, onPick: (Task) -> Unit,
) {
    val open = "p:${node.key}" in expanded
    Column(Modifier.fillMaxWidth()) {
        PickerHeader(node.title, node.taskCount, open, isProject = true, prefs, font) { onToggle("p:${node.key}") }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = prefs.pickerIndentDp.dp, top = prefs.pickerRowPadDp.dp),
                verticalArrangement = Arrangement.spacedBy(prefs.pickerRowPadDp.dp),
            ) {
                node.entries.forEach { EntryRow(it, expanded, prefs, font, onToggle, onPick) }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: PickerEntry, expanded: Set<String>, prefs: ThemePrefs, font: FontFamily?,
    onToggle: (String) -> Unit, onPick: (Task) -> Unit,
) {
    when (entry) {
        is GroupEntry -> {
            val open = "g:${entry.group.id}" in expanded
            val shape = RoundedCornerShape(prefs.pickerGroupCornerDp.dp)
            // The folder-box: a bordered, rounded container. Folded → just the header (clearly a closed
            // box); expanded → it grows to enclose its children, so loose siblings can't be mistaken for
            // its contents.
            Column(
                Modifier.fillMaxWidth()
                    .then(if (prefs.pickerGroupBorderDp > 0)
                        Modifier.border(prefs.pickerGroupBorderDp.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
                    .clip(shape),
            ) {
                PickerHeader(entry.group.name, entry.taskCount, open, isProject = false, prefs, font) { onToggle("g:${entry.group.id}") }
                if (open) {
                    // Deeper indent for a group's expanded contents, so tasks sit clearly inside the box.
                    Column(
                        Modifier.fillMaxWidth().padding(start = (prefs.pickerIndentDp + 16).dp, bottom = prefs.pickerRowPadDp.dp),
                        verticalArrangement = Arrangement.spacedBy(prefs.pickerRowPadDp.dp),
                    ) {
                        entry.children.forEach { EntryRow(it, expanded, prefs, font, onToggle, onPick) }
                    }
                }
            }
        }
        is TaskEntry -> TaskRow(entry.task, prefs, font) { onPick(entry.task) }
    }
}

@Composable
private fun PickerHeader(
    title: String, count: Int, open: Boolean, isProject: Boolean, prefs: ThemePrefs, font: FontFamily?,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = if (isProject) 0.dp else 10.dp, top = prefs.pickerRowPadDp.dp + 4.dp, bottom = prefs.pickerRowPadDp.dp + 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isProject) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size((prefs.pickerFontSizeSp + 2).dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            fontFamily = font,
            fontSize = (prefs.pickerFontSizeSp + if (isProject) 3 else 1).sp,
            fontWeight = if (isProject) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$count", fontFamily = font, fontSize = prefs.pickerFontSizeSp.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (open) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TaskRow(task: Task, prefs: ThemePrefs, font: FontFamily?, onClick: () -> Unit) {
    val bitmap = remember(task.iconPath) { TaskIconStore.loadBitmap(task.iconPath) }
    val iconDp = (prefs.pickerFontSizeSp + 6).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = prefs.pickerRowPadDp.dp, bottom = prefs.pickerRowPadDp.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Custom icon if set, else nothing — no misleading play/arrow glyph.
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(iconDp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(task.name, fontFamily = font, fontSize = prefs.pickerFontSizeSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val UNFILED_KEY = -1L
