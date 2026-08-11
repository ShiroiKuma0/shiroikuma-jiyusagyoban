package com.opentasker.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.ContextSpec
import com.opentasker.core.model.ContextType
import com.opentasker.core.model.Profile
import com.opentasker.core.model.RunLogEntry
import com.opentasker.core.model.Task
import com.opentasker.ui.theme.OpenTaskerTheme

private enum class PreviewTheme {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
    HIGH_CONTRAST,
}

private val PreviewPadding = PaddingValues()

private val PreviewTask = Task(
    id = 1L,
    name = "Morning briefing",
    actions = listOf(ActionSpec(type = "notification.show", label = "Briefing")),
)

private val PreviewProfile = Profile(
    id = 1L,
    name = "Workday",
    enterTaskId = PreviewTask.id,
    contexts = listOf(ContextSpec(type = ContextType.TIME)),
    group = "Routine",
    cooldownSec = 30,
    priority = 2,
)

private val PreviewRunLog = RunLogEntry(
    id = 1L,
    taskId = PreviewTask.id,
    taskName = PreviewTask.name,
    durationMs = 842L,
    success = true,
    message = "Completed successfully",
)

@Composable
private fun PreviewFrame(
    theme: PreviewTheme,
    title: String,
    content: @Composable () -> Unit,
) {
    val frameContent: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                PreviewHeader(title)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    content()
                }
            }
        }
    }
    when (theme) {
        PreviewTheme.SYSTEM -> OpenTaskerTheme(content = frameContent)
        PreviewTheme.LIGHT -> OpenTaskerTheme(darkTheme = false, content = frameContent)
        PreviewTheme.DARK -> OpenTaskerTheme(darkTheme = true, content = frameContent)
        PreviewTheme.AMOLED -> OpenTaskerTheme(darkTheme = true, amoled = true, content = frameContent)
        PreviewTheme.HIGH_CONTRAST -> OpenTaskerTheme(
            darkTheme = true,
            highContrast = true,
            content = frameContent,
        )
    }
}

@Composable
private fun PreviewHeader(title: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("OpenTasker", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            StatusPill("Ready", MaterialTheme.colorScheme.tertiary)
        }
    }
}

@PreviewTest
@Preview(name = "Profiles · Light · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Profiles · Light · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ProfilesLightPreview() {
    PreviewFrame(PreviewTheme.LIGHT, "Profiles") {
        ProfilesScreen(
            profiles = listOf(PreviewProfile),
            tasks = listOf(PreviewTask),
            runLogs = listOf(PreviewRunLog),
            storageDecodeIssues = emptyList(),
            onCreateTaskFirst = {},
            onCreateProfile = {},
            onBrowseTemplates = {},
            onPreviewProfileShare = {},
            onPreflightProfile = {},
            onExportOpenTaskerBundle = {},
            onImportOpenTaskerBundle = {},
            onImportOpenTaskerBundleText = {},
            openTaskerBundleBusy = false,
            onImportTaskerXml = {},
            onExportTaskerXml = {},
            taskerImportBusy = false,
            onEditProfile = {},
            onDeleteProfile = {},
            onToggleProfile = { _, _ -> },
            onAddContext = {},
            onEditContextLogic = {},
            onEditContext = { _, _, _ -> },
            onDeleteContext = { _, _ -> },
            contentPadding = PreviewPadding,
        )
    }
}

@PreviewTest
@Preview(name = "Profiles · Dark · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Profiles · Dark · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ProfilesDarkPreview() {
    ProfilesLightPreviewContent(PreviewTheme.DARK)
}

@PreviewTest
@Preview(name = "Profiles · System · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Profiles · System · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ProfilesSystemPreview() {
    ProfilesLightPreviewContent(PreviewTheme.SYSTEM)
}

@PreviewTest
@Preview(name = "Profiles · AMOLED · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Profiles · AMOLED · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ProfilesAmoledPreview() {
    ProfilesLightPreviewContent(PreviewTheme.AMOLED)
}

@PreviewTest
@Preview(name = "Profiles · High contrast · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Profiles · High contrast · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ProfilesHighContrastPreview() {
    ProfilesLightPreviewContent(PreviewTheme.HIGH_CONTRAST)
}

@Composable
private fun ProfilesLightPreviewContent(theme: PreviewTheme) {
    PreviewFrame(theme, "Profiles") {
        ProfilesScreen(
            profiles = listOf(PreviewProfile),
            tasks = listOf(PreviewTask),
            runLogs = listOf(PreviewRunLog),
            storageDecodeIssues = emptyList(),
            onCreateTaskFirst = {},
            onCreateProfile = {},
            onBrowseTemplates = {},
            onPreviewProfileShare = {},
            onPreflightProfile = {},
            onExportOpenTaskerBundle = {},
            onImportOpenTaskerBundle = {},
            onImportOpenTaskerBundleText = {},
            openTaskerBundleBusy = false,
            onImportTaskerXml = {},
            onExportTaskerXml = {},
            taskerImportBusy = false,
            onEditProfile = {},
            onDeleteProfile = {},
            onToggleProfile = { _, _ -> },
            onAddContext = {},
            onEditContextLogic = {},
            onEditContext = { _, _, _ -> },
            onDeleteContext = { _, _ -> },
            contentPadding = PreviewPadding,
        )
    }
}

@PreviewTest
@Preview(name = "Tasks · Light · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Tasks · Light · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun TasksLightPreview() {
    TasksPreviewContent(PreviewTheme.LIGHT)
}

@PreviewTest
@Preview(name = "Tasks · Dark · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Tasks · Dark · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun TasksDarkPreview() {
    TasksPreviewContent(PreviewTheme.DARK)
}

@PreviewTest
@Preview(name = "Tasks · System · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Tasks · System · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun TasksSystemPreview() {
    TasksPreviewContent(PreviewTheme.SYSTEM)
}

@PreviewTest
@Preview(name = "Tasks · High contrast · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Tasks · High contrast · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun TasksHighContrastPreview() {
    TasksPreviewContent(PreviewTheme.HIGH_CONTRAST)
}

@Composable
private fun TasksPreviewContent(theme: PreviewTheme) {
    PreviewFrame(theme, "Tasks") {
        TasksScreen(
            tasks = listOf(PreviewTask),
            storageDecodeIssues = emptyList(),
            onCreateTask = {},
            onEditTask = {},
            onDeleteTask = {},
            onRunTask = {},
            onPreflightTask = {},
            onPinTask = {},
            onAddAction = {},
            onEditAction = { _, _, _ -> },
            onDeleteAction = { _, _ -> },
            onMoveAction = { _, _, _ -> },
            contentPadding = PreviewPadding,
        )
    }
}

@PreviewTest
@Preview(name = "Diagnostics · Light · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Diagnostics · Light · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun DiagnosticsLightPreview() {
    DiagnosticsPreviewContent(PreviewTheme.LIGHT)
}

@PreviewTest
@Preview(name = "Diagnostics · Dark · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Diagnostics · Dark · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun DiagnosticsDarkPreview() {
    DiagnosticsPreviewContent(PreviewTheme.DARK)
}

@PreviewTest
@Preview(name = "Diagnostics · System · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Diagnostics · System · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun DiagnosticsSystemPreview() {
    DiagnosticsPreviewContent(PreviewTheme.SYSTEM)
}

@PreviewTest
@Preview(name = "Diagnostics · High contrast · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Diagnostics · High contrast · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun DiagnosticsHighContrastPreview() {
    DiagnosticsPreviewContent(PreviewTheme.HIGH_CONTRAST)
}

@Composable
private fun DiagnosticsPreviewContent(theme: PreviewTheme) {
    PreviewFrame(theme, "Diagnostics") {
        DiagnosticsScreen(
            state = DiagnosticsUiState(),
            contentPadding = PreviewPadding,
            onRefresh = {},
            onShare = {},
        )
    }
}

@PreviewTest
@Preview(name = "Setup · Light · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Setup · Light · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun SetupLightPreview() {
    SetupPreviewContent(PreviewTheme.LIGHT)
}

@PreviewTest
@Preview(name = "Setup · Dark · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Setup · Dark · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun SetupDarkPreview() {
    SetupPreviewContent(PreviewTheme.DARK)
}

@PreviewTest
@Preview(name = "Setup · System · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Setup · System · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun SetupSystemPreview() {
    SetupPreviewContent(PreviewTheme.SYSTEM)
}

@PreviewTest
@Preview(name = "Setup · High contrast · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Setup · High contrast · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun SetupHighContrastPreview() {
    SetupPreviewContent(PreviewTheme.HIGH_CONTRAST)
}

@Composable
private fun SetupPreviewContent(theme: PreviewTheme) {
    PreviewFrame(theme, "Setup") {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SetupSummaryCard()
            }
            item {
                SetupOptionCard("Automation service", "Ready to monitor your contexts", true)
            }
            item {
                SetupOptionCard("Notifications", "Allow run results and health alerts", false)
            }
            item {
                SetupOptionCard("Battery optimization", "Keep automation reliable in the background", false)
            }
        }
    }
}

@Composable
private fun SetupSummaryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.70f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Setup checklist", style = MaterialTheme.typography.titleLarge)
                    Text("Make the automation engine dependable before you leave it running.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatusPill("2 of 4 ready", MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SetupOptionCard(title: String, body: String, ready: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill(if (ready) "Ready" else "Needs setup", if (ready) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary)
        }
    }
}

@PreviewTest
@Preview(name = "Components · Light · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Components · Light · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ComponentsLightPreview() {
    ComponentsPreviewContent(PreviewTheme.LIGHT)
}

@PreviewTest
@Preview(name = "Components · Dark · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Components · Dark · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ComponentsDarkPreview() {
    ComponentsPreviewContent(PreviewTheme.DARK)
}

@PreviewTest
@Preview(name = "Components · System · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Components · System · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ComponentsSystemPreview() {
    ComponentsPreviewContent(PreviewTheme.SYSTEM)
}

@PreviewTest
@Preview(name = "Components · High contrast · 1.0x", widthDp = 411, heightDp = 891, fontScale = 1.0f, showBackground = true)
@Preview(name = "Components · High contrast · 2.0x", widthDp = 411, heightDp = 891, fontScale = 2.0f, showBackground = true)
@Composable
fun ComponentsHighContrastPreview() {
    ComponentsPreviewContent(PreviewTheme.HIGH_CONTRAST)
}

@Composable
private fun ComponentsPreviewContent(theme: PreviewTheme) {
    PreviewFrame(theme, "Shared states") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Status and feedback", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusPill("Enabled", MaterialTheme.colorScheme.tertiary)
                StatusPill("Paused", MaterialTheme.colorScheme.secondary)
                StatusPill("Attention", MaterialTheme.colorScheme.error)
            }
            InlineNotice(
                title = "No matching profiles",
                body = "Try another search or clear the active filter.",
                color = MaterialTheme.colorScheme.primary,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Empty state", style = MaterialTheme.typography.titleMedium)
                    Text("A calm explanation and one clear next action keep a blank workspace useful.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = {}) { Text("Create one") }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "Empty state · RTL", widthDp = 411, heightDp = 891, fontScale = 1.0f, locale = "ar-XB", showBackground = true)
@Composable
fun EmptyStateRtlPreview() {
    PreviewFrame(PreviewTheme.LIGHT, "Empty workspace") {
        EmptyState(
            title = "No automations yet",
            body = "Create an automation or browse a template to get started.",
            actionLabel = "Browse templates",
            onAction = {},
            contentPadding = PreviewPadding,
        )
    }
}
