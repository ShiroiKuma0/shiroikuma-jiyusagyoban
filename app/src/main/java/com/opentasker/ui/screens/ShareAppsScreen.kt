package com.opentasker.ui.screens

import com.opentasker.i18n.tr
import com.opentasker.ui.theme.ThemedSwitch
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.opentasker.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.opentasker.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.icons.TaskIconStore
import com.opentasker.core.share.RelayState
import com.opentasker.core.share.ShareRelayEntry
import com.opentasker.core.share.ShareRelayStore
import com.opentasker.core.share.relay.RelayGenerator
import com.opentasker.core.shizuku.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Share apps" screen: manage per-target share relays. Add an app (frozen-inclusive picker),
 * edit its share-tile name + icon, Generate/Regenerate its relay APK (built, signed, and installed
 * on-device via Shizuku), and Remove it (uninstalls the relay). Each relay becomes its own tile in
 * the system share sheet that unfreezes the app and forwards the shared content. A TASK can be added
 * as a target as well ("Add a task"): its tile starts the task with the shared files as variables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relays by ShareRelayStore.relays.collectAsState()
    var copyStreams by remember { mutableStateOf(ShareRelayStore.copyStreams) }
    var showPicker by remember { mutableStateOf(false) }
    var showTaskPicker by remember { mutableStateOf(false) }
    var iconEditFor by remember { mutableStateOf<ShareRelayEntry?>(null) }
    val busy = remember { mutableStateListOf<String>() }
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Share tiles")) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    tr("Each app here gets its own tile in the system share sheet. Sharing to it unfreezes the app (Shizuku), forwards the content, and drops a re-freeze bubble."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Text(
                    tr("A task can have a tile too. Sharing to it starts the task with the shared files in %share_paths (real paths, each quoted for a shell) and their number in %share_count. A file in shared storage is passed in place; anything else is copied for the run and the copy is deleted when the task ends."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!ShizukuShell.available()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                tr("Shizuku is needed to create and delete tiles."),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { ShizukuShell.requestPermission() }) { Text(tr("Grant")) }
                        }
                    }
                }
            }

            items(relays, key = { it.targetPackage }) { entry ->
                RelayRow(
                    entry = entry,
                    busy = entry.targetPackage in busy,
                    onLabelChange = { ShareRelayStore.setLabel(entry.targetPackage, it) },
                    onEditIcon = { iconEditFor = entry },
                    onGenerate = {
                        busy.add(entry.targetPackage)
                        scope.launch {
                            val outcome = RelayGenerator.generate(context, entry)
                            busy.remove(entry.targetPackage)
                            toast(
                                when (outcome) {
                                    is RelayGenerator.Outcome.Installed -> tr("%1\$s: tile in place", entry.label)
                                    is RelayGenerator.Outcome.ShizukuUnavailable -> tr("Shizuku unavailable")
                                    is RelayGenerator.Outcome.Failed -> "${entry.label}: ${outcome.message}"
                                },
                            )
                        }
                    },
                    onRemove = {
                        busy.add(entry.targetPackage)
                        scope.launch {
                            val outcome = RelayGenerator.remove(context, entry)
                            busy.remove(entry.targetPackage)
                            if (outcome is RelayGenerator.Outcome.Failed) toast("${entry.label}: ${outcome.message}")
                            else if (outcome is RelayGenerator.Outcome.ShizukuUnavailable) toast(tr("Shizuku unavailable"))
                        }
                    },
                )
            }

            item {
                Button(
                    onClick = { showPicker = true },
                    enabled = relays.size < ShareRelayStore.MAX_TARGETS,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (relays.size < ShareRelayStore.MAX_TARGETS) tr("Add apps") else tr("Maximum %1\$s reached", ShareRelayStore.MAX_TARGETS))
                }
            }

            item {
                OutlinedButton(
                    onClick = { showTaskPicker = true },
                    enabled = relays.size < ShareRelayStore.MAX_TARGETS,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text(tr("Add a task")) }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Copy files when forwarding"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("Route shared files through this app's storage — for receivers that reject a passed-on grant."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ThemedSwitch(checked = copyStreams, onCheckedChange = { copyStreams = it; ShareRelayStore.copyStreams = it })
                }
            }
        }
    }

    if (showPicker) {
        AppMultiSelectDialog(
            title = tr("Add share targets"),
            preselected = relays.map { it.targetPackage }.toSet(),
            onConfirm = { picked, _ ->
                showPicker = false
                scope.launch {
                    withContext(Dispatchers.IO) {
                        picked.forEach { (pkg, label) ->
                            if (ShareRelayStore.find(pkg) == null) {
                                ShareRelayStore.addDraft(pkg, label, TaskIconStore.saveFromApp(pkg))
                            }
                        }
                    }
                }
            },
            onCancel = { showPicker = false },
        )
    }

    if (showTaskPicker) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showTaskPicker = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f)
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                com.opentasker.widget.TaskPickerScreen(
                    onPick = { task ->
                        showTaskPicker = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val key = ShareRelayStore.taskKey(task.name)
                                if (ShareRelayStore.find(key) == null) {
                                    // Its own picture file: removing the tile deletes it.
                                    val glyph = task.name.trim().take(1).ifEmpty { "?" }
                                    ShareRelayStore.addDraft(key, task.name, TaskIconStore.saveFromText(context, glyph))
                                }
                            }
                        }
                    },
                    onCancel = { showTaskPicker = false },
                )
            }
        }
    }

    iconEditFor?.let { entry ->
        TaskIconPickerDialog(
            initialIconPath = entry.iconPath,
            targetPackage = entry.targetPackage.takeIf { entry.taskName == null },
            title = tr("Icon — %1\$s", entry.label),
            onDismiss = { iconEditFor = null },
            onConfirm = { path ->
                ShareRelayStore.setIcon(entry.targetPackage, path)
                iconEditFor = null
            },
        )
    }
}

@Composable
private fun RelayRow(
    entry: ShareRelayEntry,
    busy: Boolean,
    onLabelChange: (String) -> Unit,
    onEditIcon: () -> Unit,
    onGenerate: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val bmp = remember(entry.iconPath) { TaskIconStore.loadBitmap(entry.iconPath) }
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface).clickable { onEditIcon() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) Image(bmp.asImageBitmap(), tr("Edit icon"), Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                    else Text("?", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = entry.label,
                    onValueChange = onLabelChange,
                    label = { Text(tr("Tile name")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.taskName?.let { tr("Task: %1\$s", it) } ?: entry.targetPackage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StateBadge(entry.state)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onGenerate,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(if (entry.state == RelayState.GENERATED) tr("Recreate tile") else if (entry.state == RelayState.STALE) tr("Update tile") else tr("Create tile"))
                }
                OutlinedButton(onClick = onRemove, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text(tr("Delete tile")) }
            }
        }
    }
}

@Composable
private fun StateBadge(state: RelayState) {
    val (text, color) = when (state) {
        RelayState.DRAFT -> tr("no tile yet") to MaterialTheme.colorScheme.onSurfaceVariant
        RelayState.GENERATED -> tr("✓ tile in place") to MaterialTheme.colorScheme.tertiary
        RelayState.STALE -> tr("tile out of date") to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}
