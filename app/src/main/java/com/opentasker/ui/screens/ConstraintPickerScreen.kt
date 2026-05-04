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
import com.opentasker.automation.model.ConstraintConfig
import com.opentasker.automation.model.ConstraintGroup
import com.opentasker.automation.model.LogicalOperator

/**
 * Screen to pick and configure constraint groups.
 */
@Composable
fun ConstraintPickerScreen(
    onConstraintGroupSelected: (ConstraintGroup) -> Unit,
    onCancel: () -> Unit
) {
    var selectedConstraintType by remember { mutableStateOf<String?>(null) }
    var constraintOperator by remember { mutableStateOf("AND") }
    
    if (selectedConstraintType != null) {
        ConstraintConfigurationScreen(
            constraintType = selectedConstraintType!!,
            operator = constraintOperator,
            onSave = { constraints ->
                onConstraintGroupSelected(
                    ConstraintGroup(
                        operator = LogicalOperator.valueOf(constraintOperator),
                        constraints = constraints
                    )
                )
            },
            onBack = { selectedConstraintType = null }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Add Constraint") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Operator selection
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Logic:", style = MaterialTheme.typography.labelMedium)
                        repeat(2) { index ->
                            FilterChip(
                                selected = constraintOperator == listOf("AND", "OR")[index],
                                onClick = {
                                    constraintOperator = listOf("AND", "OR")[index]
                                },
                                label = { Text(listOf("AND", "OR")[index]) }
                            )
                        }
                    }
                }
                
                // Constraint types
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(CONSTRAINT_TYPES) { constraint ->
                        ConstraintTypeCard(
                            constraint = constraint,
                            onClick = { selectedConstraintType = constraint.id }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConstraintTypeCard(
    constraint: ConstraintTypeDefinition,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        shape = RoundedCornerShape(8.dp),
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
                    text = constraint.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = constraint.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Select ${constraint.name}"
                )
            }
        }
    }
}

@Composable
fun ConstraintConfigurationScreen(
    constraintType: String,
    operator: String,
    onSave: (List<ConstraintConfig>) -> Unit,
    onBack: () -> Unit
) {
    val constraintDef = CONSTRAINT_TYPES.find { it.id == constraintType }
    
    val config = remember { mutableStateMapOf<String, String>() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure ${constraintDef?.name ?: "Constraint"}") },
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
            if (constraintDef != null) {
                item {
                    Text(
                        text = constraintDef.description,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                items(constraintDef.configFields) { field ->
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
                            listOf(
                                ConstraintConfig(
                                    id = constraintType,
                                    config = config.toMap()
                                )
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Constraint")
                }
            }
        }
    }
}

// Data classes for constraint types
data class ConstraintTypeDefinition(
    val id: String,
    val name: String,
    val description: String,
    val configFields: List<ConfigField>
)

val CONSTRAINT_TYPES = listOf(
    ConstraintTypeDefinition(
        id = "time",
        name = "Time Constraint",
        description = "Only allow rule to execute during certain hours",
        configFields = listOf(
            ConfigField(
                key = "startHour",
                label = "Start Hour (0-23)",
                type = "number",
                placeholder = "e.g., 9",
                helpText = "Hour to start allowing execution"
            ),
            ConfigField(
                key = "endHour",
                label = "End Hour (0-23)",
                type = "number",
                placeholder = "e.g., 17",
                helpText = "Hour to stop allowing execution"
            )
        )
    ),
    ConstraintTypeDefinition(
        id = "screenstate",
        name = "Screen State Constraint",
        description = "Only allow rule to execute when screen is on/off",
        configFields = listOf(
            ConfigField(
                key = "screenOn",
                label = "Screen State",
                type = "dropdown",
                placeholder = "On",
                options = listOf("On", "Off", "Any"),
                helpText = "Require screen to be on or off"
            )
        )
    ),
    ConstraintTypeDefinition(
        id = "network",
        name = "Network Constraint",
        description = "Only allow rule when connected to specific network type",
        configFields = listOf(
            ConfigField(
                key = "networkType",
                label = "Network Type",
                type = "dropdown",
                placeholder = "WiFi",
                options = listOf("WiFi", "Mobile", "Ethernet", "Any"),
                helpText = "Required network connectivity"
            )
        )
    ),
    ConstraintTypeDefinition(
        id = "variable",
        name = "Variable Constraint",
        description = "Only allow rule when variable meets a condition",
        configFields = listOf(
            ConfigField(
                key = "variableName",
                label = "Variable Name",
                type = "text",
                placeholder = "e.g., counter",
                helpText = "Name of variable to check"
            ),
            ConfigField(
                key = "operator",
                label = "Operator",
                type = "dropdown",
                placeholder = "Equals",
                options = listOf("Equals", "Not Equals", "Greater", "Less", "Contains"),
                helpText = "Comparison operator"
            ),
            ConfigField(
                key = "value",
                label = "Value",
                type = "text",
                placeholder = "e.g., true or 42",
                helpText = "Value to compare against"
            )
        )
    )
)
