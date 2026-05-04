package com.opentasker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.AppDatabase
import com.opentasker.core.storage.toEntity
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.launch

/**
 * Screen for batch operations on profiles: multi-select, enable/disable/delete all.
 * Supports search/filter by profile name or context type.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BatchOperationsScreen(
    db: AppDatabase,
    onBack: () -> Unit
) {
    val allProfileEntities by db.profileDao().getAllAsFlow().collectAsState(emptyList())
    val allProfiles = allProfileEntities.map { it.toDomain() }
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val scope = rememberCoroutineScope()
    
    val filtered = allProfiles.filter { profile ->
        searchQuery.isEmpty() || profile.name.contains(searchQuery, ignoreCase = true)
    }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Batch Operations (${selectedIds.size} selected)",
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    if (selectedIds.isNotEmpty()) {
                        IconButton(onClick = {
                            // Select all visible
                            selectedIds = filtered.map { it.id }.toSet()
                        }) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All")
                        }
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Deselect")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(DesignSystem.Spacing.md)
            )
            
            // Batch action buttons
            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(DesignSystem.Spacing.md)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(DesignSystem.Radii.md))
                        .padding(DesignSystem.Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)
                ) {
                    Button(onClick = {
                        // Enable all selected
                        scope.launch {
                            selectedIds.forEach { id ->
                                val profile = allProfiles.find { it.id == id } ?: return@forEach
                                db.profileDao().update(profile.copy(enabled = true).toEntity())
                            }
                            selectedIds = emptySet()
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Done, contentDescription = null)
                        Spacer(modifier = Modifier.width(DesignSystem.Spacing.xs))
                        Text("Enable", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    
                    Button(onClick = {
                        // Disable all selected
                        scope.launch {
                            selectedIds.forEach { id ->
                                val profile = allProfiles.find { it.id == id } ?: return@forEach
                                db.profileDao().update(profile.copy(enabled = false).toEntity())
                            }
                            selectedIds = emptySet()
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Block, contentDescription = null)
                        Spacer(modifier = Modifier.width(DesignSystem.Spacing.xs))
                        Text("Disable", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    
                    Button(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            
            // Profile list with checkboxes
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { profile ->
                    BatchOperationProfileCard(
                        profile = profile,
                        isSelected = profile.id in selectedIds,
                        onSelectionChange = { selected ->
                            selectedIds = if (selected) {
                                selectedIds + profile.id
                            } else {
                                selectedIds - profile.id
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = DesignSystem.Spacing.md, vertical = DesignSystem.Spacing.xs)
                    )
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${selectedIds.size} profiles?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            selectedIds.forEach { id ->
                                db.profileDao().delete(allProfiles.find { it.id == id }?.toEntity() ?: return@forEach)
                            }
                            selectedIds = emptySet()
                            showDeleteDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BatchOperationProfileCard(
    profile: Profile,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(DesignSystem.ComponentSize.listItemHeight)
            .clickable { onSelectionChange(!isSelected) },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(DesignSystem.Radii.md)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(DesignSystem.Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChange,
                modifier = Modifier.size(DesignSystem.ComponentSize.checkboxSize)
            )
            
            // Profile info
            Column(modifier = Modifier.weight(1f).padding(start = DesignSystem.Spacing.md)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (profile.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
            
            // Status indicator
            Box(
                modifier = Modifier
                    .size(DesignSystem.ComponentSize.statusIndicator)
                    .background(
                        color = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(DesignSystem.Radii.pill)
                    )
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.height(48.dp),
        placeholder = { Text("Search profiles...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(8.dp)
    )
}
