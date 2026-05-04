package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.flow.Flow
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
                title = { Text("Run Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (logs.isEmpty()) {
                Text(
                    "No entries yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn {
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.taskName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        timeStr,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    if (entry.success) "✓" else "✗",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (entry.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            if (entry.message.isNotEmpty()) {
                Text(
                    entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                "${entry.durationMs}ms",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
