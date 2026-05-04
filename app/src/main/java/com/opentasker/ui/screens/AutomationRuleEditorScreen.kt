package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

/**
 * Screen for editing an automation rule with integrated picker screens.
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
    
    var currentScreen by remember { mutableStateOf<RuleEditorScreen>(RuleEditorScreen.Main) }
    
    when (currentScreen) {
        is RuleEditorScreen.Main -> {
            RuleEditorMainScreen(
                name = name,
                onNameChange = { name = it },
                description = description,
                onDescriptionChange = { description = it },
                enabled = enabled,
                onEnabledChange = { enabled = it },
                executionMode = executionMode,
                onExecutionModeChange = { executionMode = it },
                triggers = triggers,
                constraintGroups = constraintGroups,
                actions = actions,
                onAddTrigger = { currentScreen = RuleEditorScreen.TriggerPicker },
                onRemoveTrigger = { index ->
                    triggers = triggers.filterIndexed { i, _ -> i != index }
                },
                onAddConstraint = { currentScreen = RuleEditorScreen.ConstraintPicker },
                onRemoveConstraint = { index ->
                    constraintGroups = constraintGroups.filterIndexed { i, _ -> i != index }
                },
                onAddAction = { currentScreen = RuleEditorScreen.ActionPicker },
                onRemoveAction = { index ->
                    actions = actions.filterIndexed { i, _ -> i != index }
                },
                onSave = {
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
                onBack = onBack
            )
        }
        is RuleEditorScreen.TriggerPicker -> {
            TriggerPickerScreen(
                onTriggerSelected = { trigger ->
                    triggers = triggers + trigger
                    currentScreen = RuleEditorScreen.Main
                },
                onCancel = { currentScreen = RuleEditorScreen.Main }
            )
        }
        is RuleEditorScreen.ConstraintPicker -> {
            ConstraintPickerScreen(
                onConstraintGroupSelected = { group ->
                    constraintGroups = constraintGroups + group
                    currentScreen = RuleEditorScreen.Main
                },
                onCancel = { currentScreen = RuleEditorScreen.Main }
            )
        }
        is RuleEditorScreen.ActionPicker -> {
            ActionPickerScreen(
                onActionSelected = { action ->
                    actions = actions + action
                    currentScreen = RuleEditorScreen.Main
                },
                onCancel = { currentScreen = RuleEditorScreen.Main }
            )
        }
    }
}

sealed class RuleEditorScreen {
    data object Main : RuleEditorScreen()
    data object TriggerPicker : RuleEditorScreen()
    data object ConstraintPicker : RuleEditorScreen()
    data object ActionPicker : RuleEditorScreen()
}

@Composable
fun RuleEditorMainScreen(
    name: String,
    onNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    executionMode: String,
    onExecutionModeChange: (String) -> Unit,
    triggers: List<TriggerConfig>,
    constraintGroups: List<ConstraintGroup>,
    actions: List<ActionConfig>,
    onAddTrigger: () -> Unit,
    onRemoveTrigger: (Int) -> Unit,
    onAddConstraint: () -> Unit,
    onRemoveConstraint: (Int) -> Unit,
    onAddAction: () -> Unit,
    onRemoveAction: (Int) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Rule") },
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
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Rule Details",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        OutlinedTextField(
                            value = name,
                            onValueChange = onNameChange,
                            label = { Text("Rule Name") },
                            placeholder = { Text("e.g. Morning Notification") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        
                        OutlinedTextField(
                            value = description,
                            onValueChange = onDescriptionChange,
                            label = { Text("Description") },
                            placeholder = { Text("What does this rule do?") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3,
                            shape = RoundedCornerShape(8.dp)
                        )
                        
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Status", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    if (enabled) "Active" else "Inactive",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = enabled, onCheckedChange = onEnabledChange)
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Execution Mode", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    executionMode.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            ElevatedButton(onClick = {}) {
                                Text("Change")
                            }
                        }
                    }
                }
            }
            
            // Triggers section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
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
                            Column {
                                Text(
                                    text = "Triggers",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${triggers.size} trigger${if (triggers.size != 1) "s" else ""} configured",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = onAddTrigger,
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        
                        if (triggers.isEmpty()) {
                            Text(
                                text = "Add a trigger to define when this rule activates. Triggers can be events (WiFi connect, app open) or conditions (time, location).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }else {
                            triggers.forEachIndexed { index, trigger ->
                                TriggerConfigCard(
                                    trigger = trigger,
                                    onRemove = { onRemoveTrigger(index) }
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
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
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
                            Column {
                                Text(
                                    text = "Constraints",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${constraintGroups.size} group${if (constraintGroups.size != 1) "s" else ""} (optional)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = onAddConstraint,
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        
                        if (constraintGroups.isEmpty()) {
                            Text(
                                text = "Constraints refine when this rule executes. Combine multiple conditions with AND/OR logic for precise control.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            constraintGroups.forEachIndexed { index, group ->
                                ConstraintGroupCard(
                                    group = group,
                                    onRemove = { onRemoveConstraint(index) }
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
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
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
                            Column {
                                Text(
                                    text = "Actions",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${actions.size} action${if (actions.size != 1) "s" else ""} configured",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = onAddAction,
                                modifier = Modifier.height(36.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        
                        if (actions.isEmpty()) {
                            Text(
                                text = "Actions define what happens when this rule triggers. Execute notifications, send intents, run shell commands, and more.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }else {
                            actions.forEachIndexed { index, action ->
                                ActionConfigCard(
                                    action = action,
                                    onRemove = { onRemoveAction(index) }
                                )
                            }
                        }
                    }
                }
            }
            
            // Save/Cancel buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cancel")
                    }
                    
                    Button(
                        onClick = onSave,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = name.isNotBlank() && triggers.isNotEmpty() && actions.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Rule")
                    }
                }
                if (name.isBlank() || triggers.isEmpty() || actions.isEmpty()) {
                    Text(
                        text = buildString {
                            val issues = mutableListOf<String>()
                            if (name.isBlank()) issues.add("name")
                            if (triggers.isEmpty()) issues.add("at least one trigger")
                            if (actions.isEmpty()) issues.add("at least one action")
                            append("Required: ")
                            append(issues.joinToString(", "))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
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
                        text = trigger.config.entries.take(1).joinToString(", ") { "${it.key}=${it.value}" },
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
                        text = action.config.entries.take(1).joinToString(", ") { "${it.key}=${it.value}" },
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
