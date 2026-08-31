package com.opentasker.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task

/**
 * The "Help & Tools" tab: a "Tools" section (the relocated beginner/utility cards — templates, task
 * library, scene library) followed by in-app documentation. The concept/schema prose
 * lives in [docSections] (kept in sync with `docs/bundle-schema.md` in the repo); the **action
 * reference** is generated live from [ActionMetadataRegistry], so it can never drift from the actual
 * built-in actions.
 *
 * Every doc section folds (collapsed by default); [expandedSections] is hoisted to the host so the
 * open/closed state survives leaving and re-entering the tab.
 */
@Composable
fun HelpDocumentationScreen(
    contentPadding: PaddingValues,
    expandedSections: SnapshotStateMap<String, Boolean>,
    tasks: List<Task>,
    scenes: List<Scene>,
    onBrowseTemplates: () -> Unit,
    onCreateTask: () -> Unit,
    onCreateScene: () -> Unit,
) {
    val actionsByCategory = remember {
        ActionMetadataRegistry.all().sortedBy { it.name }.groupBy { it.category }.toSortedMap()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // "Tools" section at the top: the beginner/utility cards relocated out of the main tabs.
        item(key = "tools-header") {
            Text(
                "Tools",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        item(key = "tools-templates") { TemplatePromptCard(onBrowseTemplates) }
        item(key = "tools-task-library") { TaskLibrarySummaryCard(tasks = tasks, onCreateTask = onCreateTask) }
        item(key = "tools-scene-library") { SceneOverviewCard(scenes = scenes, tasks = tasks, onCreateScene = onCreateScene) }
        items(docSections, key = { it.title }) { section ->
            DocCard(section.title, expandedSections) {
                section.blocks.forEach { RenderBlock(it) }
            }
        }
        item(key = "Action reference") {
            DocCard("Action reference", expandedSections) {
                Para("Every built-in action, generated from the live registry. The `type` is the id you put in a bundle's `actions[]`; the fields are the keys that go in that action's `args` map.")
                Text(
                    "${actionsByCategory.values.sumOf { it.size }} actions in ${actionsByCategory.size} categories",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        actionsByCategory.forEach { (category, actions) ->
            item(key = "cat:$category") {
                DocCard(category, expandedSections, key = "cat:$category") {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ActionDoc(action)
                    }
                }
            }
        }
    }
}

/** A collapsible doc section; collapsed by default, toggle state stored in [expandedSections] by [key]. */
@Composable
private fun DocCard(
    title: String,
    expandedSections: SnapshotStateMap<String, Boolean>,
    key: String = title,
    content: @Composable () -> Unit,
) {
    val expanded = expandedSections[key] == true
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expandedSections[key] = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun ActionDoc(action: ActionMetadata) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(action.name, style = MaterialTheme.typography.titleSmall)
            CodeChip(action.id)
        }
        if (action.description.isNotBlank()) {
            Text(action.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action.fields.forEach { field ->
            val meta = buildString {
                append(field.fieldType.name.lowercase())
                if (field.required) append(", required")
            }
            FieldLine(field.key, meta, field.hint ?: "")
        }
    }
}

@Composable
private fun RenderBlock(block: DocBlock) {
    when (block) {
        is DocBlock.Para -> Para(block.text)
        is DocBlock.Code -> CodeBlock(block.text)
        is DocBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEach { item ->
                Text("•  $item", style = MaterialTheme.typography.bodySmall)
            }
        }
        is DocBlock.Fields -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.rows.forEach { FieldLine(it.name, it.type, it.note) }
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CodeBlock(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun CodeChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun FieldLine(name: String, type: String, note: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(Modifier.weight(1f)) {
            if (type.isNotBlank()) {
                Text(type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (note.isNotBlank()) {
                Text(note, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ============ Hand-written documentation (mirror of docs/bundle-schema.md) ============

private sealed interface DocBlock {
    data class Para(val text: String) : DocBlock
    data class Code(val text: String) : DocBlock
    data class Bullets(val items: List<String>) : DocBlock
    data class Fields(val rows: List<FieldRow>) : DocBlock
}

private data class FieldRow(val name: String, val type: String, val note: String)
private data class DocSection(val title: String, val blocks: List<DocBlock>)

private fun fields(vararg rows: Triple<String, String, String>): DocBlock.Fields =
    DocBlock.Fields(rows.map { FieldRow(it.first, it.second, it.third) })

private val docSections: List<DocSection> = listOf(
    DocSection(
        "Concepts",
        listOf(
            DocBlock.Para("白い熊 自由作業盤 automates your device. The pieces:"),
            DocBlock.Bullets(
                listOf(
                    "Project — an optional folder grouping profiles, tasks and scenes. Items with no project are \"Unfiled\".",
                    "Task — an ordered list of actions. Run on a trigger, a tile, a widget, or by hand.",
                    "Action — one step in a task (show a notification, set a variable, send an intent, …).",
                    "Profile — binds one or more contexts to an enter-task (and optional exit-task); active while all its contexts match.",
                    "Context — a condition: app in foreground, time window, day, location, device state, or a one-shot event.",
                    "Scene — a floating overlay built from elements (text, button, slider, image …) that can run tasks on tap.",
                    "Widget template — a named home-screen widget layout, referenced by the Set Widget action and refreshed from variables.",
                    "Variable — a named value (%name) read/written by actions; scope follows its case (see below).",
                ),
            ),
        ),
    ),
    DocSection(
        "Variables & scoping",
        listOf(
            DocBlock.Para("Variables are written %name and their scope is decided by the case of the first letters:"),
            DocBlock.Bullets(
                listOf(
                    "%ALLCAPS — super-global: one value shared everywhere (projectId 0).",
                    "%MixedCase — project-global: one value per project (the task's project; Unfiled maps to super-global).",
                    "%lowercase — task-local: ephemeral, lives only for that task run.",
                ),
            ),
            DocBlock.Para("Persisted variables (super-global and project-global) survive restarts and are listed on the Vars tab. Indexing like %array(2) is not supported — the parenthetical is literal text."),
        ),
    ),
    DocSection(
        "The bundle file",
        listOf(
            DocBlock.Para("Every Import/Export uses one JSON format, the OpenTaskerBundle. Any tab's + menu imports it; each tab exports its own slice. Top-level fields:"),
            fields(
                Triple("schemaVersion", "int", "Format version this build understands (currently 4). A newer file warns but older files always import — missing fields default."),
                Triple("appVersion", "string", "App version that wrote the file (informational)."),
                Triple("exportedAtEpochMs", "long", "Export timestamp."),
                Triple("metadata", "object", "name, description, capabilityRequirements[], warnings[]."),
                Triple("projects", "array", "Project folders referenced by items below."),
                Triple("tasks", "array", "Tasks with their actions."),
                Triple("profiles", "array", "Profiles with their contexts (imported disabled for review)."),
                Triple("variables", "array", "Persisted variables."),
                Triple("scenes", "array", "Scenes with their elements."),
                Triple("templates", "array", "Widget-layout templates."),
                Triple("sort", "object", "Per-tab Alphabetical/Manual choice (profiles, tasks, scenes)."),
            ),
            DocBlock.Para("On import, a project whose name already exists prompts: \"Import into\" (MERGE — file items under the existing project) or \"New project\" (RENAME — make a separate copy). Profiles always import disabled so you can review contexts and permissions first."),
        ),
    ),
    DocSection(
        "Tasks & actions",
        listOf(
            DocBlock.Para("A task is an ordered action[] list."),
            fields(
                Triple("id", "long", "Bundle-local id (referenced by profiles / scene elements)."),
                Triple("name", "string", "Display name; also how scene/profile lookups resolve by name."),
                Triple("priority", "int", "Higher runs first on contention."),
                Triple("collisionMode", "enum", "ABORT_NEW · ABORT_EXISTING · RUN_BOTH · WAIT — what happens if it's already running."),
                Triple("actions", "array", "The steps (see below)."),
                Triple("projectId", "long?", "Owning project, or null = Unfiled."),
                Triple("position", "int", "Manual sort order within the tab."),
            ),
            DocBlock.Para("Each action (ActionSpec):"),
            fields(
                Triple("type", "string", "Action id, e.g. notify.show — see the Action reference below."),
                Triple("args", "map", "String key→value arguments for that action type."),
                Triple("label", "string?", "Optional custom label."),
                Triple("continueOnError", "bool", "If true, a failure doesn't stop the task."),
                Triple("condition", "string?", "Optional %var condition guarding the step."),
            ),
        ),
    ),
    DocSection(
        "Profiles & contexts",
        listOf(
            DocBlock.Para("A profile is active while ALL of its contexts match; entering runs enterTaskId, leaving runs exitTaskId."),
            fields(
                Triple("name", "string", "Display name."),
                Triple("enabled", "bool", "Imported profiles arrive disabled."),
                Triple("contexts", "array", "Conditions (below)."),
                Triple("enterTaskId", "long", "Task run when the profile becomes active."),
                Triple("exitTaskId", "long?", "Task run when it deactivates (optional)."),
                Triple("cooldownSec", "int", "Minimum seconds between activations."),
                Triple("automationMode", "enum", "SINGLE · RESTART · QUEUED · PARALLEL."),
            ),
            DocBlock.Para("Each context (ContextSpec):"),
            fields(
                Triple("type", "enum", "APPLICATION · TIME · DAY · LOCATION · STATE · EVENT."),
                Triple("config", "map", "Type-specific settings (e.g. time window, app list)."),
                Triple("invert", "bool", "Match when the condition is false."),
                Triple("orGroup", "string?", "Contexts sharing an orGroup match as OR instead of AND."),
            ),
            DocBlock.Para("The EVENT type covers one-shot triggers (boot, notification, NFC, calendar, and the internal event=minute tick used by clock widgets)."),
        ),
    ),
    DocSection(
        "Scenes",
        listOf(
            DocBlock.Para("A scene is a floating overlay (widthDp × heightDp) of positioned elements."),
            fields(
                Triple("widthDp / heightDp", "int", "Panel size."),
                Triple("elements", "array", "Positioned UI elements (below)."),
                Triple("projectId", "long?", "Owning project, or null = Unfiled."),
            ),
            DocBlock.Para("Each element (SceneElement):"),
            fields(
                Triple("type", "enum", "BUTTON · TEXT · EDIT_TEXT · CHECKBOX · TOGGLE · SLIDER · IMAGE · … "),
                Triple("xDp / yDp / widthDp / heightDp", "int", "Position and size within the panel."),
                Triple("config", "map", "Element settings (label, text, min/max, …); %vars are expanded at show time."),
                Triple("tapTaskId / longPressTaskId", "long?", "Task to run on tap / long-press."),
            ),
        ),
    ),
    DocSection(
        "Variables, templates & projects",
        listOf(
            DocBlock.Para("Variable:"),
            fields(
                Triple("name", "string", "Without the leading % (scope follows its case — see above)."),
                Triple("value", "string", "Current value."),
                Triple("projectId", "long", "0 = super-global, >0 = that project's project-global."),
            ),
            DocBlock.Para("Widget template:"),
            fields(
                Triple("name", "string", "Referenced by a Set Widget action and the launcher."),
                Triple("layout", "string", "Widget-layout JSON with %vars left raw (expanded at render)."),
            ),
            DocBlock.Para("Project:"),
            fields(
                Triple("id", "long", "Bundle-local id referenced by items' projectId."),
                Triple("name", "string", "Folder name (matched case-insensitively on import)."),
                Triple("color / sortOrder / description", "—", "Optional presentation."),
            ),
        ),
    ),
)
