package com.opentasker.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.opentasker.ui.components.ThemedDropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
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
                    var layout by remember {
                        mutableStateOf(StyledWidgetStore.getLayout(this@StyledWidgetConfigActivity, widgetId) ?: DEFAULT_LAYOUT)
                    }
                    var template by remember {
                        mutableStateOf(StyledWidgetStore.getTemplate(this@StyledWidgetConfigActivity, widgetId) ?: "")
                    }
                    var templateMenu by remember { mutableStateOf(false) }
                    val templateNames = remember { TemplateStore.names() }
                    var tapMenu by remember { mutableStateOf(false) }
                    var taskNames by remember { mutableStateOf(listOf<String>()) }
                    var editing by remember { mutableStateOf(false) }
                    // Load the task list for the picker and pre-fill the bound tap task by name.
                    LaunchedEffect(Unit) {
                        taskNames = runCatching { OpenTaskerApp_NoHilt.db.taskDao().getAll().map { it.name } }.getOrDefault(emptyList())
                        val stored = StyledWidgetStore.getTapTaskName(applicationContext, widgetId)
                        tapTask = stored.ifBlank {
                            val id = StyledWidgetStore.getTapTask(applicationContext, widgetId)
                            if (id > 0) runCatching { OpenTaskerApp_NoHilt.db.taskDao().getById(id)?.name }.getOrNull().orEmpty() else ""
                        }
                    }
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
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { tapMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (tapTask.isBlank()) "Tap task: none" else "Tap task: $tapTask")
                            }
                            ThemedDropdownMenu(expanded = tapMenu, onDismissRequest = { tapMenu = false }) {
                                DropdownMenuItem(text = { Text("None") }, onClick = { tapTask = ""; tapMenu = false })
                                taskNames.forEach { n ->
                                    DropdownMenuItem(text = { Text(n) }, onClick = { tapTask = n; tapMenu = false })
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { templateMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (template.isBlank()) "Content: Custom layout" else "Content: template \"$template\"")
                            }
                            ThemedDropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                                DropdownMenuItem(text = { Text("Custom layout (edit below)") }, onClick = { template = ""; templateMenu = false })
                                templateNames.forEach { n ->
                                    DropdownMenuItem(text = { Text(n) }, onClick = { template = n; templateMenu = false })
                                }
                            }
                        }
                        if (template.isBlank()) {
                            OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Edit layout visually")
                            }
                            OutlinedTextField(
                                value = layout, onValueChange = { layout = it },
                                label = { Text("Layout (JSON, advanced)") },
                                minLines = 4, modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                "This widget renders the \"$template\" template, re-expanded by the Refresh Widgets action (e.g. the per-minute clock tick).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { save(name, template, tapTask, layout) }, modifier = Modifier.fillMaxWidth(0.6f)) {
                                Text("Add widget")
                            }
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                        }
                    }
                }
            }
        }
    }

    private fun save(name: String, template: String, tapTaskName: String, layout: String) {
        val ctx = applicationContext
        StyledWidgetStore.setName(ctx, widgetId, name.trim())
        // A non-blank template binds the slot to the pull model; a blank one falls back to the static layout.
        StyledWidgetStore.setTemplate(ctx, widgetId, template.trim())
        // Keep the layout if it parses; otherwise fall back to the placeholder so the widget still renders.
        val json = if (WidgetLayoutCodec.decode(layout) != null) layout else WidgetLayoutCodec.encode(WidgetLayoutCodec.PLACEHOLDER)
        StyledWidgetStore.setLayout(ctx, widgetId, json)
        lifecycleScope.launch {
            // Bind by NAME (resolved at tap time) so it survives bundle re-imports; clear any legacy id.
            StyledWidgetStore.setTapTaskName(ctx, widgetId, tapTaskName.trim())
            StyledWidgetStore.setTapTask(ctx, widgetId, -1L)
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
