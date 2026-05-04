package com.opentasker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.AppDatabase

/**
 * Screen for batch operations on profiles: multi-select, enable/disable/delete all.
 * Supports search/filter by profile name or context type.
 */
@Composable
fun BatchOperationsScreen(
    db: AppDatabase,
    onBack: () -> Unit
) {
    val allProfiles by db.profileDao().getAllLive().collectAsState(emptyList())
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    
    val filtered = allProfiles.filter { profile ->
        searchQuery.isEmpty() || profile.name.contains(searchQuery, ignoreCase = true)
    }
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch Operations (${selectedIds.size} selected)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
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
                    .padding(8.dp)
            )
            
            // Batch action buttons
            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = {
                        // Enable all selected
                        selectedIds.forEach { id ->
                            val profile = allProfiles.find { it.id == id } ?: return@forEach
                            db.profileDao().insertOrUpdate(profile.copy(enabled = true))
                        }
                        selectedIds = emptySet()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Done, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Enable", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    Button(onClick = {
                        // Disable all selected
                        selectedIds.forEach { id ->
                            val profile = allProfiles.find { it.id == id } ?: return@forEach
                            db.profileDao().insertOrUpdate(profile.copy(enabled = false))
                        }
                        selectedIds = emptySet()
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Block, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disable", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
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
                            .padding(horizontal = 8.dp, vertical = 4.dp)
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
                        selectedIds.forEach { id ->
                            db.profileDao().delete(allProfiles.find { it.id == id } ?: return@forEach)
                        }
                        selectedIds = emptySet()
                        showDeleteDialog = false
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
            .height(64.dp)
            .clickable { onSelectionChange(!isSelected) },
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Checkbox
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChange,
                modifier = Modifier.size(24.dp)
            )
            
            // Profile info
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
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
                    .size(12.dp)
                    .background(
                        color = if (profile.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(6.dp)
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
