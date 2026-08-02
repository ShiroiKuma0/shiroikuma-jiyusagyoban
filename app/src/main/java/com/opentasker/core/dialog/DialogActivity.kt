package com.opentasker.core.dialog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.screens.AppMultiSelectDialog
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore

/**
 * Transparent host for a task-driven dialog (Input / List / Text). It reads the dialog spec from its
 * launch intent, shows a themed dialog, and hands the result back through [DialogBridge] keyed by the
 * request id, then finishes. From a background trigger this needs the "display over other apps"
 * permission (the app already declares SYSTEM_ALERT_WINDOW); run from the app it always works.
 */
class DialogActivity : ComponentActivity() {

    private var requestId: String? = null
    private var settled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        requestId = id
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_TEXT
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val default = intent.getStringExtra(EXTRA_DEFAULT).orEmpty()
        val items = intent.getStringArrayExtra(EXTRA_ITEMS)?.toList() ?: emptyList()
        val inputType = intent.getStringExtra(EXTRA_INPUT_TYPE).orEmpty()
        val okLabel = intent.getStringExtra(EXTRA_OK)?.takeIf { it.isNotBlank() } ?: "OK"
        val cancelLabel = intent.getStringExtra(EXTRA_CANCEL)?.takeIf { it.isNotBlank() } ?: "Cancel"
        // A TEXT dialog opts out of the dismiss button entirely by passing an explicitly blank cancel
        // label; an absent extra (e.g. the permission-block dialog) keeps the default "Cancel".
        val textCancelLabel: String? =
            if (intent.hasExtra(EXTRA_CANCEL) && intent.getStringExtra(EXTRA_CANCEL).isNullOrBlank()) null else cancelLabel
        val preselected = intent.getStringExtra(EXTRA_PRESELECTED).orEmpty()
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // Optional deep-link pills for a warning dialog: parallel arrays of CapabilityRequirement names and
        // their labels; each renders a tap-through pill that opens that permission's System settings page.
        val settingsTargets = (intent.getStringArrayExtra(EXTRA_SETTINGS_REQS)?.toList() ?: emptyList())
            .zip(intent.getStringArrayExtra(EXTRA_SETTINGS_LABELS)?.toList() ?: emptyList())

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                when (type) {
                    TYPE_INPUT -> InputDialog(title, text, default, inputType, okLabel, cancelLabel,
                        onConfirm = { settle(DialogOutcome.Confirmed(it)) },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                    TYPE_LIST -> ListDialog(title, items, cancelLabel,
                        onPick = { index -> settle(DialogOutcome.Confirmed(items[index], index)) },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                    TYPE_APP_MULTISELECT -> AppMultiSelectDialog(title, preselected,
                        includeSelf = intent.getBooleanExtra(EXTRA_INCLUDE_SELF, false),
                        onConfirm = { picked ->
                            // One app per line, "<package>\t<label>".
                            val value = picked.joinToString("\n") { (pkg, label) -> "$pkg\t$label" }
                            settle(DialogOutcome.Confirmed(value))
                        },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                    TYPE_APP_SINGLESELECT -> AppMultiSelectDialog(
                        title,
                        restrictPackages = intent.getStringExtra(EXTRA_PACKAGES).orEmpty()
                            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                        singleSelect = true,
                        onConfirm = { picked ->
                            settle(DialogOutcome.Confirmed(picked.firstOrNull()?.first.orEmpty()))
                        },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                    TYPE_LIST_MULTISELECT -> ListMultiSelectDialog(
                        title, items,
                        labels = intent.getStringArrayExtra(EXTRA_LABELS)?.toList() ?: emptyList(),
                        parents = intent.getStringArrayExtra(EXTRA_PARENTS)?.toList() ?: emptyList(),
                        preselected = preselected, okLabel = okLabel, cancelLabel = cancelLabel,
                        // One value per line, in item order.
                        onConfirm = { picked -> settle(DialogOutcome.Confirmed(picked.joinToString("\n"))) },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                    else -> TextDialog(title, text, okLabel, textCancelLabel, settingsTargets,
                        onOpenSettings = { req -> openSettingsFor(req) },
                        onConfirm = { settle(DialogOutcome.Confirmed("true")) },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                }
            }
        }
    }

    /**
     * Open the System settings page that grants the named [CapabilityRequirement], then settle.
     *
     * Settling here is the fix for the dialog "doing nothing". This Activity is declared with an empty
     * `taskAffinity` and `excludeFromRecents`, so once Settings takes the foreground its task is liable
     * to be destroyed — and [onDestroy] would then report a Cancelled the user never chose, failing the
     * task. Worse, a per-minute profile re-raised the modal on top of Settings a moment later, which is
     * what made the button look broken. So: launch, settle explicitly, start the requirement's quiet
     * window, and get out of the way.
     *
     * A settings page that does not resolve is reported rather than swallowed — silence here was the
     * other half of "nothing happens".
     */
    private fun openSettingsFor(reqName: String) {
        val req = runCatching {
            com.opentasker.core.capabilities.CapabilityRequirement.valueOf(reqName)
        }.getOrNull() ?: return
        val intent = com.opentasker.core.capabilities.CapabilityState.settingsIntent(req, this)
        if (intent == null) {
            toast("No settings page for ${com.opentasker.core.capabilities.CapabilityState.shortLabel(req)}")
            return
        }
        val launched = runCatching { startActivity(intent); true }
            .onFailure { AppLogger.warn(TAG, "Could not open settings for $reqName: ${it.message}") }
            .getOrDefault(false)
        if (!launched) {
            toast("Couldn't open ${com.opentasker.core.capabilities.CapabilityState.shortLabel(req)} settings")
            return
        }
        com.opentasker.core.capabilities.CapabilityPrompt.markSentToSettings(req)
        settle(DialogOutcome.Cancelled)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun settle(outcome: DialogOutcome) {
        if (!settled) {
            settled = true
            requestId?.let { DialogBridge.complete(it, outcome) }
        }
        finish()
    }

    override fun onDestroy() {
        // If the user left without choosing, don't leave the action hanging.
        if (!settled) {
            settled = true
            requestId?.let { DialogBridge.cancel(it) }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DialogActivity"
        const val EXTRA_ID = "id"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DEFAULT = "default"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_LABELS = "labels" // optional display labels parallel to EXTRA_ITEMS
        const val EXTRA_PARENTS = "parents" // optional parent ids parallel to EXTRA_ITEMS ("" = top-level)
        const val EXTRA_PACKAGES = "packages" // newline-joined package restriction for the app pickers
        const val EXTRA_INCLUDE_SELF = "include_self" // keep this app in an unrestricted app grid
        const val EXTRA_PRESELECTED = "preselected"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_OK = "ok"
        const val EXTRA_CANCEL = "cancel"
        const val EXTRA_SETTINGS_REQS = "settings_reqs"     // CapabilityRequirement names → deep-link pills
        const val EXTRA_SETTINGS_LABELS = "settings_labels" // parallel labels for the pills

        const val TYPE_INPUT = "input"
        const val TYPE_LIST = "list"
        const val TYPE_TEXT = "text"
        const val TYPE_APP_MULTISELECT = "app_multiselect"
        const val TYPE_APP_SINGLESELECT = "app_singleselect"
        const val TYPE_LIST_MULTISELECT = "list_multiselect"
    }
}

private val dialogBorderShape = RoundedCornerShape(28.dp)

@Composable
private fun dialogModifier() =
    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, dialogBorderShape)

@Composable
private fun InputDialog(
    title: String,
    text: String,
    default: String,
    inputType: String,
    okLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(default) }
    val isPassword = inputType.equals("password", ignoreCase = true)
    val keyboard = when (inputType.lowercase()) {
        "number", "numeric" -> KeyboardType.Number
        "password" -> KeyboardType.Password
        "email" -> KeyboardType.Email
        else -> KeyboardType.Text
    }
    AlertDialog(
        modifier = dialogModifier(),
        onDismissRequest = onCancel,
        title = { if (title.isNotBlank()) Text(title) },
        text = {
            Column {
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(okLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancelLabel) } },
    )
}

@Composable
private fun ListDialog(
    title: String,
    items: List<String>,
    cancelLabel: String,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = dialogModifier(),
        onDismissRequest = onCancel,
        title = { if (title.isNotBlank()) Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                items.forEachIndexed { index, item ->
                    Text(
                        item,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(index) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(cancelLabel) } },
    )
}

@Composable
private fun ListMultiSelectDialog(
    title: String,
    items: List<String>,
    labels: List<String>,
    parents: List<String>,
    preselected: Set<String>,
    okLabel: String,
    cancelLabel: String,
    onConfirm: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var checked by remember { mutableStateOf(preselected.filter { it in items.toSet() }.toSet()) }
    fun childrenOf(id: String): List<String> =
        items.filterIndexed { i, _ -> parents.getOrNull(i)?.trim() == id }
    AlertDialog(
        modifier = dialogModifier(),
        onDismissRequest = onCancel,
        title = { if (title.isNotBlank()) Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                // Pinned master toggle: one tap checks everything (or clears everything when
                // already complete) — replaces the confusing "leave empty = all" convention.
                val allChecked = checked.size == items.size
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { checked = if (allChecked) emptySet() else items.toSet() }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(checked = allChecked, onCheckedChange = null)
                    Text(
                        "全選択",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                items.forEachIndexed { index, item ->
                    val isChecked = item in checked
                    val isChild = parents.getOrNull(index)?.trim().orEmpty().isNotEmpty()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Toggling a parent carries its sub-options with it.
                                val kids = childrenOf(item)
                                checked = if (isChecked) checked - item - kids.toSet()
                                else checked + item + kids
                            }
                            .padding(vertical = 4.dp, horizontal = if (isChild) 24.dp else 0.dp),
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = null)
                        Text(
                            labels.getOrNull(index)?.takeIf { it.isNotBlank() } ?: item,
                            style = if (isChild) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(items.filter { it in checked }) }) { Text(okLabel) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancelLabel) } },
    )
}

@Composable
private fun TextDialog(
    title: String,
    text: String,
    okLabel: String,
    cancelLabel: String?,
    settingsTargets: List<Pair<String, String>>,
    onOpenSettings: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = dialogModifier(),
        // With no cancel button an outside/back dismissal resolves as OK — the only outcome — so the
        // dialog can never get stuck; with a cancel button it keeps the distinct Cancelled outcome.
        onDismissRequest = if (cancelLabel == null) onConfirm else onCancel,
        title = { if (title.isNotBlank()) Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium)
                // One tap-through pill per missing permission → opens its System settings page.
                settingsTargets.forEach { (reqName, label) -> SettingsPill(label) { onOpenSettings(reqName) } }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(okLabel) } },
        // An explicitly blank cancel label (dialog.text with cancel="") drops the dismiss button —
        // for acknowledgment-only dialogs where there is nothing to cancel.
        dismissButton = cancelLabel?.let { { TextButton(onClick = onCancel) { Text(it) } } },
    )
}

/** A rounded, tap-through pill that opens a System settings page. */
@Composable
private fun SettingsPill(label: String, onClick: () -> Unit) {
    Text(
        "Open $label settings  →",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
