package com.opentasker.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PickableApp(val label: String, val pkg: String)

/**
 * A searchable, multi-select picker over the installed **user** apps — including frozen/disabled ones,
 * so the generated unfreeze-then-launch tasks can target apps that are currently frozen. Apps are shown
 * as a grid of icon tiles (icon + name); a tapped tile highlights with a check badge. The OK button
 * hands back the selected (package, label) pairs.
 */
@Composable
internal fun AppMultiSelectDialog(
    title: String,
    onConfirm: (List<Pair<String, String>>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<PickableApp>?>(null) }
    // package -> label of the currently-checked apps.
    val selected = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val ownPkg = context.packageName
            runCatching {
                val all = if (Build.VERSION.SDK_INT >= 33) {
                    pm.getInstalledApplications(
                        PackageManager.ApplicationInfoFlags.of(
                            (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong()
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(
                        PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES
                    )
                }
                all.asSequence()
                    .filter { info ->
                        // Keep USER apps: non-system, or a user-updated system app.
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    }
                    .filter { it.packageName != ownPkg }
                    .map { PickableApp(pm.getApplicationLabel(it).toString(), it.packageName) }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            }.getOrDefault(emptyList())
        }
    }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(16.dp)) {
                Text(title.ifBlank { "Select apps" }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = list.filter {
                        query.isBlank() ||
                            it.label.contains(query, ignoreCase = true) ||
                            it.pkg.contains(query, ignoreCase = true)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 84.dp),
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(filtered, key = { it.pkg }) { app ->
                            SelectableAppTile(
                                pkg = app.pkg,
                                label = app.label,
                                selected = selected.containsKey(app.pkg),
                                onToggle = {
                                    if (selected.containsKey(app.pkg)) selected.remove(app.pkg)
                                    else selected[app.pkg] = app.label
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        onConfirm(selected.map { (pkg, label) -> pkg to label })
                    }) { Text("OK (${selected.size})") }
                }
            }
        }
    }
}

@Composable
private fun SelectableAppTile(pkg: String, label: String, selected: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, pkg) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(pkg).toBitmap(144, 144).asImageBitmap()
            }.getOrNull()
        }
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)) else Modifier)
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            val bmp = icon
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                )
            }
            if (selected) {
                // A check badge on a solid backdrop so it reads over any icon.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
