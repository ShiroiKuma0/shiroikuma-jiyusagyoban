package com.opentasker.core.plugins.locale

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.ui.theme.DesignSystem
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.flow.map

class LocaleSettingEditActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = OpenTaskerApp_NoHilt.db
        val tasksFlow = db.taskDao().getAllAsFlow().map { tasks ->
            tasks.map { TaskPickerItem(it.id, it.name) }
        }

        setContent {
            OpenTaskerTheme {
                val tasks by tasksFlow.collectAsState(initial = emptyList())
                Scaffold(
                    topBar = {
                        TopAppBar(title = { Text("Select task for Locale plugin") })
                    },
                ) { padding ->
                    TaskPickerList(
                        tasks = tasks,
                        contentPadding = padding,
                        onSelect = { item ->
                            val resultBundle = LocalePluginTarget.buildResultBundle(item.id, item.name)
                            val resultIntent = Intent().apply {
                                putExtra(LocalePluginContract.EXTRA_BUNDLE, resultBundle)
                                putExtra(
                                    LocalePluginContract.EXTRA_STRING_BLURB,
                                    LocalePluginTarget.buildBlurb(item.name),
                                )
                            }
                            setResult(RESULT_OK, resultIntent)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

private data class TaskPickerItem(val id: Long, val name: String)

@Composable
private fun TaskPickerList(
    tasks: List<TaskPickerItem>,
    contentPadding: PaddingValues,
    onSelect: (TaskPickerItem) -> Unit,
) {
    if (tasks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(DesignSystem.Screen.horizontalPadding),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "No tasks available. Create a task in OpenTasker first.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(DesignSystem.Screen.horizontalPadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.sm),
    ) {
        items(tasks, key = { it.id }) { task ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(task) },
                shape = RoundedCornerShape(DesignSystem.Radii.lg),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(DesignSystem.Screen.horizontalPadding)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Task #${task.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
