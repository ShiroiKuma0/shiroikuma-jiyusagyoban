package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.ui.theme.DesignSystem

/**
 * Home screen providing navigation to main app sections.
 */
@Composable
fun HomeScreen(
    onProfilesClick: () -> Unit,
    onAutomationRulesClick: () -> Unit,
    onRunLogClick: () -> Unit,
    onTasksClick: () -> Unit
){
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("OpenTasker", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Automation Platform",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl, Alignment.Top)
        ) {
            Text(
                text = "What would you like to do?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // Automation Rules card
                NavigationCard(
                    icon = Icons.Default.AutoAwesome,
                    title = "Automation Rules",
                    description = "Create event-driven automation rules (new system)",
                    onClick = onAutomationRulesClick
                )
                
                // Tasks card
                NavigationCard(
                    icon = Icons.Default.CheckCircle,
                    title = "Tasks",
                    description = "Manage reusable actions and task templates",
                    onClick = onTasksClick
                )
                
                // Profiles card
                NavigationCard(
                    icon = Icons.Default.Settings,
                    title = "Profiles",
                    description = "Manage automation profiles (legacy system)",
                    onClick = onProfilesClick
                )
                
                // Execution Log card
                NavigationCard(
                    icon = Icons.Default.History,
                    title = "Execution Log",
                    description = "View automation execution history and performance",
                    onClick = onRunLogClick
                )
            }
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
            .height(ComponentSize.buttonXLarge + Spacing.lg),
        shape = RoundedCornerShape(Radii.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        onClick = onClick,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ComponentSize.iconLarge),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
            }
            
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(ComponentSize.iconMedium),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        }
    }
}
