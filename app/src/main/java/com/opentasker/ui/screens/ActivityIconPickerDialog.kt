package com.opentasker.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import com.opentasker.app.R
import com.opentasker.core.icons.TaskIconStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lists the distinct activity icons declared by [targetPackage] (frozen apps included) so a different
 * one than the launcher icon can be chosen. Icons are deduped by resource id (most activities reuse the
 * app icon). The chosen drawable is snapshotted via [TaskIconStore.saveFromDrawable].
 */
@Composable
internal fun ActivityIconPickerDialog(targetPackage: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    // Distinct (iconResId -> preview bitmap); loaded off the main thread.
    var icons by remember { mutableStateOf<List<Pair<Int, ImageBitmap>>?>(null) }
    LaunchedEffect(targetPackage) {
        icons = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            val info = runCatching { pm.getPackageInfo(targetPackage, flags) }.getOrNull()
            val seen = HashSet<Int>()
            buildList {
                info?.activities?.forEach { ai ->
                    val resId = if (ai.icon != 0) ai.icon else ai.applicationInfo?.icon ?: 0
                    if (resId != 0 && seen.add(resId)) {
                        val d = runCatching {
                            pm.getActivityIcon(android.content.ComponentName(ai.packageName, ai.name))
                        }.getOrNull()
                        if (d != null) drawableToPreview(d)?.let { add(resId to it) }
                    }
                }
                // Always offer the app's own icon too.
                if (isEmpty()) {
                    runCatching { pm.getApplicationIcon(targetPackage) }.getOrNull()
                        ?.let { d -> drawableToPreview(d)?.let { add(0 to it) } }
                }
            }
        }
    }
    AlertDialog(
        modifier = dialogBorder(),
        onDismissRequest = onDismiss,
        title = { Text("App icons") },
        text = {
            val list = icons
            when {
                list == null -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                list.isEmpty() -> Text("No activity icons found.", style = MaterialTheme.typography.bodyMedium)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(list) { _, (resId, bmp) ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    val pm = context.packageManager
                                    val d = runCatching {
                                        if (resId != 0) pm.getDrawable(targetPackage, resId, null)
                                        else pm.getApplicationIcon(targetPackage)
                                    }.getOrNull() ?: runCatching { pm.getApplicationIcon(targetPackage) }.getOrNull()
                                    val saved = d?.let { TaskIconStore.saveFromDrawable(context, it) }
                                    if (saved != null) onPick(saved)
                                }
                                .padding(6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
