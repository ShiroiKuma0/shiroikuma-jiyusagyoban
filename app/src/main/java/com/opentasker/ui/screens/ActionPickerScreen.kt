package com.opentasker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.automation.model.ActionConfig

/**
 * Screen to pick and configure an action type.
 */
@Composable
fun ActionPickerScreen(
    onActionSelected: (ActionConfig) -> Unit,
    onCancel: () -> Unit
) {
    var selectedActionType by remember { mutableStateOf<String?>(null) }
    
    if (selectedActionType != null) {
        ActionConfigurationScreen(
            actionType = selectedActionType!!,
            onSave = { config ->
                onActionSelected(config)
            },
            onBack = { selectedActionType = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Select Action Type") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
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
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ACTION_TYPES) { action ->
                    ActionTypeCard(
                        action = action,
                        onClick = { selectedActionType = action.id }
                    )
                }
            }
        }
    }
}

@Composable
fun ActionTypeCard(
    action: ActionTypeDefinition,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = action.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Select ${action.name}",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ActionConfigurationScreen(
    actionType: String,
    onSave: (ActionConfig) -> Unit,
    onBack: () -> Unit
) {
    val actionDef = ACTION_TYPES.find { it.id == actionType }
    
    val config = remember { mutableStateMapOf<String, String>() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure ${actionDef?.name ?: "Action"}") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (actionDef != null) {
                item {
                    Text(
                        text = actionDef.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                items(actionDef.configFields) { field ->
                    TriggerConfigField(
                        field = field,
                        value = config[field.key] ?: "",
                        onValueChange = { value ->
                            config[field.key] = value
                        }
                    )
                }
            }
            
            item {
                Button(
                    onClick = {
                        onSave(
                            ActionConfig(
                                id = actionType,
                                config = config.toMap()
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Action")
                }
            }
        }
    }
}

// Data classes for action types
data class ActionTypeDefinition(
    val id: String,
    val name: String,
    val description: String,
    val configFields: List<ConfigField>
)

val ACTION_TYPES = listOf(
    ActionTypeDefinition(
        id = "notification",
        name = "Notification Action",
        description = "Show a notification to the user",
        configFields = listOf(
            ConfigField(
                key = "title",
                label = "Notification Title",
                type = "text",
                placeholder = "e.g., Meeting Starting"
            ),
            ConfigField(
                key = "message",
                label = "Notification Message",
                type = "text",
                placeholder = "e.g., Your 2pm meeting is starting",
                helpText = "Message body"
            ),
            ConfigField(
                key = "enableSound",
                label = "Enable Sound",
                type = "dropdown",
                placeholder = "Yes",
                options = listOf("Yes", "No")
            ),
            ConfigField(
                key = "enableVibration",
                label = "Enable Vibration",
                type = "dropdown",
                placeholder = "Yes",
                options = listOf("Yes", "No")
            )
        )
    ),
    ActionTypeDefinition(
        id = "intent",
        name = "Intent Action",
        description = "Launch an activity, service, or broadcast",
        configFields = listOf(
            ConfigField(
                key = "action",
                label = "Action Type",
                type = "dropdown",
                placeholder = "Activity",
                options = listOf("Activity", "Service", "Broadcast"),
                helpText = "What to launch"
            ),
            ConfigField(
                key = "targetPackage",
                label = "Target Package",
                type = "text",
                placeholder = "e.g., com.example.app"
            ),
            ConfigField(
                key = "targetActivity",
                label = "Target Activity/Component",
                type = "text",
                placeholder = "e.g., .MainActivity",
                helpText = "Activity, service, or broadcast receiver"
            ),
            ConfigField(
                key = "action",
                label = "Intent Action",
                type = "text",
                placeholder = "e.g., android.intent.action.MAIN",
                helpText = "Leave empty for default"
            )
        )
    ),
    ActionTypeDefinition(
        id = "shell",
        name = "Shell Action",
        description = "Execute a shell command",
        configFields = listOf(
            ConfigField(
                key = "command",
                label = "Command",
                type = "text",
                placeholder = "e.g., am broadcast -a com.example.ACTION",
                helpText = "Shell command to execute"
            ),
            ConfigField(
                key = "timeoutSeconds",
                label = "Timeout (seconds)",
                type = "number",
                placeholder = "30",
                helpText = "Max time to wait for command"
            )
        )
    ),
    ActionTypeDefinition(
        id = "delay",
        name = "Delay Action",
        description = "Wait before executing next actions",
        configFields = listOf(
            ConfigField(
                key = "delayMillis",
                label = "Delay (milliseconds)",
                type = "number",
                placeholder = "e.g., 1000",
                helpText = "Time to wait"
            )
        )
    ),
    ActionTypeDefinition(
        id = "variable",
        name = "Variable Action",
        description = "Set or modify a variable value",
        configFields = listOf(
            ConfigField(
                key = "variableName",
                label = "Variable Name",
                type = "text",
                placeholder = "e.g., counter",
                helpText = "Name of variable to set"
            ),
            ConfigField(
                key = "operation",
                label = "Operation",
                type = "dropdown",
                placeholder = "Set",
                options = listOf("Set", "Increment", "Decrement", "Append"),
                helpText = "How to modify variable"
            ),
            ConfigField(
                key = "value",
                label = "Value",
                type = "text",
                placeholder = "e.g., 42 or 'hello'",
                helpText = "New value for variable"
            )
        )
    )
)
