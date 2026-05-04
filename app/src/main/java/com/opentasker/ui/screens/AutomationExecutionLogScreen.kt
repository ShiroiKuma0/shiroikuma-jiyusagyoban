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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentasker.automation.model.ExecutionLog
import java.text.SimpleDateFormat
import java.util.*

/**
 * Screen for viewing execution logs of automation rules.
 */
@Composable
fun AutomationExecutionLogScreen(
    logs: List<ExecutionLog> = emptyList(),
    onBack: () -> Unit
) {
    var filterByRule by remember { mutableStateOf<String?>(null) }
    var filterBySuccess by remember { mutableStateOf<Boolean?>(null) }
    
    val filteredLogs = logs.filter { log ->
        (filterByRule == null || log.ruleId == filterByRule) &&
        (filterBySuccess == null || (log.success == (filterBySuccess == true)))
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = filterBySuccess == true,
                            onClick = { 
                                filterBySuccess = if (filterBySuccess == true) null else true
                            },
                            label = { Text("Successful") },
                            leadingIcon = if (filterBySuccess == true) {
                                { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = filterBySuccess == false,
                            onClick = { 
                                filterBySuccess = if (filterBySuccess == false) null else false
                            },
                            label = { Text("Failed") },
                            leadingIcon = if (filterBySuccess == false) {
                                { Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(16.dp)) }
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
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
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
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.HistoryToggleOff,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "No execution history yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (log.success) 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (log.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (log.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = log.eventType ?: "Unknown Event",
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
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                    color = if (log.successCount == log.actionCount)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    else
                        MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${log.successCount}/${log.actionCount} actions",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (log.successCount == log.actionCount)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            
            if (log.details.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = log.details,
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
