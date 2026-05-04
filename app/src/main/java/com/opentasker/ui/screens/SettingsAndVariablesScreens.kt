package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentasker.core.model.Variable
import com.opentasker.core.storage.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class VariablesViewModel(private val db: AppDatabase) : ViewModel() {
    fun variables(): Flow<List<Variable>> = flowOf(emptyList())
    // TODO: load variables from DB
}

@Composable
fun VariablesScreen(
    onBack: () -> Unit,
    viewModel: VariablesViewModel = viewModel(),
) {
    var newVarName by remember { mutableStateOf("") }
    var newVarValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Variables") },
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
                .padding(16.dp)
        ) {
            Text(
                "Global variables (uppercase)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = newVarName,
                onValueChange = { newVarName = it.uppercase() },
                label = { Text("Variable name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = newVarValue,
                onValueChange = { newVarValue = it },
                label = { Text("Value") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            androidx.compose.material3.Button(
                onClick = { /* TODO: save */ newVarName = ""; newVarValue = "" },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Set Variable")
            }

            LazyColumn(modifier = Modifier.padding(top = 16.dp)) {
                items(0) { // TODO: actual variables
                    // Card with variable and delete button
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    isDarkMode: Boolean = true,
    onThemeToggle: (Boolean) -> Unit = {},
) {
    var darkMode by remember { mutableStateOf(isDarkMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Text("Dark mode", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = darkMode,
                        onCheckedChange = { darkMode = it; onThemeToggle(it) }
                    )
                }
            }

            Text(
                "OpenTasker v0.1.0",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 32.dp)
            )
            Text(
                "Open-source Tasker alternative\nhttps://github.com/SysAdminDoc/OpenTasker",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
