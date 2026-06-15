package com.opentasker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.widget.WidgetLayoutCodec
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.launch

/** Configures a freshly-placed styled widget: a name (for Set Widget to target), an initial layout, and an optional tap task. */
class StyledWidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish(); return
        }

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            com.opentasker.ui.theme.OpenTaskerTheme(prefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var name by remember { mutableStateOf(StyledWidgetStore.getName(this@StyledWidgetConfigActivity, widgetId)) }
                    var tapTask by remember { mutableStateOf("") }
                    var layout by remember { mutableStateOf(DEFAULT_LAYOUT) }
                    var editing by remember { mutableStateOf(false) }
                    if (editing) {
                        WidgetEditor(
                            initialJson = layout,
                            onDone = { layout = it; editing = false },
                            onCancel = { editing = false },
                        )
                        return@Surface
                    }
                    Column(
                        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Configure widget", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Widget name") },
                            supportingText = { Text("Tasks target this with the Set Widget action.") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = tapTask, onValueChange = { tapTask = it },
                            label = { Text("Tap task (optional)") },
                            supportingText = { Text("Exact task name to run when the widget is tapped.") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Edit layout visually")
                        }
                        OutlinedTextField(
                            value = layout, onValueChange = { layout = it },
                            label = { Text("Layout (JSON, advanced)") },
                            minLines = 4, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { save(name, tapTask, layout) }, modifier = Modifier.fillMaxWidth(0.6f)) {
                                Text("Add widget")
                            }
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }

    private fun save(name: String, tapTaskName: String, layout: String) {
        val ctx = applicationContext
        StyledWidgetStore.setName(ctx, widgetId, name.trim())
        // Keep the layout if it parses; otherwise fall back to the placeholder so the widget still renders.
        val json = if (WidgetLayoutCodec.decode(layout) != null) layout else WidgetLayoutCodec.encode(WidgetLayoutCodec.PLACEHOLDER)
        StyledWidgetStore.setLayout(ctx, widgetId, json)
        lifecycleScope.launch {
            val taskId = tapTaskName.trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { OpenTaskerApp_NoHilt.db.taskDao().getByName(it)?.id }.getOrNull() } ?: -1L
            StyledWidgetStore.setTapTask(ctx, widgetId, taskId)
            StyledWidgetProvider.renderWidget(ctx, AppWidgetManager.getInstance(ctx), widgetId)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }
    }

    companion object {
        private val DEFAULT_LAYOUT = """
            {
              "type": "box",
              "width": "fill", "height": "fill",
              "background": { "color": "#000000", "corner": 16 },
              "children": [
                { "type": "text", "text": "白い熊", "color": "#FFFF00", "size": 28, "align": "center" }
              ]
            }
        """.trimIndent()
    }
}
