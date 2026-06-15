package com.opentasker.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.actions.ActionMetadata
import com.opentasker.core.actions.ActionMetadataRegistry
import com.opentasker.core.capabilities.ActionCapability
import com.opentasker.core.capabilities.ActionCapabilityRegistry
import com.opentasker.core.capabilities.CapabilityLevel

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
                    it.description.contains(query, ignoreCase = true)
            }
            if (matches.isEmpty()) null else category to matches
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Add action") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    label = { Text("Search actions") },
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
            Text(category, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            Text(metadata.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (capability.level != CapabilityLevel.Supported) {
                Text(
                    if (capability.level == CapabilityLevel.Unsupported) "Unsupported" else "Setup",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (capability.level == CapabilityLevel.Unsupported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 56.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(metadata.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (capability.level != CapabilityLevel.Supported) {
                    Text(capability.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (metadata.fields.isNotEmpty()) {
                    Text("Fields", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    metadata.fields.forEach { field ->
                        Text(
                            buildString {
                                append("• ").append(field.label)
                                append(" (").append(field.fieldType.name.lowercase()).append(")")
                                if (field.required) append(" — required")
                                field.hint?.let { append(" — ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onAdd, enabled = capability.canAdd) {
                    Text("Add to task")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
