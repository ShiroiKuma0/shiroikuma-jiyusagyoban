package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.opentasker.automation.model.AutomationRule
import com.opentasker.automation.model.TriggerConfig
import com.opentasker.automation.model.ConstraintConfig
import com.opentasker.automation.model.ActionConfig
import com.opentasker.automation.model.ConstraintGroup
import com.opentasker.automation.model.LogicalOperator

/**
 * Screen for editing an automation rule.
 */
@Composable
fun AutomationRuleEditorScreen(
    rule: AutomationRule?,
    onSave: (AutomationRule) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(rule?.name ?: "") }
    var description by remember { mutableStateOf(rule?.description ?: "") }
    var enabled by remember { mutableStateOf(rule?.enabled ?: true) }
    var executionMode by remember { mutableStateOf(rule?.executionMode ?: "parallel") }
    
    var triggers by remember { mutableStateOf(rule?.triggers ?: emptyList()) }
    var constraintGroups by remember { mutableStateOf(rule?.constraintGroups ?: emptyList()) }
    var actions by remember { mutableStateOf(rule?.actions ?: emptyList()) }
    
    var showTriggerPicker by remember { mutableStateOf(false) }
    var showConstraintPicker by remember { mutableStateOf(false) }
    var showActionPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (rule == null) "Create Rule" else "Edit Rule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Rule name and description
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Rule Details",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Rule Name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        
                        TextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Enabled")
                            Switch(checked = enabled, onCheckedChange = { enabled = it })
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Execution Mode")
                            DropdownMenu(
                                expanded = false,
                                onDismissRequest = {},
                                modifier = Modifier.align(Alignment.CenterVertically)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Parallel") },
                                    onClick = { executionMode = "parallel" }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sequential") },
                                    onClick = { executionMode = "sequential" }
                                )
                            }
                            Text(executionMode.capitalize())
                        }
                    }
                }
            }
            
            // Triggers section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Triggers (${triggers.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(onClick = { showTriggerPicker = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Add trigger")
                            }
                        }
                        
                        if (triggers.isEmpty()) {
                            Text(
                                text = "Add a trigger to define when this rule activates",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            triggers.forEachIndexed { index, trigger ->
                                TriggerConfigCard(
                                    trigger = trigger,
                                    onRemove = { triggers = triggers.filterIndexed { i, _ -> i != index } }
                                )
                            }
                        }
                    }
                }
            }
            
            // Constraints section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Constraints (${constraintGroups.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(onClick = { showConstraintPicker = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Add constraint")
                            }
                        }
                        
                        if (constraintGroups.isEmpty()) {
                            Text(
                                text = "Add constraints to refine when this rule executes (optional)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            constraintGroups.forEachIndexed { index, group ->
                                ConstraintGroupCard(
                                    group = group,
                                    onRemove = { constraintGroups = constraintGroups.filterIndexed { i, _ -> i != index } }
                                )
                            }
                        }
                    }
                }
            }
            
            // Actions section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Actions (${actions.size})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            IconButton(onClick = { showActionPicker = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Add action")
                            }
                        }
                        
                        if (actions.isEmpty()) {
                            Text(
                                text = "Add actions to define what happens when this rule triggers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            actions.forEachIndexed { index, action ->
                                ActionConfigCard(
                                    action = action,
                                    onRemove = { actions = actions.filterIndexed { i, _ -> i != index } }
                                )
                            }
                        }
                    }
                }
            }
            
            // Save button
            item {
                Button(
                    onClick = {
                        if (name.isNotBlank() && triggers.isNotEmpty() && actions.isNotEmpty()) {
                            val newRule = AutomationRule(
                                id = rule?.id ?: System.nanoTime().toString(),
                                name = name,
                                description = description,
                                enabled = enabled,
                                profileId = rule?.profileId ?: "default",
                                triggers = triggers,
                                constraintGroups = constraintGroups,
                                actions = actions,
                                executionMode = executionMode
                            )
                            onSave(newRule)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    enabled = name.isNotBlank() && triggers.isNotEmpty() && actions.isNotEmpty()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Rule")
                }
            }
        }
    }
}

@Composable
fun TriggerConfigCard(
    trigger: TriggerConfig,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trigger.id ?: "Unknown",
                    style = MaterialTheme.typography.labelMedium
                )
                if (trigger.config.isNotEmpty()) {
                    Text(
                        text = trigger.config.entries.joinToString(", ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove")
            }
        }
    }
}

@Composable
fun ConstraintGroupCard(
    group: ConstraintGroup,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${group.operator}: ${group.constraints.size} constraint(s)",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove")
            }
        }
    }
}

@Composable
fun ActionConfigCard(
    action: ActionConfig,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.id ?: "Unknown",
                    style = MaterialTheme.typography.labelMedium
                )
                if (action.config.isNotEmpty()) {
                    Text(
                        text = action.config.entries.take(2).joinToString(", ") { "${it.key}=${it.value}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove")
            }
        }
    }
}
