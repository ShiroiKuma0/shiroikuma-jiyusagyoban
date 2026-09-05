package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.app.R
import com.opentasker.ui.utils.expandCollapseToggle
import com.opentasker.core.model.Project
import com.opentasker.core.model.ProjectFilter
import com.opentasker.core.model.Variable
import com.opentasker.ui.components.ItemNoteSection
import com.opentasker.ui.components.SelectionBar
import com.opentasker.ui.components.SelectionCheck
import com.opentasker.ui.components.selectableItem
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.ThemeStore

private val SENSITIVE_NAMES = setOf("password", "token", "secret", "key", "credential", "auth")

private fun isSensitive(name: String): Boolean =
    SENSITIVE_NAMES.any { name.lowercase().contains(it) }

/** Stable selection key for a variable (name alone isn't unique across scopes). */
fun variableKey(v: Variable): String = "${v.projectId}:${v.name}"

@Composable
fun VariablesScreen(
    variables: List<Variable>,
    contentPadding: PaddingValues,
    projects: List<Project>,
    projectFilter: ProjectFilter,
    onSelectProject: (ProjectFilter) -> Unit,
    onReorderProjects: (List<Long>) -> Unit,
    onUpdate: (projectId: Long, name: String, value: String) -> Unit,
    onDelete: (projectId: Long, name: String) -> Unit,
    onMessage: (String) -> Unit,
    expandedVars: SnapshotStateMap<String, Boolean>,
    selectedKeys: Set<String>,
    onLongPressVar: (Variable) -> Unit,
    onToggleSelectVar: (Variable) -> Unit,
    onSelectAllVars: () -> Unit,
    onClearVarSelection: () -> Unit,
    onDeleteSelectedVars: () -> Unit,
    deadGlobals: DeadGlobalsReport,
    onCleanupDeadGlobals: () -> Unit,
    contentLoaded: Boolean = true,
) {
    // An unread database and an empty one look identical from here, so without this a cold start
    // with stored variables flashes the empty state before Room's first emission.
    if (!contentLoaded) {
        ContentLoadingState(contentPadding)
        return
    }
    var searchQuery by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<Variable?>(null) }
    val selectionActive = selectedKeys.isNotEmpty()

    val filtered = remember(variables, searchQuery) {
        if (searchQuery.isBlank()) variables
        else variables.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.value.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        // Cleanup analyzer — global to the tab (not project-filtered), folded by default, sits atop everything.
        DeadGlobalsSection(deadGlobals, projects, onCleanupDeadGlobals)
        if (projects.isNotEmpty()) {
            ProjectFilterChips(projects, projectFilter, onSelectProject, onReorderProjects, Modifier.padding(vertical = 8.dp))
        }
        if (selectionActive) {
            SelectionBar(
                count = selectedKeys.size,
                total = variables.size,
                onSelectAll = onSelectAllVars,
                onClear = onClearVarSelection,
                onDelete = onDeleteSelectedVars,
            )
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Search variables") },
            singleLine = true,
            trailingIcon = {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (filtered.isEmpty()) {
            Text(
                text = if (variables.isEmpty()) "No global variables set" else "No matches",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        val variableRow: @Composable (Variable) -> Unit = { variable ->
            val key = variableKey(variable)
            VariableRow(
                variable = variable,
                selectionActive = selectionActive,
                selected = key in selectedKeys,
                expanded = expandedVars[key] == true,
                onToggleExpanded = { expandedVars[key] = expandedVars[key] != true },
                onLongPress = { onLongPressVar(variable) },
                onToggleSelect = { onToggleSelectVar(variable) },
                onEdit = { editTarget = variable },
                onDelete = { onDelete(variable.projectId, variable.name) },
            )
        }
        // Foldable sections by SCOPE: "Global" (super-global) then "Project-global". Each heading is
        // larger + underlined with a fold triangle to its right, slightly indented; folding hides that
        // scope's rows. Section fold state is per-scope (default open).
        val sectionExpanded = remember { mutableStateMapOf<String, Boolean>() }
        val sections = remember(filtered) {
            filtered.groupBy { if (it.projectId == 0L) "Global" else "Project-global" }
                .toList()
                .sortedBy { (label, _) -> if (label == "Global") 0 else 1 }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            sections.forEach { (label, vars) ->
                val open = sectionExpanded[label] != false
                item(key = "scope:$label") {
                    ScopeSectionHeader(
                        label = label,
                        count = vars.size,
                        expanded = open,
                        onToggle = { sectionExpanded[label] = sectionExpanded[label] == false },
                    )
                }
                if (open) {
                    items(vars, key = { variableKey(it) }) { variable -> variableRow(variable) }
                }
            }
        }
    }

    editTarget?.let { target ->
        EditVariableDialog(
            variable = target,
            onDismiss = { editTarget = null },
            onSave = { newValue ->
                onUpdate(target.projectId, target.name, newValue)
                editTarget = null
                onMessage("Updated ${target.name}")
            },
        )
    }
}

@Composable
private fun VariableSummaryCard(
    totalCount: Int,
    visibleCount: Int,
    sensitiveCount: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = DesignSystem.Opacity.elevatedSurface)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = DesignSystem.Opacity.subtleBorder)),
        shape = RoundedCornerShape(DesignSystem.Radii.xxl),
    ) {
        Column(Modifier.padding(DesignSystem.Screen.heroCardPadding), verticalArrangement = Arrangement.spacedBy(DesignSystem.Screen.sectionGap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.title_variable_vault), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (totalCount == 0) {
                            stringResource(R.string.empty_variables_body)
                        } else {
                            stringResource(R.string.variables_summary_body)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VariablePill(
                    label = "$visibleCount shown",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                VariableMetric("$totalCount", "Saved", Modifier.weight(1f))
                VariableMetric("$sensitiveCount", stringResource(R.string.label_masked), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VariableMetric(value: String, label: String, modifier: Modifier = Modifier) {
    SummaryMetric(value = value, label = label, modifier = modifier)
}

@Composable
private fun VariableEmptyState(title: String, body: String, onCreate: (() -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
        shape = RoundedCornerShape(DesignSystem.Radii.lg),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = "Info",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onCreate != null) {
                    TextButton(onClick = onCreate, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text(stringResource(R.string.empty_variables_create))
                    }
                }
            }
        }
    }
}

/**
 * Foldable "Clean up dead globals" analyzer atop the Var tab: a live table of the super-global namespace —
 * shadow-copies (dup of a project-global) and orphans (referenced nowhere) to delete, proper globals kept.
 * Recomputes from [report] (derived from the live workspace), so once cleaned it reads 0 / 0. Delete removes
 * only the exact super-global rows; every project's live copy is untouched.
 */
@Composable
private fun DeadGlobalsSection(report: DeadGlobalsReport, projects: List<Project>, onCleanup: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showShadow by remember { mutableStateOf(false) }
    var showOrphan by remember { mutableStateOf(false) }
    var showDangling by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val projName = remember(projects) { projects.associate { it.id to it.name } }
    val dead = report.deadCount
    // The pill border/background lives on the OUTER container so it wraps everything — collapsed it's a
    // stadium pill (header only); expanded it grows into a rounded card enclosing all the category rows.
    val pillShape = if (expanded) RoundedCornerShape(20.dp) else RoundedCornerShape(50)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(pillShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f), pillShape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .expandCollapseToggle(expanded) { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            // Triangle sits just after the label (not pushed to the far edge), with a wide gap between them.
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(
                text = "Clean up dead globals" + if (dead > 0) " ($dead)" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (expanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = if (expanded) "Collapse cleanup" else "Expand cleanup",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Each category is tappable to reveal exactly which variables it covers.
                DeadCatRow("Shadow-copies (dup of a project-global)", report.shadowCopies.size, "delete",
                    report.shadowCopies.isNotEmpty(), showShadow) { showShadow = !showShadow }
                if (showShadow) report.shadowCopies.forEach { s ->
                    DeadItemRow("%${s.variable.name}", "→ ${projName[s.twinProjectId] ?: "project #${s.twinProjectId}"}", s.variable.value)
                }
                DeadCatRow("Orphans (referenced nowhere)", report.orphans.size, "delete",
                    report.orphans.isNotEmpty(), showOrphan) { showOrphan = !showOrphan }
                if (showOrphan) report.orphans.forEach { v -> DeadItemRow("%${v.name}", "", v.value) }
                DeadCatRow("Dangling project-globals (dead project)", report.dangling.size, "delete",
                    report.dangling.isNotEmpty(), showDangling) { showDangling = !showDangling }
                if (showDangling) report.dangling.forEach { v -> DeadItemRow("%${v.name}", "✗ proj #${v.projectId}", v.value) }
                DeadCatRow("Proper globals (in use)", report.proper.size, "keep", false, false, null)
                Button(
                    onClick = { confirm = true },
                    enabled = dead > 0,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(if (dead > 0) "Delete $dead dead global${if (dead == 1) "" else "s"}" else "Nothing to clean") }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
            onDismissRequest = { confirm = false },
            title = { Text("Delete $dead dead global${if (dead == 1) "" else "s"}?") },
            text = { Text("${report.shadowCopies.size} shadow-copies + ${report.orphans.size} orphans + ${report.dangling.size} dangling (dead-project) globals. ${report.proper.size} proper globals stay; every live project copy is untouched.") },
            confirmButton = { TextButton(onClick = { confirm = false; onCleanup() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Category summary: label · count · action, with a fold triangle when it has items to reveal. */
@Composable
private fun DeadCatRow(label: String, count: Int, action: String, highlight: Boolean, expanded: Boolean, onToggle: (() -> Unit)?) {
    val canToggle = onToggle != null && count > 0
    Row(
        Modifier.fillMaxWidth()
            .then(if (canToggle) Modifier.clickable { onToggle!!() } else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canToggle) {
            Icon(
                if (expanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("$count  $action", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One dead variable's detail line: `%name` · where (twin project, for a shadow) · value. */
@Composable
private fun DeadItemRow(name: String, where: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 26.dp, top = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, color = Color(0xFF7FB4FF), maxLines = 1)
        if (where.isNotEmpty()) {
            Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * Foldable scope-section heading (e.g. "Global", "Project-global"). Heading-style: larger + bold +
 * underlined, slightly indented, with a fold triangle immediately to its right. Tapping anywhere on the
 * row toggles the section.
 */
@Composable
private fun ScopeSectionHeader(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 24.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "$label ($count)",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            if (expanded) Icons.Filled.ArrowDropDown else Icons.AutoMirrored.Filled.ArrowRight,
            contentDescription = if (expanded) "Collapse $label" else "Expand $label",
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun VariableRow(
    variable: Variable,
    selectionActive: Boolean,
    selected: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val themePrefs by ThemeStore.state.collectAsState()
    Card(
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize().selectableItem(
            selectionActive = selectionActive,
            onLongPress = onLongPress,
            onToggleSelect = onToggleSelect,
            onTapNormal = onToggleExpanded,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = themePrefs.varRowPadDp.dp),
            verticalArrangement = Arrangement.spacedBy(themePrefs.varRowPadDp.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionActive) {
                    SelectionCheck(selected)
                }
                // Name + value on ONE line, styled like the action view (name blue, value bold). Colours
                // and sizes come from ThemePrefs (default = the action-view data styling) and are each
                // independently settable in UI Customization. The scope/type moves to the expanded view.
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "%${variable.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = themePrefs.varNameSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(themePrefs.varNameColor),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isSensitive(variable.name)) "***" else variable.value.ifBlank { "—" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontSize = themePrefs.varValueSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(themePrefs.varValueColor),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse variable" else "Expand variable",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                ItemNoteSection("vars", "${variable.projectId}:${variable.name}")
                // Full value (multi-line — the folded preview above is truncated to one line).
                Text(
                    text = if (isSensitive(variable.name)) "***" else variable.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                // The scope/type, shown only when unfolded (under the value).
                Text(
                    text = if (variable.projectId == 0L) "super-global" else "project-global",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit variable")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete variable")
                    }
                }
            }
        }
    }
}

@Composable
private fun VariablePill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        border = BorderStroke(1.dp, color.copy(alpha = 0.32f)),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun EditVariableDialog(
    variable: Variable,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(variable.value) }

    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDismiss,
        title = { Text("%${variable.name}") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.variables_value_label, variable.name)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
