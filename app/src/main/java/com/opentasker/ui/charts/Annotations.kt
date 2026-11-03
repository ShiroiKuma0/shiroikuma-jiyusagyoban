package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * The window's annotation language: a note pill, its editor, and an authored-count pill.
 *
 * ## Ported from the sister apps rather than invented here
 *
 * 白い熊 応用管理 (`AppNotesManager` / `RowPills`) and 白い熊 考直 (`KojikiAppNotes`) have carried
 * free-text notes for months, and 白い熊 asked for the same thing here "the way we handle them
 * visually". So the rules come over unchanged, constants and all:
 *
 * - **One pill either way**, so the two states read as one control rather than two. With a note it
 *   holds the note glyph plus the note's FIRST LINE, single line, ellipsized; with none it holds the
 *   add glyph alone at a fixed width, so several add affordances stack into one column.
 * - **Alpha separates an invitation from a fact.** An add affordance is an invitation, not
 *   information, and is faded back into the card ([ADD_ALPHA]); a note that exists is drawn at full
 *   strength. The sister apps fade a written note too, because theirs shares a list row with an app
 *   name it must not outshout — ours has a card to itself, and faded it was merely hard to read
 *   (白い熊, 2026-09-03).
 * - **Transparent fill, hairline stadium outline**, over whatever card it sits on — and always in
 *   [ANNOTATION_INK], never in the host card's accent. See that constant for why.
 * - **Blank saves delete.** One rule, one control; no separately-shaped "delete" button to find.
 *
 * ## The count pill is deliberately NOT a rating
 *
 * 白い熊 asked for "a black pill, yellow number, yellow border" for the walks' stop count, and the
 * shape matters as much as the colour: every 1–5 rating in this window is a SOLID fill with no
 * border at all (settled 2026-08-12 and never revisited), so an outlined pill over black cannot be
 * mistaken for one. That is the whole distinction — **solid fill = a graded rating, outlined black
 * pill = a counted fact** — and it survives 白い熊's red-green deficiency untouched, because it is a
 * difference of shape and not of hue.
 */
object AnnotationText {
    val note = Loc("Note", "覚え書き")
    val noteAdd = Loc("Add a note", "覚え書きを付ける")
    val noteBlankDeletes = Loc("Saving it empty deletes the note.", "空のまま保存すると消えます。")
    val save = Loc("Save", "保存")
    val cancel = Loc("Cancel", "取消")
    val weightAdd = Loc("Record weight", "体重を記録")
    val weightTitle = Loc("Body weight — %s", "体重 — %s")
    val weightField = Loc("Weight in kg", "体重（kg）")
    val weightHint = Loc(
        "Saved at the date and time above — tap a digit to overwrite it. Saving it empty withdraws this weighing.",
        "上の日付と時刻で記録。数字を押すと上書きできる。空のまま保存するとこの記録を取り消す。",
    )
    val weightInvalid = Loc("A weight in kg, e.g. 134.2", "kg の数値で、例 134.2")
    val weightWhenInvalid = Loc("A real date and time, not in the future.", "正しい日付と時刻で、未来は不可。")
    val asleepAdd = Loc("Fell asleep at…", "就寝時刻を直す")
    val asleepAt = Loc("Fell asleep %s", "就寝 %s")
    val asleepTitle = Loc("Fell asleep — night to %s", "就寝時刻 — %s の朝までの夜")
    val asleepHint = Loc(
        "When you really fell asleep, where the watch started the night late or early — tap a digit to " +
            "overwrite it. Time before the watch's own start counts as sleep with no stage: in the night's " +
            "length and asleep minutes, never in deep, REM or their share.",
        "時計が夜を遅く（または早く）始めたときの、本当の就寝時刻。数字を押すと上書きできる。時計の開始より前の時間は" +
            "段階なしの睡眠として、夜の長さと睡眠時間には入り、深い睡眠・REM・その割合には入らない。",
    )
    val asleepInvalid = Loc(
        "A real date and time, before the night ended and at most 18 hours before it.",
        "正しい日付と時刻で、夜の終わりより前、18 時間以内。",
    )
    val asleepWithdraw = Loc("Back to the watch's", "時計の時刻に戻す")
    /** The card that holds a walk's own answers, as opposed to the band's measurements of it. */
    val own = Loc("Your own record", "自分の記録")
    val stops = Loc("Stops", "休憩")
    val stopsAsk = Loc("How many stops?", "何回休みましたか。")
    val stopsClear = Loc("Tap the number on file to withdraw it.", "記録した数字をもう一度押すと取り消せます。")
    val exportHeart = Loc("Heart rate as JSON", "心拍を JSON で")
    val exportGpx = Loc("Track as GPX", "経路を GPX で")
}

/**
 * The note, editable where it sits — tap the pill and type into it.
 *
 * ## Why this one is not the dialog
 *
 * Everywhere else a note is a pill that opens [NoteDialog], and that is right where the note is one
 * of several answers on a card: the dialog names what is being answered and has room for the
 * "blank deletes" rule. A lifting session has only this one answer — no stops, no map, nothing else
 * authored — so the dialog was a modal in front of a card whose entire content was the thing being
 * edited (白い熊, 2026-09-03: *"enable direct click inside to input the note text directly"*).
 *
 * ## Saving
 *
 * On losing focus and on the keyboard's Done, never per keystroke: a note is written in sentences,
 * and a save per character would rewrite `walk.json` a hundred times for one line. Blank still
 * deletes, which is the same contract the dialog carries — and [DisposableEffect] commits on the
 * way out, so leaving the screen mid-sentence keeps what was typed rather than discarding it.
 */
@Composable
fun NoteField(
    note: String?,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lang = LocalBandLanguage.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var typed by rememberSaveable(note) { mutableStateOf(note.orEmpty()) }
    var editing by rememberSaveable(note) { mutableStateOf(false) }
    val has = typed.isNotBlank()

    // Read through a live handle: the effect below runs at disposal, long after the composition
    // that captured them, and a stale `typed` there would save the note as it was on first draw.
    val latest by rememberUpdatedState(typed)
    val original by rememberUpdatedState(note.orEmpty())
    val save by rememberUpdatedState(onSave)
    DisposableEffect(Unit) {
        onDispose { if (latest.trim() != original.trim()) save(latest) }
    }

    val ink = if (has || editing) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    Row(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.5.dp, ink, RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            // Tapping anywhere in the pill puts the caret in it, not only the text itself: the
            // border is what reads as the target, and a 6 dp glyph that is the only live part of a
            // full-width control is the touch-target trap the morning card already carries a note
            // about.
            .clickable { focus.requestFocus() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (has) Icons.Filled.EditNote else Icons.AutoMirrored.Filled.NoteAdd,
            contentDescription = (if (has) AnnotationText.note else AnnotationText.noteAdd)[lang],
            tint = ink,
            modifier = Modifier.size(if (has) 22.dp else 20.dp),
        )
        BasicTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { s ->
                    if (s.isFocused) {
                        editing = true
                    } else if (editing) {
                        editing = false
                        if (typed.trim() != note.orEmpty().trim()) onSave(typed)
                    }
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ANNOTATION_INK),
            cursorBrush = SolidColor(ANNOTATION_INK),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { field ->
                // The invitation, shown only while there is nothing to read — never behind text.
                if (!has && !editing) {
                    Text(
                        AnnotationText.noteAdd[lang],
                        style = MaterialTheme.typography.bodyLarge,
                        color = ANNOTATION_INK.copy(alpha = ADD_ALPHA),
                    )
                }
                field()
            },
        )
    }
}

/**
 * A pill that DOES something, rather than holding something.
 *
 * Same shape and same yellow as the note and the count, because it sits among them and a control
 * that looked different would read as belonging to a different screen. What separates it is the
 * glyph and the fact that its label never changes: a note pill's text is the note, a count pill's
 * text is the count, and this one always says what pressing it will do.
 *
 * It goes inert while it is working. A file being written to shared storage takes long enough on a
 * cold card to be pressed twice, and two writes racing on one path is a truncated file rather than
 * two files.
 */
@Composable
fun ActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * A pill that is the point of its card rather than one option among several.
     *
     * 白い熊 asked for the 機能訓練 calendar to be bigger (2026-09-04), and it is the only way to a
     * whole page — everything else an action pill does is write a file. Size is what says so; the
     * colour is pure yellow either way, because that is the annotation language's ink and a second
     * colour would mean something it does not.
     */
    large: Boolean = false,
) {
    val ink = if (enabled) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    Row(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.5.dp, ink, RoundedCornerShape(100.dp))
            // Padding BEFORE clickable, or the touch target shrinks to the glyph.
            .padding(
                horizontal = if (large) 14.dp else 10.dp,
                vertical = if (large) 7.dp else 4.dp,
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 8.dp else 6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = ink,
            modifier = Modifier.size(if (large) 26.dp else 20.dp),
        )
        Text(
            label,
            style = if (large) {
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = ink,
        )
    }
}

/** Opacity of an "add" affordance: present, but reading as an invitation rather than as content. */
private const val ADD_ALPHA = 0.55f

/**
 * Pure yellow on black, the fork's own livery — and now the whole annotation language's, not just
 * the stop count's.
 *
 * 白い熊 asked for it in as many words (2026-09-03: "all yellow, the pill also yellow, on the walks
 * list, the note pill and icons also yellow"), and it is the right call beyond being what was asked:
 * a note was taking the colour of whatever card it happened to sit on — blue on a walk, amber on the
 * morning pill — so the one thing on these screens that 白い熊 WROTE looked like a different object
 * in each place. One colour for every annotation, and it is the app's own.
 */
val ANNOTATION_INK = Color(0xFFFFFF00)
/**
 * The ground every annotation is drawn on: the fork's own black, never the card it happens to sit on.
 *
 * Shared beyond this file since 2026-09-20, when the day-calendar note mark needed it — a yellow
 * glyph straight onto a scale colour is invisible on the yellow step and nearly so on the emerald
 * (白い熊), and what makes the pills legible anywhere is precisely that they bring their own ground.
 */
val ANNOTATION_FILL = Color(0xFF000000)

/**
 * A note affordance for one thing — a night, a walk, whatever carries [note].
 *
 * The full text is never shown here on purpose: a note may be a paragraph, and a row that grows with
 * it is a row that moves everything below it. The first line is the summary; the editor behind the
 * tap is where the rest lives.
 */
@Composable
fun NotePill(
    note: String?,
    modifier: Modifier = Modifier,
    /**
     * Smaller type for a place with no width to spend — today only the walks grid, where a cell is
     * 168 dp wide and shares it with the stop count. At the ordinary size a note there truncated to
     * "stoppe…", which is a pill that has stopped saying anything. Everywhere else the note gets the
     * size 白い熊 asked for.
     */
    compact: Boolean = false,
    /**
     * Null renders the pill as a READING rather than a control — no ripple, no touch target.
     *
     * The walks grid needs exactly that: a cell already opens the walk when tapped, and a pill
     * inside it that swallowed the tap to open a second thing would make half the cell do something
     * different from the other half.
     */
    onClick: (() -> Unit)? = null,
) {
    val lang = LocalBandLanguage.current
    val has = !note.isNullOrBlank()
    // A WRITTEN note is at full strength now. The faded treatment came over from the sister apps,
    // where a note shares a dense list row with an app name it must not outshout; here it has a card
    // of its own and nothing to compete with, and at 0.80 of a mid-blue on black it was simply hard
    // to read (白い熊, 2026-09-03). The invitation keeps its lower alpha — that distinction is the
    // one the hierarchy was actually carrying.
    val ink = if (has) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    val border = if (has) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    Row(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.5.dp, border, RoundedCornerShape(100.dp))
            // Padding BEFORE clickable, or the touch target shrinks to the glyph — the same trap the
            // morning card's 1–5 buttons carry a note about, found there the hard way.
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (has) Icons.Filled.EditNote else Icons.AutoMirrored.Filled.NoteAdd,
            contentDescription = (if (has) AnnotationText.note else AnnotationText.noteAdd)[lang],
            tint = ink,
            modifier = Modifier.size(if (has && !compact) 22.dp else if (has) 18.dp else 20.dp),
        )
        // With a note, the first line IS the label — it says what this pill is far better than the
        // word "note" would. Without one, the word is what makes the glyph legible as an
        // invitation: the sister apps can leave it out because their pill sits at the end of a list
        // row where the pattern is learned once and read fifty times, and every one of ours stands
        // alone on a card. (The one place a bare glyph would be right — the walks grid — never
        // renders the empty state at all: a walk with nothing written on it shows nothing.)
        Text(
            if (has) note.orEmpty().trim().lineSequence().first() else AnnotationText.noteAdd[lang],
            // The note reads at the size of the figures it sits among, not at a footnote's — it is
            // the one line on the card that was typed rather than measured, and it was the smallest
            // thing on the screen. The invitation stays smaller: it is a label for a control.
            style = if (has && !compact) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Read and edit one note.
 *
 * The field opens pre-filled and immediately editable, Save persists, and an emptied field deletes —
 * the helper line says so, because a control that deletes has to announce it before it is used and
 * not after.
 *
 * [title] names what is being annotated, in full. The register's rating dialog makes the same point
 * at length: an annotation filed against the wrong night looks authored, so the subject is stated
 * above the thing that writes it rather than left to whatever was tapped a moment ago.
 */
@Composable
fun NoteDialog(
    title: String,
    note: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    // Saveable: a rotation with the editor open must not silently drop what has been typed into it.
    var typed by rememberSaveable(title) { mutableStateOf(note.orEmpty()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text(AnnotationText.note[lang]) },
                // Room for a few lines without the dialog growing as it is typed into: a field that
                // resizes under the finger moves the Save button away from where it was aimed.
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                AnnotationText.noteBlankDeletes[lang],
                style = MaterialTheme.typography.bodyMedium,
                color = sectionInk,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogAction(AnnotationText.cancel[lang], main = false, onDismiss)
                Spacer(Modifier.width(10.dp))
                DialogAction(AnnotationText.save[lang], main = true) { onSave(typed) }
            }
        }
    }
}

/** A dialog's text action — the register's own close-button shape, shared so they match. */
@Composable
private fun DialogAction(text: String, main: Boolean, onClick: () -> Unit) {
    // Pills, not bare words (白い熊, 2026-11-03): Save the main one, Cancel a regular one — see
    // ui/components/Pills.kt. Both in yellow; the blue Cancel read as a note, not a button.
    if (main) {
        com.opentasker.ui.components.Button(onClick = onClick) { Text(text) }
    } else {
        com.opentasker.ui.components.OutlinedButton(onClick = onClick) { Text(text) }
    }
}

/**
 * An authored count — black pill, yellow number, yellow border, exactly as asked (白い熊, 2026-09-02).
 *
 * Unanswered it shows a "+" at the add affordance's alpha, so the pill is the same object whether or
 * not there is an answer in it, and the thing to press is in the place the answer will appear.
 */
@Composable
fun CountPill(
    count: Int?,
    modifier: Modifier = Modifier,
    /** Null renders it as a reading — see [NotePill]'s note on the same parameter. */
    onClick: (() -> Unit)? = null,
) {
    val ink = if (count != null) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    Box(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .background(ANNOTATION_FILL)
            .border(1.5.dp, ink, RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            // Wide enough that a one-digit pill and a "+" pill are the same width, so a row of them
            // does not shuffle sideways as answers arrive.
            .widthIn(min = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count?.toString() ?: "+",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = ink,
        )
    }
}

/**
 * Pick a count, or withdraw the one on file by tapping it again.
 *
 * The same contract as the 1–5 rating: one tap files it and closes, because with a single value to
 * set there is no state to accumulate and a picker that then wants confirming is a second chance to
 * file the wrong thing. Re-tapping the stored number clears it — the caller compares and decides,
 * exactly as `setFeltFor` does for the ratings.
 */
@Composable
fun CountPickerDialog(
    title: String,
    current: Int?,
    range: IntRange,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (n in range) {
                    val chosen = current == n
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(100.dp))
                            // Chosen inverts rather than gaining a second border: the pill's whole
                            // identity is a yellow outline on black, and a "selected" outline on top
                            // of that would be a third border in a window that already reserves one
                            // for "today".
                            .background(if (chosen) ANNOTATION_INK else ANNOTATION_FILL)
                            .border(1.5.dp, ANNOTATION_INK, RoundedCornerShape(100.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clickable { onPick(n) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$n",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (chosen) ANNOTATION_FILL else ANNOTATION_INK,
                        )
                    }
                }
            }
            Text(
                AnnotationText.stopsClear[lang],
                style = MaterialTheme.typography.bodyMedium,
                color = sectionInk,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogAction(AnnotationText.cancel[lang], main = false, onDismiss)
            }
        }
    }
}


/**
 * Today's body weight, or the invitation to record it — under the note pill on the 今朝 card.
 *
 * The note pill's own shape and livery, so the card's two authored things read as one family: faded
 * while nothing is recorded, full strength once a figure is. The figure is the label, as a written
 * note's first line is: "134.2 kg" says what the pill holds better than any word for it.
 */
@Composable
fun WeightPill(kg: Double?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val has = kg != null
    val ink = if (has) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    Row(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.5.dp, ink, RoundedCornerShape(100.dp))
            // Padding BEFORE clickable, or the touch target shrinks to the glyph — see [NotePill].
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.MonitorWeight,
            contentDescription = AnnotationText.weightAdd[lang],
            tint = ink,
            modifier = Modifier.size(if (has) 22.dp else 20.dp),
        )
        Text(
            kg?.let { String.format("%.1f kg", it) } ?: AnnotationText.weightAdd[lang],
            style = if (has) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            fontWeight = if (has) FontWeight.Bold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
        )
    }
}

/** Parse what was typed into the weight field: a dot or a comma, a plausible adult weight, or null. */
fun parseWeightKg(typed: String): Double? =
    typed.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 20.0..400.0 }

/**
 * Ask for a weight in kg, and when it was taken — [NoteDialog]'s shape, with a number pad.
 *
 * Opens on [dateKey]'s reading when there is one ([currentKg] at [currentAtMs]), so a second weighing
 * is an edit of a number in place; today with none opens at the current minute, a past day at **07:00**,
 * the time 白い熊 weighs (2026-11-01).
 * The same dialog serves today's pill and any past morning in the register, so the date is editable
 * here too, not only the time.
 *
 * Save hands back the instant in the boxes and the parsed kg, or null for an emptied field (withdraw
 * the reading); a figure that is not a weight, or a date and time that is not real or lies in the
 * future, keeps the dialog open with its hint in yellow.
 */
@Composable
fun WeightDialog(
    dateKey: Long,
    currentKg: Double?,
    currentAtMs: Long?,
    onSave: (atMs: Long, kg: Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val zone = remember { java.time.ZoneId.systemDefault() }
    var typed by rememberSaveable { mutableStateOf(currentKg?.let { String.format(java.util.Locale.ROOT, "%.1f", it) }.orEmpty()) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var whenInvalid by rememberSaveable { mutableStateOf(false) }
    // "yyyyMMddHHmm", one digit per box.
    var digits by rememberSaveable {
        val start = currentAtMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?: java.time.LocalDate.of((dateKey / 10_000L).toInt(), ((dateKey / 100L) % 100L).toInt(), (dateKey % 100L).toInt())
                .let { day ->
                    // Today opens at the minute it is now — a weighing being entered as it happens;
                    // 07:00 on today's pill was a time 白い熊 then had to correct (2026-11-03).
                    // A past morning has no "now", so it keeps the usual weighing time.
                    val now = java.time.LocalDateTime.now(zone)
                    if (day == now.toLocalDate()) now.withSecond(0).withNano(0) else day.atTime(7, 0)
                }
        mutableStateOf(start.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")))
    }
    val chosen = weighingTimeOf(digits)
    val focus = remember { FocusRequester() }
    val boxes = remember { List(12) { FocusRequester() } }
    fun save() {
        val at = chosen?.takeIf { !it.toLocalDate().isAfter(java.time.LocalDate.now(zone)) }
        if (at == null) { whenInvalid = true; return }
        val atMs = at.atZone(zone).toInstant().toEpochMilli()
        if (typed.isBlank()) return onSave(atMs, null)
        val kg = parseWeightKg(typed)
        if (kg == null) invalid = true else onSave(atMs, kg)
    }
    fun box(i: Int, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp): @Composable () -> Unit = {
        DigitBox(
            digit = digits[i],
            focus = boxes[i],
            width = width,
            height = height,
            onDigit = { c ->
                digits = digits.substring(0, i) + c + digits.substring(i + 1)
                whenInvalid = false
                // Straight on to the next digit — the last time digit hands over to the kg field.
                runCatching { if (i < 11) boxes[i + 1].requestFocus() else focus.requestFocus() }
            },
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        // Wider than the platform's 320 dp dialog: twelve digit boxes do not fit in that at a font
        // scale of 1.3, and shrinking the boxes would shrink the very thing that is being tapped.
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The title follows the boxes, so the day named is always the day that will be saved.
            Text(
                AnnotationText.weightTitle[lang].format(
                    nightDateFull(chosen?.toLocalDate()?.let { com.opentasker.core.band.RehabLog.dateKeyOf(it) } ?: dateKey, lang),
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..3) box(i, 32.dp, 40.dp)()
                BoxSeparator("-")
                for (i in 4..5) box(i, 32.dp, 40.dp)()
                BoxSeparator("-")
                for (i in 6..7) box(i, 32.dp, 40.dp)()
            }
            // The time as 白い熊 drew it: four square boxes, a colon in the middle.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 8..9) box(i, 44.dp, 44.dp)()
                BoxSeparator(":")
                for (i in 10..11) box(i, 44.dp, 44.dp)()
            }
            if (whenInvalid) {
                Text(
                    AnnotationText.weightWhenInvalid[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = ANNOTATION_INK,
                )
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it; invalid = false },
                label = { Text(AnnotationText.weightField[lang]) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                textStyle = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            if (invalid) {
                Text(
                    AnnotationText.weightInvalid[lang],
                    style = MaterialTheme.typography.bodyMedium,
                    color = ANNOTATION_INK,
                )
            }
            Text(
                AnnotationText.weightHint[lang],
                style = MaterialTheme.typography.bodyMedium,
                color = sectionInk,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogAction(AnnotationText.cancel[lang], main = false, onDismiss)
                Spacer(Modifier.width(10.dp))
                DialogAction(AnnotationText.save[lang], main = true) { save() }
            }
        }
    }
    // The number pad up at once: this dialog exists to take one figure.
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** The boxes' "yyyyMMddHHmm" as a real local date-time, or null while it is out of range (a 13th month, 25:00). */
internal fun weighingTimeOf(digits: String): java.time.LocalDateTime? = runCatching {
    if (digits.length != 12 || !digits.all { it.isDigit() }) return null
    java.time.LocalDateTime.of(
        digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt(),
        digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(),
    )
}.getOrNull()

@Composable
private fun BoxSeparator(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ANNOTATION_INK)
}

/**
 * One digit of a date or a time: a yellow-ringed black square, inverted while it has the focus.
 *
 * A tap makes the digit ready to be OVERWRITTEN rather than dropping a caret beside it, and the
 * caller moves the focus straight on to the next box, which arrives the same way (白い熊,
 * 2026-11-01). The field underneath is kept permanently empty and the digit is drawn over it: then
 * every key is new text, so typing the digit that is already there still counts and still moves on,
 * which a field holding the digit selected cannot tell apart from a tap. There is no empty box — a
 * digit is only ever replaced — so the date and time in the boxes are always whole.
 */
@Composable
private fun DigitBox(
    digit: Char,
    focus: FocusRequester,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onDigit: (Char) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ink = if (focused) ANNOTATION_FILL else ANNOTATION_INK
    BasicTextField(
        value = "",
        onValueChange = { typed -> typed.lastOrNull { it.isDigit() }?.let(onDigit) },
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) ANNOTATION_INK else ANNOTATION_FILL)
            .border(1.5.dp, ANNOTATION_INK, RoundedCornerShape(6.dp))
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
        textStyle = MaterialTheme.typography.titleLarge.copy(color = ink),
        cursorBrush = SolidColor(Color.Transparent),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        decorationBox = { field ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(digit.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
                field()
            }
        },
    )
}

/**
 * The night's own start, on a pill in the night dialog — the weight pill's shape. Shows the start in
 * use; [authored] says whether that is 白い熊's correction or still the watch's.
 */
@Composable
fun NightStartPill(startMs: Long?, authored: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val lang = LocalBandLanguage.current
    val ink = if (authored) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    val clock = startMs?.let {
        java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
    Row(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.5.dp, ink, RoundedCornerShape(100.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Bedtime, contentDescription = AnnotationText.asleepAdd[lang], tint = ink, modifier = Modifier.size(20.dp))
        Text(
            if (authored && clock != null) AnnotationText.asleepAt[lang].format(clock) else AnnotationText.asleepAdd[lang],
            style = if (authored) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            fontWeight = if (authored) FontWeight.Bold else FontWeight.Normal,
            color = ink,
            maxLines = 1,
        )
    }
}

/**
 * When 白い熊 really fell asleep, for the night ending on the morning [dateKey] — the weight dialog's
 * digit boxes, date and time, so a bedtime before midnight is as easy as one after it.
 *
 * Opens on the start in use ([currentStartMs]): the correction if there is one, else the watch's own.
 * Save hands back the instant; a time that is not real, not before the night's end ([endMs]) or more
 * than 18 hours before it keeps the dialog open. [authored] adds the way back to the watch's start.
 */
@Composable
fun NightStartDialog(
    dateKey: Long,
    currentStartMs: Long?,
    endMs: Long?,
    authored: Boolean,
    onSave: (atMs: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val zone = remember { java.time.ZoneId.systemDefault() }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var digits by rememberSaveable {
        val start = currentStartMs?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
            ?: java.time.LocalDate.of((dateKey / 10_000L).toInt(), ((dateKey / 100L) % 100L).toInt(), (dateKey % 100L).toInt())
                .atTime(0, 0)
        mutableStateOf(start.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm")))
    }
    val boxes = remember { List(12) { FocusRequester() } }
    fun save() {
        val at = weighingTimeOf(digits)?.atZone(zone)?.toInstant()?.toEpochMilli()
        val end = endMs ?: System.currentTimeMillis()
        if (at == null || at >= end || at < end - 18 * 3_600_000L) { invalid = true; return }
        onSave(at)
    }
    fun box(i: Int, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp): @Composable () -> Unit = {
        DigitBox(
            digit = digits[i],
            focus = boxes[i],
            width = width,
            height = height,
            onDigit = { c ->
                digits = digits.substring(0, i) + c + digits.substring(i + 1)
                invalid = false
                runCatching { if (i < 11) boxes[i + 1].requestFocus() }
            },
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.5.dp, sectionInk, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                AnnotationText.asleepTitle[lang].format(nightDateFull(dateKey, lang)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = sectionInk,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..3) box(i, 32.dp, 40.dp)()
                BoxSeparator("-")
                for (i in 4..5) box(i, 32.dp, 40.dp)()
                BoxSeparator("-")
                for (i in 6..7) box(i, 32.dp, 40.dp)()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 8..9) box(i, 44.dp, 44.dp)()
                BoxSeparator(":")
                for (i in 10..11) box(i, 44.dp, 44.dp)()
            }
            if (invalid) {
                Text(AnnotationText.asleepInvalid[lang], style = MaterialTheme.typography.bodyMedium, color = ANNOTATION_INK)
            }
            Text(AnnotationText.asleepHint[lang], style = MaterialTheme.typography.bodyMedium, color = sectionInk)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (authored) DialogAction(AnnotationText.asleepWithdraw[lang], main = false) { onSave(null) }
                DialogAction(AnnotationText.cancel[lang], main = false, onDismiss)
                DialogAction(AnnotationText.save[lang], main = true) { save() }
            }
        }
    }
    // The 2-digit hour first: the date is almost always right, the time is what is being corrected.
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { boxes[8].requestFocus() } }
}
