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
import androidx.compose.ui.unit.dp
import com.opentasker.automation.model.TriggerConfig
import com.opentasker.ui.theme.DesignSystem

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
                    title = { 
                        Text(
                            "Select Trigger Type",
                            style = MaterialTheme.typography.headlineMedium
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(DesignSystem.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignSystem.Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = trigger.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(DesignSystem.Spacing.sm))
                Text(
                    text = trigger.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Select ${trigger.name}",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(DesignSystem.ComponentSize.iconMedium)
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TriggerConfigurationScreen(
    triggerType: String,
    onSave: (TriggerConfig) -> Unit,
    onBack: () -> Unit
) {
    val triggerDef = TRIGGER_TYPES.find { it.id == triggerType }
    
    val config = remember { mutableStateMapOf<String, String>() }
    var isSaving by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Configure ${triggerDef?.name ?: "Trigger"}",
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
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(DesignSystem.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
        ) {
            if (triggerDef != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(DesignSystem.Radii.md),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(DesignSystem.Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)
                        ) {
                            Text(
                                text = triggerDef.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = triggerDef.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(1f)
                            .height(DesignSystem.ComponentSize.buttonLarge),
                        shape = RoundedCornerShape(DesignSystem.Radii.md)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall))
                        Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
                        Text("Cancel")
                    }
                    
                    Button(
                        onClick = {
                            isSaving = true
                            try {
                                onSave(
                                    TriggerConfig(
                                        id = triggerType,
                                        config = config.toMap()
                                    )
                                )
                            } finally {
                                isSaving = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(DesignSystem.ComponentSize.buttonLarge),
                        shape = RoundedCornerShape(DesignSystem.Radii.md),
                        enabled = !isSaving
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall))
                        Spacer(modifier = Modifier.width(DesignSystem.Spacing.sm))
                        Text("Add Trigger")
                    }
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
    Column(verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.labelMedium
        )
        
        when (field.type) {
            "text" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(field.placeholder) },
                    singleLine = true
                )
            }
            "number" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(field.placeholder) },
                    singleLine = true
                )
            }
            "dropdown" -> {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(field.placeholder) },
                    singleLine = true
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
