package com.opentasker.core.dialog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
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
                    TYPE_APP_MULTISELECT -> AppMultiSelectDialog(title,
                        onConfirm = { picked ->
                            // One app per line, "<package>\t<label>".
                            val value = picked.joinToString("\n") { (pkg, label) -> "$pkg\t$label" }
                            settle(DialogOutcome.Confirmed(value))
                        },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                    else -> TextDialog(title, text, okLabel, cancelLabel,
                        onConfirm = { settle(DialogOutcome.Confirmed("true")) },
                        onCancel = { settle(DialogOutcome.Cancelled) })
                }
            }
        }
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
        const val EXTRA_ID = "id"
        const val EXTRA_TYPE = "type"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_DEFAULT = "default"
        const val EXTRA_ITEMS = "items"
        const val EXTRA_INPUT_TYPE = "input_type"
        const val EXTRA_OK = "ok"
        const val EXTRA_CANCEL = "cancel"

        const val TYPE_INPUT = "input"
        const val TYPE_LIST = "list"
        const val TYPE_TEXT = "text"
        const val TYPE_APP_MULTISELECT = "app_multiselect"
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
private fun TextDialog(
    title: String,
    text: String,
    okLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = dialogModifier(),
        onDismissRequest = onCancel,
        title = { if (title.isNotBlank()) Text(title) },
        text = { if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(okLabel) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(cancelLabel) } },
    )
}
