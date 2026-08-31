package com.opentasker.core.dialog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
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
                        markup = intent.getBooleanExtra(EXTRA_MARKUP, false),
                        size = dialogSizeOf(intent.getStringExtra(EXTRA_SIZE).orEmpty()),
                        textScale = intent.getFloatExtra(EXTRA_TEXT_SCALE, 1f).coerceIn(0.5f, 3f),
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
        const val EXTRA_MARKUP = "markup" // TEXT: read the body as the lightweight markup below
        const val EXTRA_SIZE = "size"     // TEXT: "normal" (default) / "large" / "full"
        const val EXTRA_TEXT_SCALE = "text_scale" // TEXT: font multiplier, 1 = the theme's own sizes

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
            // Pills, not plain rows (白い熊, 2026-08-22).
            //
            // A tappable choice that looks like a paragraph reads as a paragraph: the target is
            // invisible until you touch it, and on a confirmation dialog the whole point is that the
            // two options look like things you press. Every other control in this app that takes a
            // choice is an outlined chip — the project filter, the 日本語／英語 switch, the band
            // ladders — so a list dialog rendering flat text is the odd one out rather than the norm.
            Column(
                Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items.forEachIndexed { index, item ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                RoundedCornerShape(22.dp),
                            )
                            .clickable { onPick(index) }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                    ) {
                        Text(
                            item,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
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
    markup: Boolean,
    size: DialogSize,
    textScale: Float,
    onOpenSettings: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        modifier = dialogModifier().then(size.surfaceModifier()),
        // With no cancel button an outside/back dismissal resolves as OK — the only outcome — so the
        // dialog can never get stuck; with a cancel button it keeps the distinct Cancelled outcome.
        onDismissRequest = if (cancelLabel == null) onConfirm else onCancel,
        // headlineSmall is AlertDialog's own default for this slot, restated so the scale can reach it.
        title = {
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.headlineSmall.scaledBy(textScale))
            }
        },
        text = {
            // A long body scrolls instead of being clipped — the reason a reference sheet can be shown
            // here at all. The scroll container is height-bounded by the dialog itself (the text slot
            // is weighted), so this changes nothing for a body that already fits.
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (text.isNotBlank()) {
                    if (markup) MarkupBody(text, textScale)
                    else Text(text, style = MaterialTheme.typography.bodyMedium.scaledBy(textScale))
                }
                // One tap-through pill per missing permission → opens its System settings page.
                settingsTargets.forEach { (reqName, label) -> SettingsPill(label) { onOpenSettings(reqName) } }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(okLabel) } },
        // An explicitly blank cancel label (dialog.text with cancel="") drops the dismiss button —
        // for acknowledgment-only dialogs where there is nothing to cancel.
        dismissButton = cancelLabel?.let { { TextButton(onClick = onCancel) { Text(it) } } },
        // "large"/"full" have to opt out of the platform's fixed dialog width before any fillMaxWidth
        // on the surface can take effect.
        properties = DialogProperties(usePlatformDefaultWidth = size == DialogSize.NORMAL),
    )
}

// ---------------------------------------------------------------------------------------------
// Text-dialog sizing and markup.
//
// A `dialog.text` is the app's only way to put a page of prose in front of the user, and at the
// platform's default width a reference sheet (which gesture on which edge bar runs which task) is
// unreadable. `size` widens the surface; `markup` gives the body headings, bold and underline so the
// sheet has structure rather than being one grey block.
// ---------------------------------------------------------------------------------------------

/** How much of the screen a text dialog claims. */
internal enum class DialogSize { NORMAL, LARGE, FULL }

internal fun dialogSizeOf(raw: String): DialogSize = when (raw.trim().lowercase()) {
    "large", "big" -> DialogSize.LARGE
    "full", "fullscreen", "max" -> DialogSize.FULL
    else -> DialogSize.NORMAL
}

/** Leave a margin at every size: a dialog flush with the screen edge reads as a broken Activity. */
private fun DialogSize.surfaceModifier(): Modifier = when (this) {
    DialogSize.NORMAL -> Modifier
    DialogSize.LARGE -> Modifier.fillMaxWidth(0.94f)
    DialogSize.FULL -> Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f)
}

/**
 * One line of the markup. The syntax is deliberately tiny — it is written by tasks (often assembled
 * by an action, e.g. `scene.gestures`), so anything that needs escaping rules would be a trap:
 *
 *   `# text`   title      `## text` section (underlined)   `### text` / `#### text` sub-headings
 *   `- text`   bullet     `---`     horizontal rule
 *   inline: `**bold**`, `__underline__`, `*italic*`
 *
 * A line that matches nothing is body text, so plain text stays plain.
 *
 * Indentation is not written out: it follows the headings. A `##` sits flush, `###` one step in, and
 * a line of content steps in past the heading it belongs to — so the same document reads as a proper
 * outline without the author counting spaces. Two leading spaces on a line add one further step, for
 * the cases the outline cannot know about (a note that belongs under the line above it). A `---`
 * closes the outline and returns to the margin.
 */
private sealed interface MarkupBlock {
    data class Heading(val level: Int, val text: String) : MarkupBlock
    data class Bullet(val text: String, val extraIndent: Int) : MarkupBlock
    data class Body(val text: String, val extraIndent: Int) : MarkupBlock
    data object Rule : MarkupBlock
    data object Blank : MarkupBlock
}

private fun parseMarkup(text: String): List<MarkupBlock> = text.lines().map { raw ->
    val line = raw.trimEnd()
    val trimmed = line.trim()
    // Leading whitespace is an explicit extra step (2 spaces = 1 step); a tab counts as one step.
    val lead = line.takeWhile { it == ' ' || it == '\t' }
    val extra = lead.count { it == '\t' } + lead.count { it == ' ' } / 2
    when {
        trimmed.isEmpty() -> MarkupBlock.Blank
        trimmed.length >= 3 && trimmed.all { it == '-' } -> MarkupBlock.Rule
        trimmed.startsWith("#### ") -> MarkupBlock.Heading(4, trimmed.removePrefix("#### "))
        trimmed.startsWith("### ") -> MarkupBlock.Heading(3, trimmed.removePrefix("### "))
        trimmed.startsWith("## ") -> MarkupBlock.Heading(2, trimmed.removePrefix("## "))
        trimmed.startsWith("# ") -> MarkupBlock.Heading(1, trimmed.removePrefix("# "))
        trimmed.startsWith("- ") -> MarkupBlock.Bullet(trimmed.removePrefix("- "), extra)
        else -> MarkupBlock.Body(trimmed, extra)
    }
}

// Ordered alternation: `**` is tried before `*`, so bold never decays into two italics.
private val INLINE_MARKUP_RE = Regex("""\*\*(.+?)\*\*|__(.+?)__|\*(.+?)\*""")

private fun inlineSpans(text: String): AnnotatedString {
    if ('*' !in text && '_' !in text) return AnnotatedString(text)
    return buildAnnotatedString {
        var last = 0
        for (m in INLINE_MARKUP_RE.findAll(text)) {
            if (m.range.first > last) append(text.substring(last, m.range.first))
            val bold = m.groupValues[1]
            val underline = m.groupValues[2]
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
                underline.isNotEmpty() ->
                    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(underline) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
            }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }
}

/**
 * Grow (or shrink) a style by [scale]. lineHeight moves with fontSize — scaling the glyphs alone
 * leaves 1.5× text crammed into 1× leading, which is what makes enlarged text look broken.
 */
private fun TextStyle.scaledBy(scale: Float): TextStyle =
    if (scale == 1f) this else copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )

/**
 * One indent step, scaled with the text. The UI-customization page steps its rows by 16dp per level
 * ([com.opentasker.ui.screens] `rowStartPadding`); 13dp here, because a dialog on the folded cover
 * panel has a third of that page's width to spend and still owes two full steps.
 */
private fun indentStep(scale: Float) = 13.dp * scale

@Composable
internal fun MarkupBody(text: String, scale: Float) {
    val blocks = parseMarkup(text)
    Column(Modifier.fillMaxWidth()) {
        // depth = the level of the heading currently in force; content sits one step past it.
        var depth = 0
        var seenSection = false
        var index = 0
        while (index < blocks.size) {
            when (val block = blocks[index]) {
                is MarkupBlock.Heading -> {
                    MarkupHeading(block, scale, hairlineAbove = block.level == 2 && seenSection)
                    if (block.level == 2) seenSection = true
                    depth = block.level
                    index++
                }
                MarkupBlock.Rule -> {
                    // A rule closes the outline: what follows it (a footer, a second document) is not
                    // filed under the last heading and must not inherit its indent.
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 14.dp, bottom = 12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    depth = 0
                    index++
                }
                MarkupBlock.Blank -> {
                    Spacer(Modifier.height(9.dp * scale))
                    index++
                }
                else -> {
                    // Take the whole run of content belonging to this heading in one go, so the rail
                    // beside it is one continuous line rather than one stub per row.
                    val run = mutableListOf<MarkupBlock>()
                    while (index < blocks.size &&
                        blocks[index] !is MarkupBlock.Heading &&
                        blocks[index] != MarkupBlock.Rule
                    ) {
                        run += blocks[index]
                        index++
                    }
                    while (run.isNotEmpty() && run.last() == MarkupBlock.Blank) run.removeAt(run.size - 1)
                    MarkupRun(run, depth, scale)
                }
            }
        }
    }
}

/**
 * A heading, in the visual language of the UI-customization page: a `##` is that page's section
 * header — accent-coloured, with a 2dp accent rule as wide as the text itself and a full-width
 * hairline separating it from the section above — and `###`/`####` are its quieter sub-headers.
 */
@Composable
private fun MarkupHeading(block: MarkupBlock.Heading, scale: Float, hairlineAbove: Boolean) {
    val step = indentStep(scale)
    val indent = step * (block.level - 2).coerceAtLeast(0)
    if (hairlineAbove) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 18.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
    }
    when (block.level) {
        1, 2 -> Column(
            Modifier
                .padding(start = indent, top = if (hairlineAbove) 12.dp else 2.dp, bottom = 6.dp)
                // Max, not Min: with a CJK heading every character is a break point, so the minimum
                // intrinsic width is one glyph and the rule would shrink to it. (Same trap the UI
                // page's section header documents.)
                .width(IntrinsicSize.Max),
        ) {
            Text(
                inlineSpans(block.text),
                style = (if (block.level == 1) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.titleMedium).scaledBy(scale),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(3.dp))
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
        }
        else -> Text(
            inlineSpans(block.text),
            style = (if (block.level == 3) MaterialTheme.typography.labelLarge
            else MaterialTheme.typography.bodyMedium).scaledBy(scale),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Closer to what it introduces than to what came before it.
            modifier = Modifier.fillMaxWidth().padding(start = indent, top = 10.dp, bottom = 3.dp),
        )
    }
}

/**
 * The content under a heading: indented one step past it and, from the second step in, tied together
 * by a thin accent rail running down its left edge. The rail is what makes a long list of
 * gesture → task lines read as belonging to the bar named above it rather than floating.
 */
@Composable
private fun MarkupRun(run: List<MarkupBlock>, depth: Int, scale: Float) {
    if (run.isEmpty()) return
    val step = indentStep(scale)
    val steps = (depth - 1).coerceAtLeast(0)
    if (steps == 0) {
        Column(Modifier.fillMaxWidth()) { MarkupLines(run, scale) }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            // The rail stands at the PARENT's indent, so the text after it lands exactly on this
            // level's own step — the gutter is spent on the rail rather than added to the indent.
            .padding(start = step * (steps - 1))
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        )
        Spacer(Modifier.width(step - 2.dp))
        Column(Modifier.weight(1f)) { MarkupLines(run, scale) }
    }
}

@Composable
private fun MarkupLines(run: List<MarkupBlock>, scale: Float) {
    val step = indentStep(scale)
    val body = MaterialTheme.typography.bodyMedium.scaledBy(scale)
    run.forEach { block ->
        when (block) {
            MarkupBlock.Blank -> Spacer(Modifier.height(9.dp * scale))
            is MarkupBlock.Bullet -> Row(
                Modifier.fillMaxWidth().padding(start = step * block.extraIndent, bottom = 3.dp),
            ) {
                Text("•  ", style = body, color = MaterialTheme.colorScheme.primary)
                Text(inlineSpans(block.text), style = body)
            }
            is MarkupBlock.Body -> Text(
                inlineSpans(block.text),
                style = body,
                modifier = Modifier.fillMaxWidth()
                    .padding(start = step * block.extraIndent, bottom = 3.dp),
            )
            // Headings and rules are consumed by the caller and never reach a run.
            else -> Unit
        }
    }
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
