package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.AppDatabase
import com.opentasker.ui.theme.DesignSystem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunLogViewModel(private val db: AppDatabase) : ViewModel() {
    val logs: StateFlow<List<RunLogEntry>> = db.runLogDao().getRecentFlow()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RunLogScreen(
    db: AppDatabase,
    onBack: () -> Unit,
    viewModel: RunLogViewModel = viewModel { RunLogViewModel(db) },
) {
    val logs by viewModel.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Run Log",
                        style = MaterialTheme.typography.headlineMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (logs.isEmpty()) {
                // Premium empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(DesignSystem.Spacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.Info,
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
                        "Execution history appears here when profiles and tasks run. Enable a profile and execute its tasks to see logs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.2
                    )
                }
            }else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignSystem.Spacing.md)
                        .padding(top = DesignSystem.Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                ) {
                    items(logs) { entry ->
                        RunLogEntryItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
fun RunLogEntryItem(entry: RunLogEntry) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val timeStr = dateFormat.format(Date(entry.timestamp))
    val statusColor = if (entry.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val statusText = if (entry.success) "Success" else "Failed"

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(DesignSystem.Radii.md),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (entry.success)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = DesignSystem.Elevation.sm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DesignSystem.Spacing.lg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md)
                ) {
                    Icon(
                        imageVector = if (entry.success) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(DesignSystem.ComponentSize.iconMedium)
                    )
                    Text(
                        entry.taskName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(DesignSystem.Spacing.sm))
                if (entry.message.isNotEmpty()) {
                    Text(
                        entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(DesignSystem.Spacing.xs))
                }
                Text(
                    timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                color = statusColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(DesignSystem.Radii.xs),
                modifier = Modifier.padding(start = DesignSystem.Spacing.md)
            ) {
                Text(
                        statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = DesignSystem.Spacing.sm, vertical = DesignSystem.Spacing.xs)
                    )
                }
                Text(
                    "${entry.durationMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
