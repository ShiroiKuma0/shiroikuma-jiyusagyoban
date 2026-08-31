package com.opentasker.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
 * the system share sheet that unfreezes the app and forwards the shared content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relays by ShareRelayStore.relays.collectAsState()
    var copyStreams by remember { mutableStateOf(ShareRelayStore.copyStreams) }
    var showPicker by remember { mutableStateOf(false) }
    var iconEditFor by remember { mutableStateOf<ShareRelayEntry?>(null) }
    val busy = remember { mutableStateListOf<String>() }
    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
                    "Each app here gets its own tile in the system share sheet. Sharing to it unfreezes " +
                        "the app (Shizuku), forwards the content, and drops a re-freeze bubble.",
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
                                "Shizuku is needed to install and remove relays.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { ShizukuShell.requestPermission() }) { Text("Grant") }
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
                                    is RelayGenerator.Outcome.Installed -> "${entry.label}: installed"
                                    is RelayGenerator.Outcome.ShizukuUnavailable -> "Shizuku unavailable"
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
                            else if (outcome is RelayGenerator.Outcome.ShizukuUnavailable) toast("Shizuku unavailable")
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
                    Text(if (relays.size < ShareRelayStore.MAX_TARGETS) "Add apps" else "Maximum ${ShareRelayStore.MAX_TARGETS} reached")
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Copy files when forwarding", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Route shared files through this app's storage — for receivers that reject a passed-on grant.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = copyStreams, onCheckedChange = { copyStreams = it; ShareRelayStore.copyStreams = it })
                }
            }
        }
    }

    if (showPicker) {
        AppMultiSelectDialog(
            title = "Add share targets",
            preselected = relays.map { it.targetPackage }.toSet(),
            onConfirm = { picked ->
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

    iconEditFor?.let { entry ->
        TaskIconPickerDialog(
            initialIconPath = entry.iconPath,
            targetPackage = entry.targetPackage,
            title = "Icon — ${entry.label}",
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
                    if (bmp != null) Image(bmp.asImageBitmap(), "Edit icon", Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                    else Text("?", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = entry.label,
                    onValueChange = onLabelChange,
                    label = { Text("Tile name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.targetPackage,
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
                    else Text(if (entry.state == RelayState.GENERATED) "Reinstall" else if (entry.state == RelayState.STALE) "Regenerate" else "Generate")
                }
                OutlinedButton(onClick = onRemove, enabled = !busy, shape = RoundedCornerShape(10.dp)) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun StateBadge(state: RelayState) {
    val (text, color) = when (state) {
        RelayState.DRAFT -> "not generated" to MaterialTheme.colorScheme.onSurfaceVariant
        RelayState.GENERATED -> "✓ installed" to MaterialTheme.colorScheme.tertiary
        RelayState.STALE -> "needs regenerate" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}
