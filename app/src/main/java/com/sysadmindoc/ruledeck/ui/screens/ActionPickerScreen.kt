package com.sysadmindoc.ruledeck.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.sysadmindoc.ruledeck.i18n.tr
import com.sysadmindoc.ruledeck.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.sysadmindoc.ruledeck.ui.components.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmindoc.ruledeck.core.actions.ActionMetadata
import com.sysadmindoc.ruledeck.core.actions.ActionMetadataRegistry
import com.sysadmindoc.ruledeck.core.capabilities.ActionCapability
import com.sysadmindoc.ruledeck.core.capabilities.ActionCapabilityRegistry
import com.sysadmindoc.ruledeck.core.capabilities.CapabilityLevel
import com.sysadmindoc.ruledeck.core.capabilities.CapabilityState
import com.sysadmindoc.ruledeck.ui.components.IconRole
import com.sysadmindoc.ruledeck.ui.components.RoleIcon
import com.sysadmindoc.ruledeck.ui.theme.UiRole
import com.sysadmindoc.ruledeck.ui.theme.role

/**
 * The icon of an action category and the role that colours it — shared by both action pickers, so a
 * category looks the same wherever its actions are listed. Chosen from the category's name; a
 * category added later falls back to a neutral bolt until it is given a line here.
 */
internal fun actionCategoryIcon(category: String): Pair<ImageVector, IconRole> = when (category) {
    "Alert" -> Icons.Filled.NotificationsActive to IconRole.WRITE
    "App" -> Icons.Filled.Apps to IconRole.VIEW
    "Battery" -> Icons.Filled.BatteryFull to IconRole.VIEW
    "Calendar" -> Icons.Filled.CalendarMonth to IconRole.VIEW
    "Calls" -> Icons.Filled.Call to IconRole.CONNECT
    "Display" -> Icons.Filled.BrightnessMedium to IconRole.VIEW
    "File" -> Icons.Filled.InsertDriveFile to IconRole.SHARE
    "Flow" -> Icons.Filled.AccountTree to IconRole.CONNECT
    "Focus" -> Icons.Filled.DoNotDisturbOn to IconRole.BLOCK
    "Habits" -> Icons.Filled.Repeat to IconRole.PERSON
    "Health" -> Icons.Filled.MonitorHeart to IconRole.PERSON
    "Import" -> Icons.Filled.Download to IconRole.SHARE
    "Interface" -> Icons.Filled.Dashboard to IconRole.VIEW
    "Location", "Location-lite" -> Icons.Filled.LocationOn to IconRole.VIEW
    "Media" -> Icons.Filled.MusicNote to IconRole.CONNECT
    "Network" -> Icons.Filled.Wifi to IconRole.SHARE
    "Notification" -> Icons.Filled.Notifications to IconRole.WRITE
    "Plugin" -> Icons.Filled.Extension to IconRole.CONNECT
    "Safety" -> Icons.Filled.HealthAndSafety to IconRole.BLOCK
    "Script" -> Icons.Filled.Code to IconRole.WRITE
    "Settings" -> Icons.Filled.Settings to IconRole.VIEW
    "Sleep" -> Icons.Filled.Bedtime to IconRole.PERSON
    "System" -> Icons.Filled.PhoneAndroid to IconRole.VIEW
    "Tasks" -> Icons.Filled.PlayArrow to IconRole.CONNECT
    "Text" -> Icons.Filled.TextFields to IconRole.WRITE
    "Variable" -> Icons.Filled.DataObject to IconRole.WRITE
    else -> Icons.Filled.Bolt to IconRole.VIEW
}

/**
 * Full-screen action picker (advanced mode). Actions are foldable by category; expanding a category
 * rolls out its actions, and expanding an action shows its full description and fields before adding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedActionPickerScreen(
    onDismiss: () -> Unit,
    onSelect: (ActionMetadata) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val actionGroups = remember {
        ActionMetadataRegistry.all()
            .groupBy { it.category }
            .toSortedMap()
            .map { (category, actions) -> category to actions.sortedBy { it.name } }
    }
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    val expandedActions = remember { mutableStateMapOf<String, Boolean>() }
    var query by remember { mutableStateOf("") }
    val filteredGroups = if (query.isBlank()) {
        actionGroups
    } else {
        actionGroups.mapNotNull { (category, actions) ->
            val matches = actions.filter {
                it.name.contains(query, ignoreCase = true) ||
                    category.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true) ||
                    tr(it.name).contains(query, ignoreCase = true) ||
                    tr(category).contains(query, ignoreCase = true) ||
                    tr(it.description).contains(query, ignoreCase = true)
            }
            if (matches.isEmpty()) null else category to matches
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(tr("Add action")) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Search actions")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            filteredGroups.forEach { (category, actions) ->
                // While searching, force categories open so matches are visible.
                val categoryExpanded = query.isNotBlank() || expandedCategories[category] == true
                item(key = "cat-$category") {
                    CategoryHeaderRow(category, actions.size, categoryExpanded) {
                        expandedCategories[category] = !categoryExpanded
                    }
                }
                if (categoryExpanded) {
                    items(actions, key = { it.id }) { metadata ->
                        ActionAccordionItem(
                            metadata = metadata,
                            capability = ActionCapabilityRegistry.get(metadata.id),
                            expanded = expandedActions[metadata.id] == true,
                            onToggle = { expandedActions[metadata.id] = expandedActions[metadata.id] != true },
                            onAdd = { onSelect(metadata) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeaderRow(category: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val (categoryIcon, categoryRole) = actionCategoryIcon(category)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            RoleIcon(categoryIcon, categoryRole, Modifier.size(22.dp))
            Text(tr(category), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The line under a category heading is the group divider of this list.
        HorizontalDivider(color = role(UiRole.LIST_DIVIDER))
    }
}

@Composable
private fun ActionAccordionItem(
    metadata: ActionMetadata,
    capability: ActionCapability,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAdd: () -> Unit,
) {
    // Live capability state — same checks the Setup tab uses — so the note is red+fix only when the
    // requirement is genuinely not met, and a yellow FYI when it is.
    val context = LocalContext.current
    val met = CapabilityState.isMet(capability.requirement, context)
    val effectiveLevel = if (met) CapabilityLevel.Supported else capability.level
    val (categoryIcon, categoryRole) = actionCategoryIcon(metadata.category)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 36.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RoleIcon(categoryIcon, categoryRole, Modifier.size(20.dp))
            Text(tr(metadata.name), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (effectiveLevel != CapabilityLevel.Supported) {
                Text(
                    if (effectiveLevel == CapabilityLevel.Unsupported) tr("Unsupported") else tr("Setup"),
                    style = MaterialTheme.typography.labelSmall,
                    color = role(UiRole.WARNING),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 96.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr(metadata.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CapabilityNote(capability)
                if (metadata.fields.isNotEmpty()) {
                    Text(tr("Fields"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    metadata.fields.forEach { field ->
                        Text(
                            buildString {
                                append("• ").append(tr(field.label))
                                append(" (").append(field.fieldType.name.lowercase()).append(")")
                                if (field.required) append(" — ").append(tr("required"))
                                field.hint?.let { append(" — ").append(tr(it)) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onAdd, enabled = capability.canAdd) {
                    Text(tr("Add to task"))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
