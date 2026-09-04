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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.EditNote
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
) {
    val ink = if (enabled) ANNOTATION_INK else ANNOTATION_INK.copy(alpha = ADD_ALPHA)
    Row(
        modifier
            .clip(RoundedCornerShape(100.dp))
            .border(1.5.dp, ink, RoundedCornerShape(100.dp))
            // Padding BEFORE clickable, or the touch target shrinks to the glyph.
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink)
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
private val COUNT_FILL = Color(0xFF000000)

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
            NoteText(AnnotationText.noteBlankDeletes[lang])
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogAction(AnnotationText.cancel[lang], sectionNote, onDismiss)
                Spacer(Modifier.width(6.dp))
                DialogAction(AnnotationText.save[lang], sectionInk) { onSave(typed) }
            }
        }
    }
}

/** A dialog's text action — the register's own close-button shape, shared so they match. */
@Composable
private fun DialogAction(text: String, ink: Color, onClick: () -> Unit) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = ink,
    )
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
            .background(COUNT_FILL)
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
                            .background(if (chosen) ANNOTATION_INK else COUNT_FILL)
                            .border(1.5.dp, ANNOTATION_INK, RoundedCornerShape(100.dp))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clickable { onPick(n) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$n",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (chosen) COUNT_FILL else ANNOTATION_INK,
                        )
                    }
                }
            }
            NoteText(AnnotationText.stopsClear[lang])
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogAction(AnnotationText.cancel[lang], sectionInk, onDismiss)
            }
        }
    }
}
