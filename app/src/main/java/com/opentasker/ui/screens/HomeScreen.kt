package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Home screen providing navigation to main app sections.
 */
@Composable
fun HomeScreen(
    onProfilesClick: () -> Unit,
    onAutomationRulesClick: () -> Unit,
    onRunLogClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenTasker") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text(
                text = "Choose what to do",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            
            // Profiles card
            NavigationCard(
                icon = Icons.Default.Settings,
                title = "Profiles",
                description = "Manage automation profiles and tasks (legacy system)",
                onClick = onProfilesClick
            )
            
            // Automation Rules card
            NavigationCard(
                icon = Icons.Default.AutoAwesome,
                title = "Automation Rules",
                description = "Create event-driven automation rules (new system)",
                onClick = onAutomationRulesClick
            )
            
            // Execution Log card
            NavigationCard(
                icon = Icons.Default.History,
                title = "Execution Log",
                description = "View automation execution history",
                onClick = onRunLogClick
            )
        }
    }
}

@Composable
fun NavigationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            
            Column(
                modifier = Modifier
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Go to $title"
                )
            }
        }
    }
}
