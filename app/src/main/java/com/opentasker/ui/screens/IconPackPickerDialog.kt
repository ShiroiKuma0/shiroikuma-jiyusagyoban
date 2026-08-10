package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.opentasker.app.R
import com.opentasker.core.icons.TaskIconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** One installed icon-pack app. */
internal data class IconPack(val pkg: String, val label: String)

/** Detection + drawable enumeration for the de-facto icon-pack contract. */
internal object IconPackRepository {
    private val PACK_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
    )

    fun installedPacks(context: Context): List<IconPack> {
        val pm = context.packageManager
        val seen = LinkedHashSet<String>()
        for (action in PACK_ACTIONS) {
            runCatching { pm.queryIntentActivities(Intent(action), 0) }.getOrNull()?.forEach {
                it.activityInfo?.packageName?.let { p -> seen.add(p) }
            }
        }
        return seen.mapNotNull { pkg ->
            runCatching {
                IconPack(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** The drawable names a pack exposes (from appfilter.xml / drawable.xml), deduped, order-preserved. */
    fun drawableNames(context: Context, pkg: String, cap: Int = 3000): List<String> {
        val res = runCatching { context.packageManager.getResourcesForApplication(pkg) }.getOrNull() ?: return emptyList()
        val names = LinkedHashSet<String>()
        for (file in listOf("drawable.xml", "appfilter.xml", "icon_pack.xml")) {
            parseInto(res, pkg, file, names, cap)
            if (names.size >= cap) break
        }
        return names.toList()
    }

    private fun parseInto(res: Resources, pkg: String, file: String, out: LinkedHashSet<String>, cap: Int) {
        val parser: XmlPullParser = runCatching {
            res.assets.open(file).let { input ->
                android.util.Xml.newPullParser().apply { setInput(input, "UTF-8") }
            }
        }.getOrNull() ?: runCatching {
            val id = res.getIdentifier(file.removeSuffix(".xml"), "xml", pkg)
            if (id != 0) res.getXml(id) else null
        }.getOrNull() ?: return
        runCatching {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && out.size < cap) {
                if (event == XmlPullParser.START_TAG && (parser.name == "item" || parser.name == "icon")) {
                    val drawable = parser.getAttributeValue(null, "drawable")
                    if (!drawable.isNullOrBlank()) out.add(drawable.trim())
                }
                event = parser.next()
            }
        }
    }

    fun loadDrawable(context: Context, pkg: String, name: String): android.graphics.drawable.Drawable? = runCatching {
        val res = context.packageManager.getResourcesForApplication(pkg)
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) null else ResourcesCompat.getDrawable(res, id, null)
    }.getOrNull()
}

/**
 * Two-phase icon-pack browser: pick an installed pack, then pick one of its (possibly thousands of)
 * icons from a lazy, searchable grid. Previews render in memory; the chosen icon is snapshotted via
 * [TaskIconStore.saveFromDrawable] only on selection.
 */
@Composable
internal fun IconPackPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var packs by remember { mutableStateOf<List<IconPack>?>(null) }
    var chosen by remember { mutableStateOf<IconPack?>(null) }
    LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { IconPackRepository.installedPacks(context) } }

    val current = chosen
    if (current == null) {
        AlertDialog(
            modifier = dialogBorder(),
            onDismissRequest = onDismiss,
            title = { Text("Icon pack") },
            text = {
                val list = packs
                when {
                    list == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    list.isEmpty() -> Text("No icon packs installed.", style = MaterialTheme.typography.bodyMedium)
                    else -> Column(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        list.forEach { pack ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { chosen = pack }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            ) {
                                val icon = remember(pack.pkg) {
                                    runCatching { context.packageManager.getApplicationIcon(pack.pkg) }.getOrNull()
                                        ?.let { drawableToPreview(it) }
                                }
                                if (icon != null) Image(icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)))
                                Text(pack.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    } else {
        IconPackGrid(pack = current, onBack = { chosen = null }, onDismiss = onDismiss, onPick = onPick)
    }
}

@Composable
private fun IconPackGrid(pack: IconPack, onBack: () -> Unit, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var names by remember { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(pack.pkg) { names = withContext(Dispatchers.IO) { IconPackRepository.drawableNames(context, pack.pkg) } }
    val filtered = remember(names, query) {
        val all = names ?: emptyList()
        if (query.isBlank()) all else all.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        modifier = dialogBorder(),
        onDismissRequest = onDismiss,
        title = { Text(pack.label, maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search icons") }, singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    names == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                    filtered.isEmpty() -> Text("No icons.", style = MaterialTheme.typography.bodyMedium)
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(56.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filtered, key = { it }) { name ->
                            val preview = remember(name) {
                                IconPackRepository.loadDrawable(context, pack.pkg, name)?.let { drawableToPreview(it) }
                            }
                            if (preview != null) {
                                Image(
                                    bitmap = preview,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            val saved = IconPackRepository.loadDrawable(context, pack.pkg, name)
                                                ?.let { TaskIconStore.saveFromDrawable(context, it) }
                                            if (saved != null) onPick(saved)
                                        }
                                        .padding(6.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onBack) { Text("Packs") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
