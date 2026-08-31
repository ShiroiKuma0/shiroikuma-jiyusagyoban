package com.opentasker.ui.screens

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.core.icons.TaskIconStore

/**
 * Picks one of a curated set of stable Android framework drawables (`android.R.drawable`). The full
 * framework set is huge and partly private/unstable across OEMs, so only these hand-picked generic
 * symbols are offered. Previews render in memory; the chosen drawable is snapshotted to a PNG via
 * [TaskIconStore.saveFromDrawable] only on selection.
 */
@Composable
internal fun FrameworkIconPickerDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        modifier = dialogBorder(),
        onDismissRequest = onDismiss,
        title = { Text("System icon") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(FRAMEWORK_ICONS) { resId ->
                    val preview = remember(resId) {
                        ContextCompat.getDrawable(context, resId)?.let { drawableToPreview(it) }
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
                                    val saved = ContextCompat.getDrawable(context, resId)
                                        ?.let { TaskIconStore.saveFromDrawable(context, it) }
                                    if (saved != null) onPick(saved)
                                }
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** A curated, stable subset of android.R.drawable — generic symbols safe across OEM builds. */
private val FRAMEWORK_ICONS: List<Int> = listOf(
    android.R.drawable.ic_menu_share, android.R.drawable.ic_menu_send, android.R.drawable.ic_menu_edit,
    android.R.drawable.ic_menu_save, android.R.drawable.ic_menu_camera, android.R.drawable.ic_menu_gallery,
    android.R.drawable.ic_menu_call, android.R.drawable.ic_menu_directions, android.R.drawable.ic_menu_search,
    android.R.drawable.ic_menu_add, android.R.drawable.ic_menu_delete, android.R.drawable.ic_menu_info_details,
    android.R.drawable.ic_menu_manage, android.R.drawable.ic_menu_preferences, android.R.drawable.ic_menu_upload,
    android.R.drawable.ic_menu_view, android.R.drawable.ic_menu_agenda, android.R.drawable.ic_menu_today,
    android.R.drawable.ic_menu_month, android.R.drawable.ic_menu_my_calendar, android.R.drawable.ic_menu_mapmode,
    android.R.drawable.ic_menu_mylocation, android.R.drawable.ic_menu_compass, android.R.drawable.ic_menu_slideshow,
    android.R.drawable.ic_menu_sort_by_size, android.R.drawable.ic_menu_crop, android.R.drawable.ic_menu_rotate,
    android.R.drawable.ic_menu_set_as, android.R.drawable.ic_menu_help, android.R.drawable.ic_menu_more,
    android.R.drawable.star_big_on, android.R.drawable.star_big_off, android.R.drawable.btn_star_big_on,
    android.R.drawable.ic_dialog_email, android.R.drawable.ic_dialog_info, android.R.drawable.ic_dialog_alert,
    android.R.drawable.ic_dialog_map, android.R.drawable.ic_dialog_dialer, android.R.drawable.ic_input_add,
    android.R.drawable.ic_lock_idle_alarm, android.R.drawable.ic_lock_lock, android.R.drawable.ic_popup_reminder,
    android.R.drawable.ic_popup_sync, android.R.drawable.sym_action_call, android.R.drawable.sym_action_chat,
    android.R.drawable.sym_action_email, android.R.drawable.stat_sys_download, android.R.drawable.stat_notify_chat,
    android.R.drawable.presence_online, android.R.drawable.presence_busy, android.R.drawable.presence_away,
)
