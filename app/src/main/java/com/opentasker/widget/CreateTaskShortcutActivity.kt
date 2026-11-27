package com.opentasker.widget

import com.sysadmindoc.ruledeck.widget.*
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import com.sysadmindoc.ruledeck.ui.components.IconRole
import com.sysadmindoc.ruledeck.ui.components.RoleIcon
import com.sysadmindoc.ruledeck.ui.theme.UiRole
import com.sysadmindoc.ruledeck.ui.theme.role
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.components.OutlinedButton
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
import androidx.lifecycle.lifecycleScope
import com.sysadmindoc.ruledeck.RuleDeckApp_NoHilt
import com.sysadmindoc.ruledeck.core.engine.variables.PersistentGlobalScope
import com.sysadmindoc.ruledeck.core.icons.TaskIconStore
import com.sysadmindoc.ruledeck.core.model.Project
import com.sysadmindoc.ruledeck.core.model.Task
import com.sysadmindoc.ruledeck.core.storage.ItemGroupEntity
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import com.sysadmindoc.ruledeck.ui.theme.ThemePrefs
import com.sysadmindoc.ruledeck.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point for the launcher's "add a shortcut" flow (Intent.ACTION_CREATE_SHORTCUT). Shows a foldable
 * projects → groups → tasks picker; choosing a task returns a home-screen shortcut that runs that task,
 * using the task's saved icon (or 自由作業盤's launcher icon when none is set). Layout (font, spacing,
 * indent, group-box look) is tunable under UI customization → Shortcut picker.
 *
 * ## Named in advance: no picker at all
 *
 * A launcher that already knows which task it wants passes its name in [EXTRA_TASK_NAME] and gets the
 * shortcut back at once, without the picker ever drawing. That is 白い熊 雷起動盤's 「貼り付け」 item: the
 * launcher-task generator (`tasks.launchers`) leaves the names it just made on the clipboard under
 * [TaskShortcutClip.HEADER], and the launcher hands them here one by one. The result is the very same
 * `createShortcutResultIntent` a manual pick returns, so icon, label and target package cannot differ
 * between the two ways in. A name that matches nothing cancels silently; the launcher reports it.
 */
class CreateTaskShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)  // backing out without picking cancels cleanly
        val named = intent.getStringExtra(EXTRA_TASK_NAME)?.trim().orEmpty()
        if (named.isNotEmpty()) {
            lifecycleScope.launch {
                val task = withContext(Dispatchers.IO) {
                    RuleDeckApp_NoHilt.db.taskDao().getByName(named)?.toDomainDecodeResult()?.value
                }
                // Silent on a miss: the launcher may be asking for several names and reports the
                // tally once, in its own Flash — N stacked toasts would only bury it.
                if (task == null) finish() else finishWithShortcut(task)
            }
            return
        }
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            RuleDeckTheme(prefs = themePrefs) {
                // A large floating dialog over a dimmed scrim. It starts below the status bar and
                // ends above the navigation bar, and only Cancel (or Back) closes it: a tap beside
                // it is swallowed (白い熊, 2026-11-27).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .fillMaxHeight()
                            .border(2.dp, role(UiRole.DIALOG_FRAME), RoundedCornerShape(24.dp)),
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
        // 白い熊 picked this task for a shortcut, here on the phone: that is the permission.
        ShortcutGate.setAllowed(applicationContext, task.name, true)
        setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this, shortcut))
        finish()
    }

    companion object {
        /** The task to make a shortcut for, by its (unique) name; skips the picker. */
        const val EXTRA_TASK_NAME = "shiroikuma.jiyusagyoban.extra.TASK_NAME"
    }
}

// A project's contents as a tree: groups (with nested groups + member tasks) then ungrouped tasks.
private sealed interface PickerEntry
private data class GroupEntry(val group: ItemGroupEntity, val children: List<PickerEntry>, val taskCount: Int) : PickerEntry
private data class TaskEntry(val task: Task) : PickerEntry
private data class ProjectNode(val title: String, val key: Long, val entries: List<PickerEntry>, val taskCount: Int)

@Composable
internal fun TaskPickerScreen(onPick: (Task) -> Unit, onCancel: () -> Unit) {
    val prefs by ThemeStore.state.collectAsState()
    val font = remember(prefs.pickerFontFileName) { ThemeStore.fontFamily(prefs.pickerFontFileName) }

    val nodes by produceState<List<ProjectNode>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val db = RuleDeckApp_NoHilt.db
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

    TaskPickerBody(nodes, emptySet(), prefs, font, onPick, onCancel)
}

/**
 * The picker with a small made-up workspace, one project and its group open — for the screenshot
 * preview, which runs no database.
 */
@Composable
internal fun TaskPickerSample() {
    val prefs by ThemeStore.state.collectAsState()
    fun group(id: Long, name: String) = ItemGroupEntity(id = id, projectId = 3, tab = "tasks", name = name)
    fun task(id: Long, name: String) = TaskEntry(Task(id = id, name = name))
    val open = listOf(
        GroupEntry(group(1, "起動無効"), listOf(task(1, "携帯 ⇨ 起動 -- [71][71]"), task(2, "携帯の設定 -- [71][01]"), task(3, "携帯 ⇨ 無効 -- [71][37]")), 3),
        task(4, "携帯 接続 [71][000]"), task(5, "携帯の設定確認 -- [71]"), task(6, "携帯 巡回"),
    )
    val nodes = listOf(
        ProjectNode("健康", 1, emptyList(), 47), ProjectNode("接続", 2, emptyList(), 31),
        ProjectNode("携帯", 3, open, 40), ProjectNode("通知明滅", 4, emptyList(), 25), ProjectNode("電池線", 5, emptyList(), 7),
    )
    TaskPickerBody(nodes, setOf("p:3", "g:1"), prefs, null, onPick = {}, onCancel = {})
}

@Composable
private fun TaskPickerBody(
    nodes: List<ProjectNode>?, startExpanded: Set<String>, prefs: ThemePrefs, font: FontFamily?,
    onPick: (Task) -> Unit, onCancel: () -> Unit,
) {
    // Projects AND groups start folded; tap a header to expand. Keys: "p:<id>" / "g:<id>".
    var expanded by remember { mutableStateOf(startExpanded) }
    val toggle: (String) -> Unit = { key -> expanded = if (key in expanded) expanded - key else expanded + key }
    val list = nodes

    val sizes = remember(prefs) { PickerSizes.from(prefs) }
    val hairline = role(UiRole.LIST_DIVIDER)
    Column(Modifier.fillMaxSize()) {
        // Title with its icon, and one line saying what to do, in the explanation colour.
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, tint = role(UiRole.DIALOG_TITLE))
                Spacer(Modifier.width(10.dp))
                Text(
                    tr("Pick a task"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontFamily = font,
                    color = role(UiRole.DIALOG_TITLE),
                )
            }
            Spacer(Modifier.size(6.dp))
            Text(
                tr("Open a project, then pick the task to make a shortcut for."),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = font,
                color = role(UiRole.DIM_GOLD),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(role(UiRole.DIALOG_FRAME)))
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)) {
            when {
                list == null -> Unit
                list.isEmpty() -> Text(
                    tr("No tasks yet. Create one in 白い熊 自由作業盤 first."),
                    modifier = Modifier.padding(top = 12.dp),
                    fontFamily = font, fontSize = sizes.itemSp.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { "p_${it.key}" }) { node ->
                        ProjectBlock(node, expanded, prefs, font, toggle, onPick)
                        // A hairline under every project, inset to the list.
                        Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(role(UiRole.DIALOG_FRAME)))
        // The dialog's one way out, a regular pill pinned under the list.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel) { Text(tr("Cancel"), fontFamily = font) }
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
        PickerHeader(if (node.key == UNFILED_KEY) tr(node.title) else node.title, node.taskCount, open, isProject = true, prefs, font) { onToggle("p:${node.key}") }
        if (open) Children(node.entries, expanded, prefs, font, onToggle, onPick)
    }
}

/**
 * What a project or a group holds, hung on a rule at its left: the rule is what says "these belong
 * to the row above", so no box is needed round a group. Tasks are plain rows with a hairline between
 * two of them — a frame round each would make a wall of equal pills in a project of a hundred.
 */
@Composable
private fun Children(
    entries: List<PickerEntry>, expanded: Set<String>, prefs: ThemePrefs, font: FontFamily?,
    onToggle: (String) -> Unit, onPick: (Task) -> Unit,
) {
    val sizes = remember(prefs) { PickerSizes.from(prefs) }
    val rule = role(UiRole.DIM_GOLD)
    val hairline = role(UiRole.LIST_DIVIDER)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, bottom = sizes.padDp.dp)
            .drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = prefs.pickerIndentDp.coerceAtLeast(8).dp),
    ) {
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is GroupEntry -> {
                    val open = "g:${entry.group.id}" in expanded
                    PickerHeader(entry.group.name, entry.taskCount, open, isProject = false, prefs, font) { onToggle("g:${entry.group.id}") }
                    if (open) Children(entry.children, expanded, prefs, font, onToggle, onPick)
                }
                is TaskEntry -> TaskRow(entry.task, prefs, font) { onPick(entry.task) }
            }
            if (index < entries.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
        }
    }
}

/**
 * The row of a project or of a group: its icon (layers for a project, a folder for a group, so the
 * two are told apart), the name, the number of tasks in a right-aligned monospace column, and ›
 * when folded or ⌄ when open.
 */
@Composable
private fun PickerHeader(
    title: String, count: Int, open: Boolean, isProject: Boolean, prefs: ThemePrefs, font: FontFamily?,
    onToggle: () -> Unit,
) {
    val sizes = remember(prefs) { PickerSizes.from(prefs) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = (sizes.padDp + 6).dp, bottom = (sizes.padDp + 6).dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isProject) {
            RoleIcon(Icons.Outlined.Layers, IconRole.PERSON, Modifier.size(sizes.iconDp.dp))
        } else {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(sizes.iconDp.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontFamily = font,
            fontSize = sizes.itemSp.sp,
            fontWeight = if (isProject) FontWeight.Bold else FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count",
            modifier = Modifier.padding(start = 8.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = (sizes.itemSp * 0.8f).sp,
            color = role(UiRole.DIM_GOLD),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
            contentDescription = if (open) tr("Collapse") else tr("Expand"),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The two sizes 白い熊 asked to be able to set from a task, and where they come from.
 *
 * 2026-09-22: *"Increase the item and icon size to 2x. Make both settable in 01 設定 task."* The
 * theme page already carried the type size, but the icon was derived from it (`font + 6`), so the
 * two could never be set apart — and neither could be reached from the workspace at all.
 *
 * A **super-global** rather than a project-scoped name, because this picker is the launcher's and
 * belongs to no project: any 01 settings task can publish it, 凍結融解's included, and the name says
 * what it governs rather than who happens to set it. Unset or unreadable leaves the theme page's own
 * value, so nothing has to be defined for the picker to work.
 */
private data class PickerSizes(val itemSp: Int, val iconDp: Int, val padDp: Int) {
    companion object {
        const val VAR_ITEM_SP = "SHORTCUT_PICKER_ITEM_SP"
        const val VAR_ICON_DP = "SHORTCUT_PICKER_ICON_DP"

        /**
         * The gap between lines, settable for the same reason the other two are.
         *
         * 白い熊 asked for it to be minimal (2026-09-22), and a default alone could not deliver
         * that: the theme page's stored value wins over a changed default for anyone who has ever
         * opened the page. A variable set by the 01 settles it either way.
         */
        const val VAR_PAD_DP = "SHORTCUT_PICKER_PAD_DP"

        fun from(prefs: ThemePrefs): PickerSizes {
            // Read straight out of the persisted globals — this is an Activity, so there is no
            // ActionContext to ask, and the widget renderer reaches them the same way.
            val globals = runCatching { PersistentGlobalScope.snapshotAll() }.getOrDefault(emptyMap())
            fun read(name: String, fallback: Int, max: Int) =
                globals[name]?.trim()?.toIntOrNull()?.coerceIn(1, max) ?: fallback

            val item = read(VAR_ITEM_SP, prefs.pickerFontSizeSp, ThemePrefs.PICKER_FONT_MAX)
            val icon = read(
                VAR_ICON_DP,
                prefs.pickerIconDp.takeIf { it > 0 } ?: (prefs.pickerFontSizeSp + 6),
                ThemePrefs.PICKER_ICON_MAX,
            )
            val pad = globals[VAR_PAD_DP]?.trim()?.toIntOrNull()?.coerceIn(0, ThemePrefs.PICKER_PAD_MAX)
                ?: prefs.pickerRowPadDp
            return PickerSizes(item, icon, pad)
        }
    }
}

@Composable
private fun TaskRow(task: Task, prefs: ThemePrefs, font: FontFamily?, onClick: () -> Unit) {
    val bitmap = remember(task.iconPath) { TaskIconStore.loadBitmap(task.iconPath) }
    val sizes = remember(prefs) { PickerSizes.from(prefs) }
    val iconDp = sizes.iconDp.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = (sizes.padDp + 6).dp, bottom = (sizes.padDp + 6).dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The task's own icon where it has one — it is what the shortcut will show — and the "run"
        // icon otherwise, so every row has its mark in the same column (白い熊 chose this on the
        // draft of 2026-11-27; before, a task without an icon had none).
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(iconDp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            RoleIcon(Icons.Filled.PlayCircleOutline, IconRole.CONNECT, Modifier.size(iconDp))
        }
        Spacer(Modifier.width(10.dp))
        Text(task.name, fontFamily = font, fontSize = sizes.itemSp.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val UNFILED_KEY = -1L
