package com.opentasker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.opentasker.core.storage.ItemGroupEntity
import com.opentasker.ui.theme.ThemeStore

/** A rendered row in a grouped list: a group header, the "Ungrouped" drop zone, or a member item. */
sealed interface GroupRow<out T> {
    data class Header(val group: ItemGroupEntity, val memberCount: Int, val depth: Int) : GroupRow<Nothing>
    data class UngroupedHeader(val memberCount: Int) : GroupRow<Nothing>
    data class Member<T>(val item: T, val depth: Int) : GroupRow<T>
}

/** Indent (dp) added per nesting level, so deeper groups/items step further right. */
const val GROUP_INDENT_DP = 56

/** Compact per-level indent for the narrow (folded-cover) panel, where 56dp would eat ~17% of the width. */
const val GROUP_INDENT_NARROW_DP = 14

/** The per-depth indent in effect: compact on a narrow screen so cards keep their width. */
@Composable
fun groupIndentDp(): Int =
    if (com.opentasker.ui.theme.isNarrowScreen()) GROUP_INDENT_NARROW_DP else GROUP_INDENT_DP

/** Sentinel "groupId" for the Ungrouped drop zone — means remove from group (real ids are >= 1). */
const val UNGROUP_TARGET = 0L

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
    dragActive: Boolean = false,
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
    val ungrouped = itemsByGroup[null].orEmpty()
    // A drop-to-ungroup zone: shown when groups exist and there are loose items, or while dragging.
    if (groups.isNotEmpty() && (ungrouped.isNotEmpty() || dragActive)) {
        rows += GroupRow.UngroupedHeader(ungrouped.size)
    }
    ungrouped.forEach { rows += GroupRow.Member(it, 0) }
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
    // Drag-to-REORDER: persist the moved item's group + the tab's whole new member order. Defaulted so the
    // widgets/vars tabs (which don't wire it) still construct a GroupOps. Wired by groupOpsFor → the VM.
    val reorder: (movedKey: String, targetGroupId: Long?, orderedKeys: List<String>) -> Unit = { _, _, _ -> },
    // Drag-to-reorder the GROUPS: the dragged group's siblings in their new order. Same defaulting.
    val reorderGroups: (orderedGroupIds: List<Long>) -> Unit = { },
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
    // Group multi-select (long-press a header to start): defaulted so tabs that don't opt in still compile.
    selectedGroupIds: Set<Long> = emptySet(),
    onLongPressGroup: (ItemGroupEntity) -> Unit = {},
    onToggleSelectGroup: (ItemGroupEntity) -> Unit = {},
    // Drag-to-reorder commit: moved item-key, the group it was dropped into, and the tab's whole new member
    // order (Members only). Defaulted to no-op so tabs that don't wire it (widgets/vars) still compile.
    onReorder: (movedKey: String, targetGroupId: Long?, orderedKeys: List<String>) -> Unit = { _, _, _ -> },
    // Group drag-to-reorder commit: the dragged group's siblings in their new order. Defaulted so tabs
    // that don't wire it still compile.
    onReorderGroups: (orderedGroupIds: List<Long>) -> Unit = { },
    itemContent: @Composable (T) -> Unit,
) {
    val rows = buildGroupRows(items, keyOf, ops.groups, ops.groupIdOf, dragActive = drag.draggingKey != null)
    // The visual Member order (all members, top to bottom) — the basis for the persisted reorder.
    val orderedMemberKeys = rows.mapNotNull { (it as? GroupRow.Member<T>)?.let { m -> keyOf(m.item) } }
    val moved = drag.draggingKey
    // Members minus the one being dragged: the target insertion index is expressed against THIS list.
    val others = orderedMemberKeys.filter { it != moved }
    // Which group each member is filed under (null = top level) — from stored meta, filtered to live groups.
    val liveGroupIds = ops.groups.mapTo(mutableSetOf()) { it.id }
    fun memberGroupId(key: String): Long? = ops.groupIdOf(key)?.takeIf { it in liveGroupIds }

    // Each group's peers, in the order they are drawn. A parent that isn't live leaves its children at
    // top level — the same normalisation buildGroupRows does, so the two agree on who is a sibling.
    val siblingsByParent = ops.groups
        .groupBy { g -> g.parentGroupId?.takeIf { it in liveGroupIds } }
        .mapValues { (_, peers) -> peers.sortedBy { it.position }.map { it.id } }
    fun siblingsOf(group: ItemGroupEntity): List<Long> =
        siblingsByParent[group.parentGroupId?.takeIf { it in liveGroupIds }].orEmpty()

    // Ordered drop anchors used to translate the lifted row's center Y into (targetGroup, insertionIndex).
    // Each header contributes "first slot in its group"; each (non-moved) member contributes "after me".
    val anchors = ArrayList<DropAnchor>()
    var cursor = 0
    rows.forEach { r ->
        when (r) {
            is GroupRow.Header -> anchors += DropAnchor(isHeader = true, gid = r.group.id, key = null, index = cursor)
            is GroupRow.UngroupedHeader -> anchors += DropAnchor(isHeader = true, gid = null, key = null, index = cursor)
            is GroupRow.Member -> {
                val k = keyOf(r.item)
                if (k != moved) {
                    anchors += DropAnchor(isHeader = false, gid = memberGroupId(k), key = k, index = cursor)
                    cursor++
                }
            }
        }
    }
    // Live drop-slot computation: pick the lowest anchor whose threshold the center has crossed. A header's
    // threshold is its bottom (→ first slot in that group); a member's is its midline (→ inserted after it).
    drag.setDropComputer computer@{ centerY ->
        if (anchors.isEmpty()) return@computer null
        var best: Pair<Long?, Int>? = null
        anchors.forEach { a ->
            if (a.isHeader) {
                val b = drag.headerBounds[a.gid ?: UNGROUP_TARGET] ?: return@forEach
                if (centerY >= b.endInclusive) best = a.gid to a.index
            } else {
                val b = drag.rowBounds[a.key] ?: return@forEach
                val mid = (b.start + b.endInclusive) / 2f
                if (centerY >= mid) best = a.gid to (a.index + 1)
            }
        }
        // Above every threshold → land in the first slot of the first (topmost) group/region.
        best ?: (anchors.first().gid to 0)
    }

    rows.forEach { row ->
        when (row) {
            is GroupRow.UngroupedHeader -> item(key = "ungrouped") {
                UngroupedDropZone(
                    memberCount = row.memberCount,
                    highlighted = drag.draggingKey != null && drag.dropGroupId == null,
                    modifier = Modifier.onGloballyPositioned {
                        val b = it.boundsInWindow()
                        drag.headerBounds[UNGROUP_TARGET] = b.top..b.bottom
                    },
                )
            }
            is GroupRow.Header -> item(key = "grp:${row.group.id}") {
                val isDraggingGroup = drag.draggingGroupId == row.group.id
                // Where the lifted group will land: a line above the sibling currently occupying that
                // slot, or below the last sibling when the target is the end of the run.
                val peers = siblingsOf(row.group).filter { it != drag.draggingGroupId }
                val myPeerIndex = peers.indexOf(row.group.id)
                val showAbove = drag.draggingGroupId != null && drag.groupDragMoved &&
                    myPeerIndex >= 0 && drag.groupDropIndex == myPeerIndex
                val showBelow = drag.draggingGroupId != null && drag.groupDragMoved &&
                    row.group.id == peers.lastOrNull() && drag.groupDropIndex == peers.size
                Column(Modifier.zIndex(if (isDraggingGroup) 1f else 0f)) {
                    if (showAbove) DropIndicator(row.depth)
                    GroupHeaderRow(
                        group = row.group,
                        memberCount = row.memberCount,
                        depth = row.depth,
                        highlighted = drag.draggingKey != null && drag.dropGroupId == row.group.id,
                        selected = row.group.id in selectedGroupIds,
                        selectionActive = selectedGroupIds.isNotEmpty(),
                        onToggleSelect = { onToggleSelectGroup(row.group) },
                        onLongPress = { onLongPressGroup(row.group) },
                        onToggleExpanded = { ops.toggleGroup(row.group) },
                        onRename = { ops.renameGroup(row.group, it) },
                        onDelete = { ops.deleteGroup(row.group) },
                        onMoveInto = { onMoveGroup(row.group) },
                        onMoveOut = { ops.setGroupParent(row.group, null) },
                        onAddSubgroup = { ops.createSubgroup(row.group, it) },
                        dragging = isDraggingGroup,
                        dragOffsetY = drag.groupOffsetY,
                        onHoldStart = { drag.startGroupDrag(row.group.id, siblingsOf(row.group)) },
                        onHoldDrag = { drag.moveGroup(it) },
                        // No travel → this was the long-press that has always started a selection.
                        onHoldEnd = {
                            val ordered = drag.endGroupDrag()
                            if (ordered != null) onReorderGroups(ordered) else onLongPressGroup(row.group)
                        },
                        onHoldCancel = { drag.cancelGroupDrag() },
                        modifier = Modifier.onGloballyPositioned {
                            val b = it.boundsInWindow()
                            // Untranslated bounds: onGloballyPositioned sits before the lift's
                            // graphicsLayer, so the drop maths is not fed the offset twice.
                            drag.headerBounds[row.group.id] = b.top..b.bottom
                        },
                    )
                    if (showBelow) DropIndicator(row.depth)
                }
            }
            is GroupRow.Member -> item(key = "itm:${keyOf(row.item)}") {
                val key = keyOf(row.item)
                val isDragging = drag.draggingKey == key
                // Drop-indicator placement: a line ABOVE the member currently sitting at the target slot, or
                // BELOW the last member when the target is the very end of the list.
                val myOtherIndex = others.indexOf(key)
                val showTopIndicator = drag.draggingKey != null && myOtherIndex >= 0 && drag.dropIndex == myOtherIndex
                val showBottomIndicator = drag.draggingKey != null && key == others.lastOrNull() && drag.dropIndex == others.size
                Column(Modifier.zIndex(if (isDragging) 1f else 0f)) {
                    if (showTopIndicator) DropIndicator(row.depth)
                    Row(
                        modifier = Modifier
                            .padding(start = (row.depth * groupIndentDp()).dp)
                            // Record untranslated window bounds (this sits BEFORE graphicsLayer, so the lifted
                            // row's drag offset doesn't corrupt the geometry used to pick the drop slot).
                            .onGloballyPositioned {
                                val b = it.boundsInWindow()
                                drag.recordRowBounds(key, b.top, b.bottom)
                            }
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
                        // Drag handle: press and drag to REPOSITION the item anywhere (up/down, first slot in a
                        // group, any lower slot); dropping within a group's region also files it there. A
                        // dedicated handle avoids clashing with the card's own long-press (multi-select).
                        // The drag handle doubles as the group menu (白い熊): TAP shows Move in/out of group,
                        // DRAG reorders. Merged from a separate ⋮ so the row isn't cluttered with two menus.
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            Icon(
                                Icons.Filled.DragIndicator,
                                contentDescription = "Drag to reorder; tap for group options",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(32.dp)
                                    .pointerInput(key) { detectTapGestures(onTap = { menu = true }) }
                                    .pointerInput(key) {
                                        detectDragGestures(
                                            onDragStart = { drag.start(key) },
                                            onDrag = { change, amount -> change.consume(); drag.move(amount.y) },
                                            onDragEnd = {
                                                val res = drag.endDrag()
                                                if (res != null) {
                                                    val (gid, idx) = res
                                                    val remaining = orderedMemberKeys.filter { it != key }
                                                    val insertAt = idx.coerceIn(0, remaining.size)
                                                    val ordered = remaining.toMutableList().apply { add(insertAt, key) }
                                                    onReorder(key, gid, ordered)
                                                }
                                            },
                                            onDragCancel = { drag.cancel() },
                                        )
                                    },
                            )
                            ThemedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Move into group…") }, onClick = { menu = false; onMoveItem(key) })
                                if (ops.groupIdOf(key) != null) {
                                    DropdownMenuItem(text = { Text("Move out of group") }, onClick = { menu = false; ops.setItemGroup(key, null) })
                                }
                            }
                        }
                    }
                    if (showBottomIndicator) DropIndicator(row.depth)
                }
            }
        }
    }
}

/** One candidate drop position: a group header ("first slot in this group") or a member ("insert after me"). */
private class DropAnchor(val isHeader: Boolean, val gid: Long?, val key: String?, val index: Int)

/** A thin primary-color line marking where a dragged member will land, indented to the target depth. */
@Composable
private fun DropIndicator(depth: Int) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * groupIndentDp()).dp, top = 2.dp, bottom = 2.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary),
    )
}

/** Foldable group header — chevron + name + member count + an overflow menu (rename / delete / nest). */
@Composable
fun GroupHeaderRow(
    group: ItemGroupEntity,
    memberCount: Int,
    depth: Int,
    highlighted: Boolean = false,
    // Multi-select: [selected] = this group is picked; while [selectionActive] a tap toggles instead of folds.
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onToggleExpanded: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMoveInto: () -> Unit,
    onMoveOut: () -> Unit,
    onAddSubgroup: (String) -> Unit,
    // Hold-to-reorder. [onHoldStart] arms the drag (the long press has fired), [onHoldDrag] feeds it
    // the travel, and [onHoldEnd] decides between committing a reorder and falling back to onLongPress.
    dragging: Boolean = false,
    dragOffsetY: Float = 0f,
    onHoldStart: () -> Unit = {},
    onHoldDrag: (Float) -> Unit = {},
    onHoldEnd: () -> Unit = {},
    onHoldCancel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var addingSub by remember { mutableStateOf(false) }
    val themePrefs by ThemeStore.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .padding(start = (depth * groupIndentDp()).dp)
            .fillMaxWidth()
            // The lift rides above the background/clip so the header visibly leaves the list while held.
            .graphicsLayer {
                if (dragging) {
                    translationY = dragOffsetY
                    shadowElevation = 12f
                    alpha = 0.95f
                }
            }
            .clip(RoundedCornerShape(12.dp))
            // Normal header colour is user-settable (ARGB); selection/highlight keep the accent tints so
            // multi-select stays visible. Optional user border (default a thin yellow). 白い熊
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                    else -> Color(themePrefs.groupHeaderColor)
                },
            )
            .then(
                if (themePrefs.groupHeaderBorderWidthDp > 0) {
                    Modifier.border(
                        themePrefs.groupHeaderBorderWidthDp.dp,
                        Color(themePrefs.groupHeaderBorderColor),
                        RoundedCornerShape(12.dp),
                    )
                } else Modifier,
            )
            // Tap = fold (or toggle selection while selecting). The long press is NOT wired here: it
            // belongs to the drag detector below, which is the only place that can tell a hold-and-
            // release (select) from a hold-and-drag (reorder). Wiring onLongClick as well would fire
            // the selection the instant the timeout elapsed, halfway into a reorder.
            .clickable { if (selectionActive) onToggleSelect() else onToggleExpanded() }
            .pointerInput(group.id) {
                detectDragGesturesAfterLongPress(
                    // The buzz IS the affordance: nothing else tells you the hold has been long
                    // enough and the header is now yours to move. It fires the moment the long press
                    // lands — before any travel — so it also confirms the press was registered at all.
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onHoldStart()
                    },
                    onDrag = { change, amount -> change.consume(); onHoldDrag(amount.y) },
                    onDragEnd = { onHoldEnd() },
                    onDragCancel = { onHoldCancel() },
                )
            }
            .padding(horizontal = 12.dp, vertical = themePrefs.groupHeaderVPadDp.dp),
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
        // Compact (32dp) so the ⋮ doesn't force the header taller than its padding setting (白い熊).
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Group actions")
            ThemedDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
 * Drag-to-REORDER state for one grouped list: the lifted item's key, its live drag offset, the window bounds
 * of every group header and member row (the geometry), and the currently computed drop slot ([dropGroupId] +
 * [dropIndex]). Drag a member's handle to lift it; the row floats on top (zIndex) and a drop indicator shows
 * where it will land; releasing repositions it (and files it into whatever group it was dropped in). The ⋮
 * menu remains the explicit way to file/un-group.
 */
class GroupDragState {
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var offsetY by mutableStateOf(0f)
        private set
    // The live drop slot, recomputed as the row is dragged. dropIndex is an insertion index into the member
    // list with the dragged item removed (0..size); -1 = no valid target. dropGroupId = the target group.
    var dropIndex by mutableStateOf(-1)
        private set
    var dropGroupId by mutableStateOf<Long?>(null)
        private set

    // Window bounds of each group header (real group id) + the ungrouped zone (UNGROUP_TARGET) — drop regions.
    val headerBounds = mutableStateMapOf<Long, ClosedFloatingPointRange<Float>>()
    // Window bounds (untranslated) of each member row, keyed by item key.
    val rowBounds = mutableStateMapOf<String, ClosedFloatingPointRange<Float>>()

    // Window-Y of the lifted row's center at the moment the drag started (offsetY is added to it live).
    private var startCenterY = 0f
    // Set each composition by groupedItems: maps a center-Y to (targetGroupId, insertionIndex) or null.
    private var dropComputer: ((Float) -> Pair<Long?, Int>?)? = null

    fun recordRowBounds(key: String, top: Float, bottom: Float) { rowBounds[key] = top..bottom }
    fun setDropComputer(f: (Float) -> Pair<Long?, Int>?) { dropComputer = f }

    fun start(key: String) {
        draggingKey = key
        offsetY = 0f
        val b = rowBounds[key]
        startCenterY = if (b != null) (b.start + b.endInclusive) / 2f else 0f
        recompute()
    }

    fun move(dy: Float) { offsetY += dy; recompute() }

    private fun centerY(): Float = startCenterY + offsetY

    private fun recompute() {
        val target = dropComputer?.invoke(centerY())
        if (target == null) {
            dropIndex = -1; dropGroupId = null
        } else {
            dropGroupId = target.first; dropIndex = target.second
        }
    }

    /** End the drag, returning the committed (targetGroupId, insertionIndex) or null if there's no valid slot. */
    fun endDrag(): Pair<Long?, Int>? {
        val result = if (draggingKey != null && dropIndex >= 0) dropGroupId to dropIndex else null
        reset()
        return result
    }

    fun cancel() = reset()

    private fun reset() { draggingKey = null; offsetY = 0f; dropIndex = -1; dropGroupId = null }

    // -----------------------------------------------------------------------------------------
    // Reordering the GROUPS themselves.
    //
    // A member has a dedicated drag handle; a group header has no room for one (it already carries a
    // chevron, a count and a ⋮ menu) and its whole width is the fold target. So a group is lifted by
    // pressing and HOLDING it — the long press that used to only start a multi-selection now also arms
    // the drag, and which of the two you get is decided by whether you then move: hold-and-release
    // still selects, hold-and-drag reorders. Nothing that worked before stops working.
    //
    // A drag moves a group among its SIBLINGS only. Re-parenting stays on the ⋮ menu, where the target
    // is named explicitly — inferring "did they mean to nest this or just pass over it?" from a
    // position between a parent and its first child is the kind of guess that loses people's layouts.
    // -----------------------------------------------------------------------------------------

    var draggingGroupId by mutableStateOf<Long?>(null)
        private set
    var groupOffsetY by mutableStateOf(0f)
        private set
    /** Insertion index among the siblings with the dragged group removed; -1 = nothing to commit. */
    var groupDropIndex by mutableStateOf(-1)
        private set
    /** True once the finger has actually travelled — what separates a reorder from a plain long-press. */
    var groupDragMoved by mutableStateOf(false)
        private set

    private var groupStartCenterY = 0f
    private var groupSiblings: List<Long> = emptyList()

    /** [siblings] are the dragged group's peers in visual order, INCLUDING it. */
    fun startGroupDrag(id: Long, siblings: List<Long>) {
        draggingGroupId = id
        groupOffsetY = 0f
        groupDragMoved = false
        groupSiblings = siblings.filter { it != id }
        val b = headerBounds[id]
        groupStartCenterY = if (b != null) (b.start + b.endInclusive) / 2f else 0f
        recomputeGroup()
    }

    fun moveGroup(dy: Float) {
        groupOffsetY += dy
        // A few pixels of travel is a shaky finger, not an intent to move the group.
        if (kotlin.math.abs(groupOffsetY) > 8f) groupDragMoved = true
        recomputeGroup()
    }

    private fun recomputeGroup() {
        if (draggingGroupId == null) { groupDropIndex = -1; return }
        val center = groupStartCenterY + groupOffsetY
        var index = 0
        groupSiblings.forEachIndexed { i, sid ->
            val b = headerBounds[sid] ?: return@forEachIndexed
            // Cross the middle of a sibling's HEADER to land after it — its folded-out children do not
            // extend the target, so passing a large expanded group takes one gesture, not a scroll.
            if (center >= (b.start + b.endInclusive) / 2f) index = i + 1
        }
        groupDropIndex = index
    }

    /**
     * End a group drag. Returns the sibling order to persist, or null when the press was a plain
     * long-press (no travel) — the caller then treats it as the selection gesture it always was.
     */
    fun endGroupDrag(): List<Long>? {
        val id = draggingGroupId
        val moved = groupDragMoved
        val index = groupDropIndex
        val siblings = groupSiblings
        resetGroup()
        if (id == null || !moved || index < 0) return null
        return siblings.toMutableList().apply { add(index.coerceIn(0, size), id) }
    }

    fun cancelGroupDrag() = resetGroup()

    private fun resetGroup() {
        draggingGroupId = null
        groupOffsetY = 0f
        groupDropIndex = -1
        groupDragMoved = false
        groupSiblings = emptyList()
    }
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

/** Drop target that removes a dragged item from its group (back to top level). */
@Composable
fun UngroupedDropZone(memberCount: Int, highlighted: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (highlighted) "Drop here to remove from group" else "Ungrouped",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (memberCount > 0) {
            Text(
                "$memberCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
