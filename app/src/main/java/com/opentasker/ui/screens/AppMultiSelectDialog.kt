package com.opentasker.ui.screens

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PickableApp(val label: String, val pkg: String)

/**
 * A searchable, multi-select picker over the installed **user** apps — including frozen/disabled ones,
 * so the generated unfreeze-then-launch tasks can target apps that are currently frozen. Each row is a
 * checkbox + label + package; the OK button hands back the selected (package, label) pairs.
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
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
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
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.pkg }) { app ->
                            val checked = selected.containsKey(app.pkg)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selected.remove(app.pkg) else selected[app.pkg] = app.label
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        if (it) selected[app.pkg] = app.label else selected.remove(app.pkg)
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        app.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        app.pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
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
