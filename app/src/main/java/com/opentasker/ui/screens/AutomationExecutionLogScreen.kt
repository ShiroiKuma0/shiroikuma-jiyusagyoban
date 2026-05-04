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
import com.opentasker.automation.model.ExecutionLog
import com.opentasker.automation.model.ExecutionStatus
import com.opentasker.ui.theme.DesignSystem
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen for viewing execution logs of automation rules.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AutomationExecutionLogScreen(
    logs: List<ExecutionLog> = emptyList(),
    onBack: () -> Unit
) {
    var filterByRule by remember { mutableStateOf<String?>(null) }
    var filterBySuccess by remember { mutableStateOf<Boolean?>(null) }
    
    val filteredLogs = logs.filter { log ->
        (filterByRule == null || log.ruleId == filterByRule) &&
        (filterBySuccess == null || ((log.status == ExecutionStatus.SUCCESS) == (filterBySuccess == true)))
    }.sortedByDescending { it.timestamp }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Execution Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            // Filter bar
            if (logs.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignSystem.Spacing.md, vertical = DesignSystem.Spacing.sm),
                    shape = RoundedCornerShape(DesignSystem.Radii.md),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(DesignSystem.Spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = filterBySuccess == true,
                            onClick = { 
                                filterBySuccess = if (filterBySuccess == true) null else true
                            },
                            label = { Text("Successful") },
                            leadingIcon = if (filterBySuccess == true) {
                                { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall)) }
                            } else null
                        )
                        FilterChip(
                            selected = filterBySuccess == false,
                            onClick = { 
                                filterBySuccess = if (filterBySuccess == false) null else false
                            },
                            label = { Text("Failed") },
                            leadingIcon = if (filterBySuccess == false) {
                                { Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall)) }
                            } else null
                        )
                        if (filterByRule != null || filterBySuccess != null) {
                            Spacer(modifier = Modifier.weight(1f))
                            AssistChip(
                                onClick = { 
                                    filterByRule = null
                                    filterBySuccess = null
                                },
                                label = { Text("Clear filters") },
                                leadingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(DesignSystem.ComponentSize.iconSmall))
                                }
                            )
                        }
                    }
                }
            }
            
            // Logs list
            if (filteredLogs.isEmpty()) {
                EmptyLogState()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(DesignSystem.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                ) {
                    items(filteredLogs) { log ->
                        ExecutionLogCard(log = log)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyLogState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(DesignSystem.Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.HistoryToggleOff,
            contentDescription = null,
            modifier = Modifier.size(DesignSystem.ComponentSize.iconXLarge),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(DesignSystem.Spacing.xl))
        Text(
            "No execution history yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(DesignSystem.Spacing.md))
        Text(
            "Execution logs will appear here once your automation rules begin running. Create a rule and enable it to see logs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
        )
    }
}

@Composable
fun ExecutionLogCard(log: ExecutionLog) {
    val isSuccess = log.status == ExecutionStatus.SUCCESS
    val successCount = log.actionResults.count { it.second.success }
    val actionCount = log.actionResults.size
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) 
                MaterialTheme.colorScheme.surfaceVariant
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignSystem.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                    ) {
                        Icon(
                            imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(DesignSystem.ComponentSize.iconMedium)
                        )
                        Column {
                            Text(
                                text = log.triggerType ?: "Unknown Event",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if ((log.message ?: "").isNotEmpty()) {
                                Text(
                                    text = log.message ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${log.executionTimeMs}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                
                Surface(
                    color = if (successCount == actionCount)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else
                        MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "$successCount/$actionCount actions",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (successCount == actionCount)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            
            if ((log.message ?: "").isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        "Just now"
    }
}
