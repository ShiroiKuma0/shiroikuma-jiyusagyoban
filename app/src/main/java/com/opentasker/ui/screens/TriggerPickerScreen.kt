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
import com.opentasker.automation.model.TriggerConfig

/**
 * Screen to pick and configure a trigger type.
 */
@Composable
fun TriggerPickerScreen(
    onTriggerSelected: (TriggerConfig) -> Unit,
    onCancel: () -> Unit
) {
    var selectedTriggerType by remember { mutableStateOf<String?>(null) }
    
    if (selectedTriggerType != null) {
        TriggerConfigurationScreen(
            triggerType = selectedTriggerType!!,
            onSave = { config ->
                onTriggerSelected(config)
            },
            onBack = { selectedTriggerType = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Select Trigger Type") },
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
                items(TRIGGER_TYPES) { trigger ->
                    TriggerTypeCard(
                        trigger = trigger,
                        onClick = { selectedTriggerType = trigger.id }
                    )
                }
            }
        }
    }
}

@Composable
fun TriggerTypeCard(
    trigger: TriggerTypeDefinition,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp)
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
                    text = trigger.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = trigger.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Select ${trigger.name}"
                )
            }
        }
    }
}

@Composable
fun TriggerConfigurationScreen(
    triggerType: String,
    onSave: (TriggerConfig) -> Unit,
    onBack: () -> Unit
) {
    val triggerDef = TRIGGER_TYPES.find { it.id == triggerType }
    
    val config = remember { mutableStateMapOf<String, String>() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure ${triggerDef?.name ?: "Trigger"}") },
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
            if (triggerDef != null) {
                item {
                    Text(
                        text = triggerDef.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                items(triggerDef.configFields) { field ->
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
                            TriggerConfig(
                                id = triggerType,
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
                    Text("Add Trigger")
                }
            }
        }
    }
}

@Composable
fun TriggerConfigField(
    field: ConfigField,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium
        )
        
        when (field.type) {
            "text" -> {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(field.placeholder) }
                )
            }
            "number" -> {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(field.placeholder) }
                )
            }
            "dropdown" -> {
                // Simplified dropdown - would need full implementation
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(field.placeholder) }
                )
            }
            else -> {}
        }
        
        if (field.helpText.isNotEmpty()) {
            Text(
                text = field.helpText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Data classes for trigger types
data class TriggerTypeDefinition(
    val id: String,
    val name: String,
    val description: String,
    val configFields: List<ConfigField>
)

data class ConfigField(
    val key: String,
    val label: String,
    val type: String, // "text", "number", "dropdown"
    val placeholder: String,
    val helpText: String = "",
    val options: List<String> = emptyList()
)

val TRIGGER_TYPES = listOf(
    TriggerTypeDefinition(
        id = "time",
        name = "Time Trigger",
        description = "Trigger at specific times using cron expressions",
        configFields = listOf(
            ConfigField(
                key = "cronExpression",
                label = "Cron Expression",
                type = "text",
                placeholder = "e.g., 0 9 * * MON-FRI",
                helpText = "Format: minute hour day month dayOfWeek"
            ),
            ConfigField(
                key = "timezone",
                label = "Timezone",
                type = "text",
                placeholder = "e.g., America/New_York",
                helpText = "Leave empty for device timezone"
            )
        )
    ),
    TriggerTypeDefinition(
        id = "wifi",
        name = "WiFi Trigger",
        description = "Trigger when connecting to a WiFi network",
        configFields = listOf(
            ConfigField(
                key = "ssidPattern",
                label = "SSID Pattern",
                type = "text",
                placeholder = "e.g., MyNetwork or .*Office.*",
                helpText = "Supports regex patterns"
            )
        )
    ),
    TriggerTypeDefinition(
        id = "battery",
        name = "Battery Trigger",
        description = "Trigger when battery level changes",
        configFields = listOf(
            ConfigField(
                key = "batteryLevel",
                label = "Battery Level Threshold (%)",
                type = "number",
                placeholder = "e.g., 20",
                helpText = "Trigger when battery drops below this level"
            ),
            ConfigField(
                key = "chargeState",
                label = "Charge State",
                type = "dropdown",
                placeholder = "Any",
                helpText = "Filter by charging status",
                options = listOf("Any", "Charging", "Discharging")
            )
        )
    ),
    TriggerTypeDefinition(
        id = "geofence",
        name = "Geofence Trigger",
        description = "Trigger when entering/leaving a location",
        configFields = listOf(
            ConfigField(
                key = "latitude",
                label = "Latitude",
                type = "number",
                placeholder = "e.g., 40.7128"
            ),
            ConfigField(
                key = "longitude",
                label = "Longitude",
                type = "number",
                placeholder = "e.g., -74.0060"
            ),
            ConfigField(
                key = "radiusMeters",
                label = "Radius (meters)",
                type = "number",
                placeholder = "e.g., 100",
                helpText = "Distance from point to trigger"
            )
        )
    ),
    TriggerTypeDefinition(
        id = "appopen",
        name = "App Open Trigger",
        description = "Trigger when a specific app is opened",
        configFields = listOf(
            ConfigField(
                key = "packageName",
                label = "App Package Name",
                type = "text",
                placeholder = "e.g., com.example.app",
                helpText = "Package name of the app to monitor"
            )
        )
    )
)
