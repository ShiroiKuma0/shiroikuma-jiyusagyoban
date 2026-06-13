package com.opentasker.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.flow.map

class TaskWidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val tasksFlow = OpenTaskerApp_NoHilt.db.taskDao().getAllAsFlow()
            .map { entities -> entities.map { it.toDomain() } }

        setContent {
            OpenTaskerTheme {
                val tasks by tasksFlow.collectAsState(initial = emptyList())
                ConfigScreen(tasks = tasks, onTaskSelected = ::onTaskPicked)
            }
        }
    }

    private fun onTaskPicked(task: Task) {
        val prefs = getSharedPreferences(TaskWidgetProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putLong(TaskWidgetProvider.keyTaskId(widgetId), task.id)
            .putString(TaskWidgetProvider.keyTaskName(widgetId), task.name)
            .apply()

        TaskWidgetProvider.updateWidget(
            this,
            AppWidgetManager.getInstance(this),
            widgetId,
        )

        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(tasks: List<Task>, onTaskSelected: (Task) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pick a task") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxSize().padding(padding),
                color = MaterialTheme.colorScheme.background,
            ) {
                Text(
                    "No tasks yet. Create a task in OpenTasker first.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTaskSelected(task) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(task.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${task.actions.size} action${if (task.actions.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
