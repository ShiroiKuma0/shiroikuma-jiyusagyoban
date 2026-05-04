package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentasker.automation.model.AutomationRule
import com.opentasker.automation.model.TriggerConfig
import com.opentasker.automation.model.ConstraintConfig
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ConstraintGroup
import com.opentasker.automation.model.LogicalOperator
import com.opentasker.ui.theme.DesignSystem

/**
 * Screen for viewing and managing all automation rules.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AutomationRuleListScreen(
    rules: List<AutomationRule> = emptyList(),
    onCreateRule: () -> Unit,
    onEditRule: (AutomationRule) -> Unit,
    onDeleteRule: (AutomationRule) -> Unit,
    onToggleRule: (AutomationRule) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Automation Rules",
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onCreateRule) {
                        Icon(Icons.Default.Add, contentDescription = "Create rule")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (rules.isEmpty()) {
                EmptyRulesState(onCreateRule)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(DesignSystem.Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                ) {
                    items(rules) { rule ->
                        RuleCard(
                            rule = rule,
                            onEdit = { onEditRule(rule) },
                            onDelete = { onDeleteRule(rule) },
                            onToggle = { onToggleRule(rule) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyRulesState(onCreateRule: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(DesignSystem.Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(DesignSystem.ComponentSize.iconXLarge),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
        Text(
            "No automation rules yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
        Text(
            "Create your first rule to automate tasks based on triggers and conditions. Rules execute automatically when their conditions are met.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
        )
        Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
        Button(
            onClick = onCreateRule,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(DesignSystem.ComponentSize.buttonLarge),
            shape = RoundedCornerShape(DesignSystem.Radii.md)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
            Text("Create First Rule")
        }
    }
}

@Composable
fun RuleCard(
    rule: AutomationRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete \"${rule.name}\"?") },
            text = { Text("This action cannot be undone. All rule history will be permanently deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) 
                MaterialTheme.colorScheme.surfaceVariant
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm),
        onClick = onEdit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignSystem.Spacing.lg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = DesignSystem.Spacing.md)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                    ) {
                        Text(
                            text = rule.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        StatusBadge(enabled = rule.enabled)
                    }
                    
                    if (rule.description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(DesignSystem.Spacing.sm))
                        Text(
                            text = rule.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    RuleSummary(rule = rule)
                }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.xs),
                    modifier = Modifier.padding(start = DesignSystem.Spacing.md)
                ) {
                    IconButton(
                        onClick = onToggle,
                        modifier = Modifier.size(DesignSystem.ComponentSize.buttonMedium)
                    ) {
                        Icon(
                            imageVector = if (rule.enabled) Icons.Default.ToggleOn else Icons.Default.ToggleOff,
                            contentDescription = if (rule.enabled) "Disable rule" else "Enable rule",
                            modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall),
                            tint = if (rule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(DesignSystem.ComponentSize.buttonMedium)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit rule",
                            modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall)
                        )
                    }
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(DesignSystem.ComponentSize.buttonMedium)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete rule",
                            modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatusBadge(enabled: Boolean) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
        shape = RoundedCornerShape(DesignSystem.Radii.xs)
    ) {
        Text(
            text = if (enabled) "Active" else "Inactive",
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = DesignSystem.Spacing.sm, vertical = DesignSystem.Spacing.xs)
        )
    }
}

@Composable
fun RuleSummary(rule: AutomationRule) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SummaryBadge("${rule.triggers.size}", "Trigger${if (rule.triggers.size != 1) "s" else ""}")
        
        if (rule.constraintGroups.isNotEmpty()) {
            SummaryBadge("${rule.constraintGroups.size}", "Constraint${if (rule.constraintGroups.size != 1) "s" else ""}")
        }
        
        SummaryBadge("${rule.actions.size}", "Action${if (rule.actions.size != 1) "s" else ""}")
    }
}

@Composable
fun SummaryBadge(count: String, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = count,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
