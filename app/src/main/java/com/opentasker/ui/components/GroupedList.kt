package com.opentasker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opentasker.core.storage.ItemGroupEntity

/** A rendered row in a grouped list: a group header or a member item, each carrying its nesting depth. */
sealed interface GroupRow<out T> {
    data class Header(val group: ItemGroupEntity, val memberCount: Int, val depth: Int) : GroupRow<Nothing>
    data class Member<T>(val item: T, val depth: Int) : GroupRow<T>
}

/** Indent (dp) added per nesting level, so deeper groups/items step further right. */
const val GROUP_INDENT_DP = 56

/** The set of group ids nested (at any depth) under [groupId] — used to forbid nesting a group into itself. */
fun descendantGroupIds(groupId: Long, groups: List<ItemGroupEntity>): Set<Long> {
    val out = mutableSetOf<Long>()
    fun rec(id: Long) {
        groups.filter { it.parentGroupId == id }.forEach { if (out.add(it.id)) rec(it.id) }
    }
    rec(groupId)
    return out
}

/**
 * Order [items] + [groups] into a depth-aware row list: each group (by position) as a header followed by
 * its sub-groups (recursively) and its member items, then any ungrouped/orphaned items at the bottom.
 * [groupIdOf] maps an item's key to its stored groupId (null = top level).
 */
fun <T> buildGroupRows(
    items: List<T>,
    keyOf: (T) -> String,
    groups: List<ItemGroupEntity>,
    groupIdOf: (String) -> Long?,
): List<GroupRow<T>> {
    val liveIds = groups.mapTo(mutableSetOf()) { it.id }
    val itemsByGroup = items.groupBy { groupIdOf(keyOf(it)).takeIf { id -> id in liveIds } }
    val childrenByParent = groups.groupBy { it.parentGroupId?.takeIf { p -> p in liveIds } }
    val rows = mutableListOf<GroupRow<T>>()
    val visited = mutableSetOf<Long>()
    fun emit(g: ItemGroupEntity, depth: Int) {
        if (!visited.add(g.id)) return // cycle guard
        val members = itemsByGroup[g.id].orEmpty()
        val children = childrenByParent[g.id].orEmpty().sortedBy { it.position }
        rows += GroupRow.Header(g, members.size + children.size, depth)
        if (g.expanded) {
            children.forEach { emit(it, depth + 1) }
            members.forEach { rows += GroupRow.Member(it, depth + 1) }
        }
    }
    childrenByParent[null].orEmpty().sortedBy { it.position }.forEach { emit(it, 0) }
    itemsByGroup[null].orEmpty().forEach { rows += GroupRow.Member(it, 0) }
    return rows
}

/** The group operations a list tab needs, pre-filtered to that tab. */
class GroupOps(
    val groups: List<ItemGroupEntity>,
    val groupIdOf: (String) -> Long?,
    val projectId: Long?,
    val setItemGroup: (itemKey: String, groupId: Long?) -> Unit,
    val createGroupForItem: (itemKey: String, name: String) -> Unit,
    val createSubgroup: (parent: ItemGroupEntity, name: String) -> Unit,
    val setGroupParent: (group: ItemGroupEntity, parentId: Long?) -> Unit,
    val toggleGroup: (ItemGroupEntity) -> Unit,
    val renameGroup: (ItemGroupEntity, String) -> Unit,
    val deleteGroup: (ItemGroupEntity) -> Unit,
)

/**
 * Render [items] grouped + nested: foldable headers (indented by depth), indented members, a per-row menu
 * to move items between groups, and a per-header menu to nest/un-nest the group. [onMoveItem] /
 * [onMoveGroup] open the relevant picker (hosted by the caller). [itemContent] draws the item's card.
 */
fun <T> LazyListScope.groupedItems(
    items: List<T>,
    keyOf: (T) -> String,
    ops: GroupOps,
    drag: GroupDragState,
    onMoveItem: (String) -> Unit,
    onMoveGroup: (ItemGroupEntity) -> Unit,
    itemContent: @Composable (T) -> Unit,
) {
    val rows = buildGroupRows(items, keyOf, ops.groups, ops.groupIdOf)
    rows.forEach { row ->
        when (row) {
            is GroupRow.Header -> item(key = "grp:${row.group.id}") {
                GroupHeaderRow(
                    group = row.group,
                    memberCount = row.memberCount,
                    depth = row.depth,
                    highlighted = drag.targetGroupId() == row.group.id,
                    onToggleExpanded = { ops.toggleGroup(row.group) },
                    onRename = { ops.renameGroup(row.group, it) },
                    onDelete = { ops.deleteGroup(row.group) },
                    onMoveInto = { onMoveGroup(row.group) },
                    onMoveOut = { ops.setGroupParent(row.group, null) },
                    onAddSubgroup = { ops.createSubgroup(row.group, it) },
                    modifier = Modifier.onGloballyPositioned {
                        val b = it.boundsInWindow()
                        drag.headerBounds[row.group.id] = b.top..b.bottom
                    },
                )
            }
            is GroupRow.Member -> item(key = "itm:${keyOf(row.item)}") {
                val key = keyOf(row.item)
                val isDragging = drag.draggingKey == key
                Row(
                    modifier = Modifier
                        .padding(start = (row.depth * GROUP_INDENT_DP).dp)
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = drag.offsetY
                                shadowElevation = 12f
                                alpha = 0.95f
                            }
                        },
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) { itemContent(row.item) }
                    // Drag handle: press and drag onto a group header to file the item there. A dedicated
                    // handle avoids clashing with the card's own long-press (multi-select).
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "Drag into a group",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .onGloballyPositioned { drag.recordRowTop(key, it.positionInWindow().y) }
                            .pointerInput(key) {
                                detectDragGestures(
                                    onDragStart = { offset -> drag.start(key, offset.y) },
                                    onDrag = { change, amount -> change.consume(); drag.move(amount.y) },
                                    onDragEnd = { drag.end { target -> if (target != null) ops.setItemGroup(key, target) } },
                                    onDragCancel = { drag.cancel() },
                                )
                            },
                    )
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Group")
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Move into group…") },
                                onClick = { menu = false; onMoveItem(key) },
                            )
                            if (ops.groupIdOf(key) != null) {
                                DropdownMenuItem(
                                    text = { Text("Move out of group") },
                                    onClick = { menu = false; ops.setItemGroup(key, null) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Foldable group header — chevron + name + member count + an overflow menu (rename / delete / nest). */
@Composable
fun GroupHeaderRow(
    group: ItemGroupEntity,
    memberCount: Int,
    depth: Int,
    highlighted: Boolean = false,
    onToggleExpanded: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMoveInto: () -> Unit,
    onMoveOut: () -> Unit,
    onAddSubgroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var addingSub by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .padding(start = (depth * GROUP_INDENT_DP).dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (highlighted) 0.42f else 0.16f))
            .clickable { onToggleExpanded() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (group.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (group.expanded) "Collapse group" else "Expand group",
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            group.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$memberCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Group actions")
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("New subgroup") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { menuOpen = false; addingSub = true },
                )
                DropdownMenuItem(
                    text = { Text("Rename group") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; renaming = true },
                )
                DropdownMenuItem(
                    text = { Text("Move into group…") },
                    onClick = { menuOpen = false; onMoveInto() },
                )
                if (group.parentGroupId != null) {
                    DropdownMenuItem(
                        text = { Text("Move out of group") },
                        onClick = { menuOpen = false; onMoveOut() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete group") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename group") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = { if (text.isNotBlank()) onRename(text.trim()); renaming = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (addingSub) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingSub = false },
            title = { Text("New subgroup in “${group.name}”") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Subgroup name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { if (text.isNotBlank()) onAddSubgroup(text.trim()); addingSub = false }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { addingSub = false }) { Text("Cancel") } },
        )
    }
}

/** Per-tab state for "which item/group is being moved into a group". */
class GroupMoveHost {
    var movingItemKey by mutableStateOf<String?>(null)
    var movingGroup by mutableStateOf<ItemGroupEntity?>(null)
}

@Composable
fun rememberGroupMoveHost(): GroupMoveHost = remember { GroupMoveHost() }

/**
 * Drag-to-file state for one grouped list: the lifted item's key, its live drag offset, and the window
 * bounds of each group header (the drop targets). Long-press a member to lift it; drop over a header to
 * file it there; drop elsewhere cancels (the ⋮ menu remains the way to un-group).
 */
class GroupDragState {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var offsetY by mutableStateOf(0f)
        private set
    private var pointerY by mutableStateOf(0f)
    val headerBounds = mutableStateMapOf<Long, ClosedFloatingPointRange<Float>>()
    private val rowTop = mutableStateMapOf<String, Float>()

    fun recordRowTop(key: String, top: Float) { rowTop[key] = top }
    fun start(key: String, localY: Float) { draggingKey = key; pointerY = (rowTop[key] ?: 0f) + localY; offsetY = 0f }
    fun move(dy: Float) { offsetY += dy; pointerY += dy }
    fun targetGroupId(): Long? =
        if (draggingKey == null) null else headerBounds.entries.firstOrNull { pointerY in it.value }?.key
    fun end(drop: (target: Long?) -> Unit) { drop(targetGroupId()); reset() }
    fun cancel() = reset()
    private fun reset() { draggingKey = null; offsetY = 0f }
}

@Composable
fun rememberGroupDragState(): GroupDragState = remember { GroupDragState() }

/** Renders the move-into-group pickers for [host] (an item picker + a group-nesting picker). Place after the list. */
@Composable
fun GroupMoveDialogs(ops: GroupOps, host: GroupMoveHost) {
    host.movingItemKey?.let { key ->
        GroupPickerDialog(
            groups = ops.groups,
            onPick = { ops.setItemGroup(key, it); host.movingItemKey = null },
            onCreate = { ops.createGroupForItem(key, it); host.movingItemKey = null },
            onDismiss = { host.movingItemKey = null },
        )
    }
    host.movingGroup?.let { g ->
        val excluded = descendantGroupIds(g.id, ops.groups) + g.id
        GroupPickerDialog(
            groups = ops.groups.filter { it.id !in excluded },
            onPick = { ops.setGroupParent(g, it); host.movingGroup = null },
            onCreate = null,
            onDismiss = { host.movingGroup = null },
        )
    }
}

/** Pick a group to move an item (or group) into. [onCreate] (when non-null) offers a new group inline. */
@Composable
fun GroupPickerDialog(
    groups: List<ItemGroupEntity>,
    onPick: (Long) -> Unit,
    onCreate: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move into group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                groups.forEach { g ->
                    Text(
                        g.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(g.id) }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                    )
                }
                if (onCreate != null) {
                    if (creating) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            placeholder = { Text("New group name") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { creating = true }
                                .padding(vertical = 10.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("New group")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating && onCreate != null) {
                TextButton(onClick = { if (newName.isNotBlank()) onCreate(newName.trim()) }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
