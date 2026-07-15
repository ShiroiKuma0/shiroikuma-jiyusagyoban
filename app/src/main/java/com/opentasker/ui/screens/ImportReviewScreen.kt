package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentasker.ui.theme.ThemeStore
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Project
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.model.Variable
import com.opentasker.core.transfer.ItemConflictStrategy
import com.opentasker.core.transfer.ProjectImportChoice
import com.opentasker.widget.WidgetTemplate

/** The two synthetic top-level folders that hold items with no (nameable) project. */
private const val UNFILED_FOLDER = "Unfiled"
private const val VARIABLES_FOLDER = "Variables"

/** One incoming item as shown in the tree: its name and whether that name already exists locally. */
private data class ReviewRow(val name: String, val conflict: Boolean)

/**
 * A group node between a TypeGroup and its rows: the (bundle) group the items file into. [name] == null is
 * the synthetic "ungrouped" node (rows with no group — rendered header-less). [isNew] marks a group that
 * does not yet exist locally, so it'll be created on import (drawn like a new project).
 */
private data class GroupNode(val name: String?, val isNew: Boolean, val rows: List<ReviewRow>)

/**
 * A per-type sub-list inside a folder, split into group [nodes]. [tab] is the override-key tab
 * (tasks/profiles/scenes/templates/variables).
 */
private data class TypeGroup(
    val tab: String,
    val label: String,
    val nodes: List<GroupNode>,
)

/** What kind of top-level folder this is — drives the header colour + which pill (if any) it shows. */
private enum class FolderKind { EXISTING, NEW, SPECIAL }

/** A top-level folder in the tree: a project (EXISTING/NEW) or a synthetic Unfiled/Variables (SPECIAL). */
private data class ProjectFolder(
    val name: String,
    val kind: FolderKind,
    val groups: List<TypeGroup>,
)

/**
 * The single near-full-screen "Review import" screen. It diffs the incoming bundle against the current
 * workspace BY NAME (case-insensitive) and presents a **project→items folder tree**: one folder per
 * project the import references (resolving each item's projectId → the bundle's `projects[]` name), plus
 * synthetic "Unfiled" (project-less items + widgets) and "Variables" folders. Each project folder header
 * carries a yellow-bordered pill deciding whether its items land in the existing same-name project, a new
 * one, or Unfiled; each conflicting item carries its own overwrite/rename pill.
 *
 * [onImport] hands back (global item strategy, per-item overrides keyed "<tab>:<lowercased name>",
 * per-project choices keyed by lowercased project name) — exactly what `confirmOpenTaskerBundleImport`
 * threads to the core.
 */
@Composable
internal fun ImportReviewScreen(
    state: OpenTaskerBundleReviewState,
    projects: List<Project>,
    tasks: List<Task>,
    profiles: List<Profile>,
    scenes: List<Scene>,
    widgetTemplates: List<WidgetTemplate>,
    variables: List<Variable>,
    localGroups: List<com.opentasker.core.storage.ItemGroupEntity> = emptyList(),
    busy: Boolean,
    onCancel: () -> Unit,
    onImport: (ItemConflictStrategy, Map<String, ItemConflictStrategy>, Map<String, ProjectImportChoice>) -> Unit,
) {
    val prefs by ThemeStore.state.collectAsState()
    val bundle = state.bundle
    val plan = state.plan
    // Conflicting (already-exists) rows are drawn in the user-settable "Conflict colour" (default sky blue).
    val conflictColor = Color(prefs.importConflictColor)

    fun lowerSet(names: List<String>): HashSet<String> = names.mapTo(HashSet()) { it.lowercase() }
    val existingProjectNames = lowerSet(projects.map { it.name })
    val existingTaskNames = lowerSet(tasks.map { it.name })
    val existingProfileNames = lowerSet(profiles.map { it.name })
    val existingSceneNames = lowerSet(scenes.map { it.name })
    val existingTemplateNames = lowerSet(widgetTemplates.map { it.name })
    val existingVariableNames = lowerSet(variables.map { it.name })

    // Resolve each item's project BY NAME: bundle-local projectId → the bundle project's name (null /
    // unknown id → the synthetic Unfiled folder).
    val projectNameById = bundle.projects.associate { it.id to it.name }
    fun folderNameFor(pid: Long?): String = pid?.let { projectNameById[it] } ?: UNFILED_FOLDER
    val tasksByFolder = bundle.tasks.groupBy { folderNameFor(it.projectId) }
    val profilesByFolder = bundle.profiles.groupBy { folderNameFor(it.projectId) }
    val scenesByFolder = bundle.scenes.groupBy { folderNameFor(it.projectId) }

    // --- Group resolution (display only) --------------------------------------------------------
    // An item's bundle group NAME: itemMeta (tab,itemKey→groupId) → groups (id,tab→name). itemKey for
    // tasks/profiles/scenes is the entity id as text.
    fun bundleGroupName(tab: String, itemKey: String): String? {
        val gid = bundle.itemMeta.firstOrNull { it.tab == tab && it.itemKey == itemKey }?.groupId ?: return null
        return bundle.groups.firstOrNull { it.id == gid && it.tab == tab }?.name
    }
    // A bundle group already EXISTS locally iff a local group shares its (tab, case-insensitive name) AND
    // lives under a local project whose name matches this folder's project (null == null for synthetic folders).
    val localProjectNameById = projects.associate { it.id to it.name }
    fun groupExistsLocally(tab: String, groupName: String, folderProjectName: String?): Boolean =
        localGroups.any {
            it.tab == tab && it.name.equals(groupName, true) &&
                localProjectNameById[it.projectId].equals(folderProjectName, true)
        }

    // Split a type's items into GroupNodes: named groups first (in bundle.groups position order, else stable
    // insertion order), the ungrouped node last. [folderProjectName] is null for the synthetic folders.
    fun <T> nodesFor(
        items: List<T>,
        tab: String,
        folderProjectName: String?,
        idOf: (T) -> Long,
        nameOf: (T) -> String,
        existsSet: HashSet<String>,
    ): List<GroupNode> {
        val grouped = LinkedHashMap<String?, MutableList<ReviewRow>>()
        items.forEach { item ->
            val gName = bundleGroupName(tab, idOf(item).toString())
            grouped.getOrPut(gName) { mutableListOf() }
                .add(ReviewRow(nameOf(item), nameOf(item).lowercase() in existsSet))
        }
        val nodes = mutableListOf<GroupNode>()
        val emitted = HashSet<String>()
        fun emit(name: String) {
            if (!emitted.add(name)) return
            val rows = grouped[name] ?: return
            nodes.add(GroupNode(name, isNew = !groupExistsLocally(tab, name, folderProjectName), rows))
        }
        // Named groups in bundle order (by position within tab)…
        bundle.groups.filter { it.tab == tab && grouped.containsKey(it.name) }
            .sortedBy { it.position }
            .forEach { emit(it.name) }
        // …then any named groups not covered above (stable insertion order).
        grouped.keys.filterNotNull().forEach { emit(it) }
        // Ungrouped node last (no header when rendered).
        grouped[null]?.let { nodes.add(GroupNode(null, isNew = false, it)) }
        return nodes
    }

    // The Tasks/Profiles/Scenes sub-lists under a folder ([folderName] = the byFolder key; [folderProjectName]
    // = the real project name, or null for the synthetic Unfiled/Variables folders).
    fun typeGroupsFor(folderName: String, folderProjectName: String?): List<TypeGroup> = buildList {
        tasksByFolder[folderName].orEmpty().takeIf { it.isNotEmpty() }?.let {
            add(TypeGroup("tasks", "Tasks", nodesFor(it, "tasks", folderProjectName, { t -> t.id }, { t -> t.name }, existingTaskNames)))
        }
        profilesByFolder[folderName].orEmpty().takeIf { it.isNotEmpty() }?.let {
            add(TypeGroup("profiles", "Profiles", nodesFor(it, "profiles", folderProjectName, { p -> p.id }, { p -> p.name }, existingProfileNames)))
        }
        scenesByFolder[folderName].orEmpty().takeIf { it.isNotEmpty() }?.let {
            add(TypeGroup("scenes", "Scenes", nodesFor(it, "scenes", folderProjectName, { s -> s.id }, { s -> s.name }, existingSceneNames)))
        }
    }

    // Widgets/Variables carry no groups → a single ungrouped GroupNode.
    fun ungroupedType(tab: String, label: String, rows: List<ReviewRow>) =
        TypeGroup(tab, label, listOf(GroupNode(null, false, rows)))

    val folders = buildList {
        // One folder per project the import references, in bundle order (dedup same-name projects).
        val seen = HashSet<String>()
        bundle.projects.forEach { p ->
            if (!seen.add(p.name.lowercase())) return@forEach
            val groups = typeGroupsFor(p.name, p.name)
            if (groups.isNotEmpty()) {
                val kind = if (p.name.lowercase() in existingProjectNames) FolderKind.EXISTING else FolderKind.NEW
                add(ProjectFolder(p.name, kind, groups))
            }
        }
        // Unfiled: project-less tasks/profiles/scenes + all widgets (templates carry no project).
        val unfiledGroups = buildList {
            addAll(typeGroupsFor(UNFILED_FOLDER, null))
            if (bundle.templates.isNotEmpty()) {
                add(ungroupedType("templates", "Widgets",
                    bundle.templates.map { ReviewRow(it.name, it.name.lowercase() in existingTemplateNames) }))
            }
        }
        if (unfiledGroups.isNotEmpty()) add(ProjectFolder(UNFILED_FOLDER, FolderKind.SPECIAL, unfiledGroups))
        // Variables have no project → their own top-level folder.
        if (bundle.variables.isNotEmpty()) {
            add(ProjectFolder(VARIABLES_FOLDER, FolderKind.SPECIAL, listOf(
                ungroupedType("variables", "Variables",
                    bundle.variables.map { ReviewRow(it.name, it.name.lowercase() in existingVariableNames) }))))
        }
    }
    val totalConflicts = folders.sumOf { f -> f.groups.sumOf { g -> g.nodes.sumOf { n -> n.rows.count { it.conflict } } } }

    // Per-category stats as separate, column-aligned "Label: count [⚠ N exists]" lines (non-empty only).
    val statRows = listOf(
        // Projects: count only NEW ones (to be created). An existing project is merged into, not
        // created/overwritten, so it isn't "imported" and isn't reported here (白い熊).
        Triple("Projects", bundle.projects.count { it.name.lowercase() !in existingProjectNames }, 0),
        Triple("Tasks", bundle.tasks.size, bundle.tasks.count { it.name.lowercase() in existingTaskNames }),
        Triple("Profiles", bundle.profiles.size, bundle.profiles.count { it.name.lowercase() in existingProfileNames }),
        Triple("Scenes", bundle.scenes.size, bundle.scenes.count { it.name.lowercase() in existingSceneNames }),
        Triple("Widgets", bundle.templates.size, bundle.templates.count { it.name.lowercase() in existingTemplateNames }),
        Triple("Variables", bundle.variables.size, bundle.variables.count { it.name.lowercase() in existingVariableNames }),
    ).filter { it.second > 0 }

    val warnings = (bundle.metadata.warnings + plan.warnings + plan.lossyWarnings).distinct()

    var itemStrategy by remember { mutableStateOf(ItemConflictStrategy.OVERWRITE_DELETE) }
    val overrides = remember { mutableStateMapOf<String, ItemConflictStrategy>() }
    // Per-project choice, keyed by lowercased project name; absent = the folder's default.
    val projectChoices = remember { mutableStateMapOf<String, ProjectImportChoice>() }
    // Folders start expanded so the whole import is visible at a glance.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // Default per-project choice: an existing project → into it; a new one → create it.
    fun defaultChoice(f: ProjectFolder): ProjectImportChoice =
        if (f.kind == FolderKind.EXISTING) ProjectImportChoice.INTO_EXISTING else ProjectImportChoice.CREATE

    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.98f).fillMaxHeight(0.94f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    "Review import",
                    style = MaterialTheme.typography.headlineSmall,
                    fontSize = prefs.importHeaderSp.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    statRows.forEach { (label, count, exists) ->
                        StatLine(label, count, exists, conflictColor, prefs.importHeaderSp)
                    }
                }
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (warnings.isNotEmpty()) {
                        item(key = "warnings") {
                            ImportWarnNotice(
                                title = "Warnings",
                                body = warnings.joinToString("\n"),
                                color = if (plan.canImport) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.error,
                                bodySp = prefs.importWarnSp,
                            )
                        }
                    }
                    if (!plan.canImport) {
                        item(key = "cannot-import") {
                            InlineNotice(
                                title = "Cannot import",
                                body = plan.warnings.joinToString("\n")
                                    .ifBlank { "Import is not compatible with this build." },
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    // Global item-conflict default (per-project resolution now lives in each folder pill).
                    item(key = "global-controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Items that already exist",
                                style = MaterialTheme.typography.labelLarge,
                                fontSize = prefs.importSectionSp.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            StrategyChoice(
                                options = listOf(
                                    ItemConflictStrategy.OVERWRITE_DELETE to "Overwrite in place",
                                    ItemConflictStrategy.RENAME to "Keep both (rename new)",
                                    ItemConflictStrategy.OVERWRITE_BACKUP to "Overwrite + backup (rename old)",
                                ),
                                selected = itemStrategy,
                                onSelect = { itemStrategy = it },
                                rowPadDp = prefs.importRowPadDp,
                            )
                        }
                    }

                    // Folder tree — one section per project (plus Unfiled / Variables).
                    folders.forEach { folder ->
                        val isOpen = expanded[folder.name] ?: true
                        val choice = projectChoices[folder.name.lowercase()] ?: defaultChoice(folder)
                        item(key = "folder-${folder.name}") {
                            FolderHeader(
                                folder = folder,
                                expanded = isOpen,
                                conflictColor = conflictColor,
                                titleSp = prefs.importSectionSp,
                                choice = choice,
                                onChoice = { projectChoices[folder.name.lowercase()] = it },
                                onToggle = { expanded[folder.name] = !isOpen },
                            )
                        }
                        if (isOpen) {
                            folder.groups.forEach { group ->
                                val groupCount = group.nodes.sumOf { it.rows.size }
                                val conflicts = group.nodes.sumOf { n -> n.rows.count { it.conflict } }
                                item(key = "grp-${folder.name}-${group.tab}") {
                                    TypeSubHeader(
                                        label = group.label,
                                        count = groupCount,
                                        conflicts = conflicts,
                                        conflictColor = conflictColor,
                                        sectionSp = prefs.importSectionSp,
                                    )
                                }
                                group.nodes.forEach { node ->
                                    // A named group gets a folder-icon header (NEW ones marked sky-blue);
                                    // the ungrouped node (name == null) renders its rows header-less as before.
                                    if (node.name != null) {
                                        item(key = "gnode-${folder.name}-${group.tab}-${node.name}") {
                                            GroupNodeHeader(
                                                name = node.name,
                                                isNew = node.isNew,
                                                conflictColor = conflictColor,
                                                sectionSp = prefs.importSectionSp,
                                            )
                                        }
                                    }
                                    items(node.rows) { row ->
                                        ReviewItemRow(
                                            row = row,
                                            tab = group.tab,
                                            globalItemStrategy = itemStrategy,
                                            overrides = overrides,
                                            conflictColor = conflictColor,
                                            itemSp = prefs.importItemSp,
                                            rowPadDp = prefs.importRowPadDp,
                                            extraIndent = node.name != null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = plan.canImport && !busy,
                        onClick = {
                            val effectiveChoices = folders
                                .filter { it.kind != FolderKind.SPECIAL }
                                .associate { f ->
                                    f.name.lowercase() to (projectChoices[f.name.lowercase()] ?: defaultChoice(f))
                                }
                            onImport(itemStrategy, overrides.toMap(), effectiveChoices)
                        },
                    ) {
                        Text(if (busy) "Importing…" else "Import")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> StrategyChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    rowPadDp: Int = 1,
) {
    // Tighten the radio rows: drop Material's 48dp touch-target min so options sit close together (白い熊);
    // the line padding is settable via the "Row spacing" (importRowPadDp) setting.
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            options.forEach { (value, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .requiredHeight((24 + rowPadDp * 2).dp)   // force tight rows (the min-touch toggle is a no-op here)
                        .clickable { onSelect(value) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == value, onClick = { onSelect(value) })
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** A top-level folder header: chevron + folder icon + name, plus the project-choice pill (real projects only). */
@Composable
private fun FolderHeader(
    folder: ProjectFolder,
    expanded: Boolean,
    conflictColor: Color,
    titleSp: Int,
    choice: ProjectImportChoice,
    onChoice: (ProjectImportChoice) -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size((titleSp + 2).dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                style = MaterialTheme.typography.titleMedium,
                fontSize = titleSp.sp,
                // A project not present locally is highlighted in the conflict colour (it'll be created).
                color = if (folder.kind == FolderKind.NEW) conflictColor else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.kind == FolderKind.EXISTING) {
                Text(
                    "existing project",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (folder.kind != FolderKind.SPECIAL) {
            Spacer(Modifier.width(6.dp))
            ProjectChoicePill(
                selected = choice,
                options = if (folder.kind == FolderKind.EXISTING)
                    listOf(
                        ProjectImportChoice.INTO_EXISTING to "Into existing",
                        ProjectImportChoice.CREATE to "Create new",
                    )
                else
                    listOf(
                        ProjectImportChoice.CREATE to "Create",
                        ProjectImportChoice.UNFILED to "Unfiled",
                    ),
                titleSp = titleSp,
                onSelect = onChoice,
            )
        }
    }
}

/** The per-project decision as a yellow-bordered pill: current choice label + chevron; menu picks the two options. */
@Composable
private fun ProjectChoicePill(
    selected: ProjectImportChoice,
    options: List<Pair<ProjectImportChoice, String>>,
    titleSp: Int,
    onSelect: (ProjectImportChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                options.firstOrNull { it.first == selected }?.second ?: "",
                style = MaterialTheme.typography.labelMedium,
                fontSize = titleSp.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size((titleSp + 4).dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(value); open = false },
                )
            }
        }
    }
}

/** A small type sub-header inside a folder (Tasks / Profiles / Scenes / Widgets / Variables). */
@Composable
private fun TypeSubHeader(
    label: String,
    count: Int,
    conflicts: Int,
    conflictColor: Color,
    sectionSp: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 30.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label ($count)",
            style = MaterialTheme.typography.labelLarge,
            fontSize = (sectionSp - 2).coerceAtLeast(10).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (conflicts > 0) {
            Text(
                "$conflicts already exist",
                style = MaterialTheme.typography.labelMedium,
                color = conflictColor,
            )
        }
    }
}

/**
 * A group node header under a type sub-header: a folder icon + the group name + a status hint. A NEW group
 * (not present locally) is drawn in the conflict colour with "will be created", like a new project; an
 * EXISTING one is dimmed.
 */
@Composable
private fun GroupNodeHeader(
    name: String,
    isNew: Boolean,
    conflictColor: Color,
    sectionSp: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 44.dp, top = 3.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (isNew) conflictColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((sectionSp).dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            fontSize = (sectionSp - 1).coerceAtLeast(10).sp,
            // A group not present locally is highlighted in the conflict colour (it'll be created).
            color = if (isNew) conflictColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (isNew) "new group · will be created" else "existing group",
            style = MaterialTheme.typography.labelSmall,
            color = if (isNew) conflictColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReviewItemRow(
    row: ReviewRow,
    tab: String,
    globalItemStrategy: ItemConflictStrategy,
    overrides: MutableMap<String, ItemConflictStrategy>,
    conflictColor: Color,
    itemSp: Int,
    rowPadDp: Int,
    extraIndent: Boolean = false,
) {
    val key = "$tab:${row.name.lowercase()}"
    // Rows under a named group indent one step further than the ungrouped/normal rows.
    val startPad = if (extraIndent) 60.dp else 44.dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = startPad, top = rowPadDp.dp, bottom = rowPadDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.name,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = itemSp.sp,
            color = if (row.conflict) conflictColor else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        if (row.conflict) {
            // Conflicting row: a red warning triangle OUTSIDE (left of) the pill, then the strategy pill.
            Icon(
                Icons.Filled.Warning,
                contentDescription = "Already exists",
                tint = Color(0xFFFF0000),
                modifier = Modifier.size(itemSp.dp),
            )
            Spacer(Modifier.width(4.dp))
            val current = overrides[key] ?: globalItemStrategy
            ItemStrategyPill(
                selected = current,
                conflictColor = conflictColor,
                itemSp = itemSp,
                onSelect = { overrides[key] = it },
            )
        } else {
            // Non-conflicting row: a plain "New".
            Text(
                "New",
                style = MaterialTheme.typography.labelMedium,
                fontSize = itemSp.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The per-item conflict-strategy control as a yellow-bordered pill: ⚠ + current strategy + chevron. */
@Composable
private fun ItemStrategyPill(
    selected: ItemConflictStrategy,
    conflictColor: Color,
    itemSp: Int,
    onSelect: (ItemConflictStrategy) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // Pill text uses the shared conflict colour (settable once, for item names + this pill).
            Text(
                itemStrategyShortLabel(selected),
                style = MaterialTheme.typography.labelMedium,
                fontSize = itemSp.sp,
                color = conflictColor,
                maxLines = 1,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = conflictColor,
                modifier = Modifier.size((itemSp + 4).dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ItemConflictStrategy.entries.forEach { strat ->
                DropdownMenuItem(
                    text = { Text(itemStrategyLongLabel(strat)) },
                    onClick = { onSelect(strat); open = false },
                )
            }
        }
    }
}

/** Local sized variant of [InlineNotice] so the Warnings text honours the "Warnings size" setting. */
@Composable
private fun ImportWarnNotice(title: String, body: String, color: Color, bodySp: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.26f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = bodySp.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One "Label: count [⚠ N exists]" stats line — the label column is width-aligned so counts line up; the
 *  "N exists" reuses the conflict (sky-blue) colour with a red warning triangle. */
@Composable
private fun StatLine(label: String, count: Int, exists: Int, conflictColor: Color, headerSp: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label:",
            fontSize = headerSp.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width((headerSp * 6).dp),   // label column — count starts here (slightly less indented)
        )
        Text(
            "$count",
            fontSize = headerSp.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width((headerSp * 4).dp),    // count column — the ⚠/exists starts after it (more indented)
        )
        if (exists > 0) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = Color(0xFFFF0000),
                modifier = Modifier.size(headerSp.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$exists exist${if (exists == 1) "s" else ""}",
                fontSize = headerSp.sp,
                color = conflictColor,
            )
        }
    }
}

private fun itemStrategyShortLabel(s: ItemConflictStrategy): String = when (s) {
    ItemConflictStrategy.OVERWRITE_DELETE -> "Overwrite"
    ItemConflictStrategy.RENAME -> "Keep both"
    ItemConflictStrategy.OVERWRITE_BACKUP -> "Backup"
}

private fun itemStrategyLongLabel(s: ItemConflictStrategy): String = when (s) {
    ItemConflictStrategy.OVERWRITE_DELETE -> "Overwrite in place"
    ItemConflictStrategy.RENAME -> "Keep both (rename new)"
    ItemConflictStrategy.OVERWRITE_BACKUP -> "Overwrite + backup (rename old)"
}
