package com.opentasker.ui.charts.huawei

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiFaceLibrary
import com.opentasker.core.huawei.HuaweiUploadClient
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.ChartPalette
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionCard
import com.opentasker.ui.charts.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the window is doing, so a cell can show it without inventing its own state. */
data class HuaweiFacesState(
    val faces: List<HuaweiFaceLibrary.Entry> = emptyList(),
    val dir: String = "",
    val loading: Boolean = true,
    /** The face being installed, by id. Null when the band is free. */
    val installing: String? = null,
    val bytesSent: Int = 0,
    val message: String? = null,
    /** What the band answered when last asked, or null if it has not been asked. */
    val band: HuaweiUploadClient.FaceStore? = null,
    val reading: Boolean = false,
    /** The face being removed, by asset id. */
    val deleting: String? = null,
    /** The face being brought to the front, by asset id. */
    val activating: String? = null,
    /**
     * Set when an install stopped because the band had no free slot.
     *
     * Carries the face that wanted in and what the band was holding when it said no, because the
     * question "which one goes?" cannot be asked without both, and re-reading the band to ask it
     * would be a second session.
     */
    val roomNeeded: RoomRequest? = null,
    /**
     * Names 白い熊 has given to faces this library holds no copy of, by asset id.
     *
     * Nine of the faces on his band are Huawei Health's, and the band identifies them by a
     * ten-digit number alone. Deleting one to make room is irreversible — there is no copy here to
     * reinstall from — so the number has to be turnable into something recognisable first.
     */
    val names: Map<String, String> = emptyMap(),
    /** Live readout of which face the band is showing, while it is being cycled by hand. */
    val identify: IdentifyState? = null,
    /**
     * A face on the band, this library has no copy of, waiting on a yes before it is removed.
     *
     * Confirmed rather than immediate because the removal is final for exactly these faces: the
     * grid's own Remove is safe — the ZIP is on disk and reinstalls — and this one is not.
     */
    val confirmRemove: HuaweiUploadClient.InstalledFace? = null,
) {
    /**
     * Reading, installing and removing all open the one session the band allows, so any of them
     * makes every button in the grid inert. Deriving it here rather than at each call site is what
     * stops a new button being added that forgets one of the three.
     */
    val bandBusy: Boolean
        get() = installing != null || reading || deleting != null || activating != null

    /** Whether the band is holding this face — false whenever the band has not been read. */
    fun onBand(assetId: String): Boolean =
        band?.faces?.any { it.assetId == assetId } == true

    /** The face the band is showing right now, if it has been read. */
    fun isShowing(assetId: String): Boolean =
        band?.faces?.any { it.assetId == assetId && it.showing } == true

    /** The version the BAND has for a face, which is the one a delete or an activate must name. */
    fun bandVersion(assetId: String): String? =
        band?.faces?.firstOrNull { it.assetId == assetId }?.version
}

/**
 * The naming session: one held connection, reporting what the band is showing.
 *
 * [showing] is the band's own answer and may be null — either it has not been read yet, or the band
 * reports no face as current. Both are worth distinguishing from "the watch has stopped", which is
 * what [running] is for.
 */
data class IdentifyState(
    val running: Boolean = false,
    val showing: HuaweiUploadClient.InstalledFace? = null,
    val message: String? = null,
)

/**
 * What to call a face on the band, in the order the answer is trustworthy.
 *
 * The library's own name first — it is the one thing here that is certainly right. Then a name
 * 白い熊 gave the id himself. Only then the bare number, which is what the picker used to offer for
 * nine of his faces and is no basis for deleting anything.
 */
internal fun faceLabel(
    assetId: String,
    library: List<HuaweiFaceLibrary.Entry>,
    names: Map<String, String>,
    unknown: String,
): String = library.firstOrNull { it.assetId == assetId }?.name
    ?: names[assetId]
    ?: "$assetId ($unknown)"

/** An install waiting on 白い熊 to say which face may be given up. */
data class RoomRequest(
    val incoming: HuaweiFaceLibrary.Entry,
    val onBand: List<HuaweiUploadClient.InstalledFace>,
)

/**
 * The watch-face library: pick one, install it.
 *
 * ## Why every button disables together
 *
 * The band serves a single connection and `HuaweiSessionGuard` enforces one session process-wide, so
 * a second install started while the first is running does not queue — it is refused outright. A
 * grid where the other buttons stayed live would therefore answer a tap with "a sync is already
 * running", which reads as a broken button rather than as a busy band. Disabling all of them says
 * the true thing: the band is occupied.
 */
@Composable
fun HuaweiFacesScreen(
    state: HuaweiFacesState,
    contentPadding: PaddingValues,
    /**
     * Where a cell's picture comes from, for callers not reading a real archive — today the
     * screenshot previews, which are the only way this layout gets looked at before it ships,
     * since the phone is normally locked.
     *
     * Null means the ordinary path: read the ZIP off the main thread. **A supplied one is called
     * synchronously**, and that is the point — the screenshot engine renders a single frame and
     * never runs a `produceState`, so an asynchronous seam drew every cell as "no picture" and the
     * previews silently stopped being evidence of anything.
     */
    previewOf: ((HuaweiFaceLibrary.Entry) -> ByteArray?)? = null,
    onReadBand: () -> Unit = {},
    /** Open the naming session — one held connection, watching which face the band shows. */
    onIdentifyStart: () -> Unit = {},
    onIdentifyStop: () -> Unit = {},
    /** Name (or, with a blank name, forget) one asset id. */
    onNameFace: (String, String) -> Unit = { _, _ -> },
    /** Ask before removing a face the library has no copy of. Null clears the question. */
    onAskRemoveBandFace: (HuaweiUploadClient.InstalledFace?) -> Unit = {},
    /** Actually remove it, once confirmed. */
    onRemoveBandFace: (HuaweiUploadClient.InstalledFace) -> Unit = {},
    onRemove: (HuaweiFaceLibrary.Entry) -> Unit = {},
    onActivate: (HuaweiFaceLibrary.Entry) -> Unit = {},
    /** The chosen victim, or null when 白い熊 cancels the install instead. */
    onResolveRoom: (HuaweiUploadClient.InstalledFace?) -> Unit = {},
    /** Last so it stays the trailing lambda — installing is what this window is for. */
    onInstall: (HuaweiFaceLibrary.Entry) -> Unit,
) {
    val lang = LocalBandLanguage.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            BandCard(state, onReadBand, onIdentifyStart, onAskRemoveBandFace)
        }
        if (state.message != null || state.faces.isEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                SectionCard(accent = ChartPalette.AXIS_TEXT) {
                    SectionTitle(HuaweiText.facesTitle[lang], ChartPalette.AXIS_TEXT)
                    BodyText(state.message ?: HuaweiText.facesEmpty[lang])
                    NoteText(state.dir)
                }
            }
        }
        items(state.faces, key = { it.zip.absolutePath }) { face ->
            FaceCell(
                face = face,
                installing = state.installing == face.id,
                removing = state.deleting == face.assetId,
                onBand = state.onBand(face.assetId),
                showing = state.isShowing(face.assetId),
                bandBusy = state.bandBusy,
                sent = state.bytesSent,
                previewOf = previewOf,
                activating = state.activating == face.assetId,
                onInstall = { onInstall(face) },
                onRemove = { onRemove(face) },
                onActivate = { onActivate(face) },
            )
        }
    }
    state.roomNeeded?.let { RoomDialog(it, state.faces, state.names, onResolveRoom) }
    state.identify?.let {
        IdentifyDialog(it, state.faces, state.names, onNameFace, onIdentifyStop)
    }
    state.confirmRemove?.let { face ->
        ConfirmRemoveDialog(
            label = state.names[face.assetId] ?: face.assetId,
            onKeep = { onAskRemoveBandFace(null) },
            onRemove = { onAskRemoveBandFace(null); onRemoveBandFace(face) },
        )
    }
}

/**
 * "The band is full — which face should go?"
 *
 * A dialog rather than an automatic eviction (白い熊, 2026-08-28). The band holds twelve and a face
 * on the wrist is a choice, so picking the victim by rule — oldest, largest, whatever — would be
 * this app deciding something it has no standing to decide. Nothing is destroyed either way: the
 * library keeps every face, so a removal is undone by installing it again.
 *
 * The face currently on screen is offered like any other. Predicting what the band will refuse is
 * exactly the mistake that once locked seven of 白い熊's own faces behind a rule that did not exist.
 */
/**
 * The naming session.
 *
 * One connection is held open while this is up, and the band is asked every couple of seconds which
 * face it is showing. 白い熊 turns the dial; each face announces its own ten-digit id here and can
 * be given a name. That name is what the "band is full" picker shows afterwards, so a stock face he
 * wants to keep is never a number he has to guess about.
 *
 * The field is seeded from whatever the id is already called and re-seeded whenever the band moves
 * to a different face — typing a name for one face and having it follow you to the next would be a
 * quiet way to mislabel the lot.
 */
@Composable
private fun IdentifyDialog(
    identify: IdentifyState,
    library: List<HuaweiFaceLibrary.Entry>,
    names: Map<String, String>,
    onName: (String, String) -> Unit,
    onDone: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val showing = identify.showing
    val known = showing?.let { f -> library.firstOrNull { it.assetId == f.assetId } }
    var typed by remember(showing?.assetId) { mutableStateOf(names[showing?.assetId] ?: "") }
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onDone,
        title = { Text(HuaweiText.facesIdentifyTitle[lang]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteText(HuaweiText.facesIdentifyHow[lang])
                when {
                    showing != null -> {
                        SectionTitle(showing.assetId, ChartPalette.STEPS)
                        // The version matters: a delete names both, and two installs of the same
                        // face differ only there.
                        NoteText(showing.version)
                        if (known != null) {
                            // Nothing to name — the library already knows this one, and a second
                            // name for it would only disagree with the first.
                            BodyText("${known.name} — ${HuaweiText.facesIdentifyInLibrary[lang]}")
                        } else {
                            OutlinedTextField(
                                value = typed,
                                onValueChange = { typed = it },
                                singleLine = true,
                                label = { Text(HuaweiText.facesIdentifyName[lang]) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // THE PRESS HAS TO SAY SOMETHING.
                            //
                            // Saving worked from the first build, but silently: the name appeared
                            // on the screen behind the dialog and nowhere in it, so the button read
                            // as broken (白い熊, 2026-08-30 — "it works after having exited it, but
                            // that's no good"). The stored name is the feedback. Once the field
                            // matches it there is nothing left to save, so the button says so and
                            // stops being pressable, and the line beneath states the name that is
                            // now on file. Both are real state rather than a flash that is gone by
                            // the time you look up from the band.
                            val stored = names[showing.assetId]
                            val dirty = typed.trim() != (stored ?: "")
                            Button(
                                onClick = { onName(showing.assetId, typed) },
                                enabled = dirty,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    when {
                                        !dirty -> HuaweiText.facesIdentifySaved[lang]
                                        typed.isBlank() -> HuaweiText.facesIdentifyForget[lang]
                                        else -> HuaweiText.facesIdentifySave[lang]
                                    },
                                )
                            }
                            stored?.let {
                                BodyText("${HuaweiText.facesIdentifySavedAs[lang]}: \u201c$it\u201d")
                            }
                        }
                    }
                    identify.running -> BodyText(HuaweiText.facesIdentifyWaiting[lang])
                    else -> BodyText(HuaweiText.facesIdentifyNone[lang])
                }
                identify.message?.let { NoteText(it, warn = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) { Text(HuaweiText.facesIdentifyDone[lang]) }
        },
    )
}

/**
 * The one guard on removing a face this library cannot put back.
 *
 * The grid's own Remove needs no confirmation — the ZIP is on disk and reinstalls in a minute. This
 * one is different in kind, and the dialog says which difference rather than asking "are you sure"
 * about nothing in particular.
 */
@Composable
private fun ConfirmRemoveDialog(label: String, onKeep: () -> Unit, onRemove: () -> Unit) {
    val lang = LocalBandLanguage.current
    AlertDialog(
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = onKeep,
        title = { Text(HuaweiText.facesRemoveConfirmTitle[lang]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle(label, ChartPalette.STEPS)
                BodyText(HuaweiText.facesRemoveConfirmBody[lang])
            }
        },
        confirmButton = {
            TextButton(onClick = onRemove) { Text(HuaweiText.facesRemoveConfirmYes[lang]) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(HuaweiText.facesRemoveCancel[lang]) }
        },
    )
}

@Composable
private fun RoomDialog(
    request: RoomRequest,
    library: List<HuaweiFaceLibrary.Entry>,
    names: Map<String, String>,
    onResolve: (HuaweiUploadClient.InstalledFace?) -> Unit,
) {
    val lang = LocalBandLanguage.current
    var chosen by remember(request.incoming.id) {
        mutableStateOf<HuaweiUploadClient.InstalledFace?>(null)
    }
    AlertDialog(
        // The app's own dialog frame — 1.5 dp of the theme's primary on a 28 dp corner, exactly as
        // ImportReviewDialogs and EmojiPickerDialog wear it. Material draws no border of its own, so
        // a dialog that omits this is a black panel on a black screen (白い熊, 2026-08-28).
        modifier = Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)),
        onDismissRequest = { onResolve(null) },
        title = { Text(HuaweiText.facesFullTitle[lang]) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BodyText("${request.incoming.name} — ${HuaweiText.facesFullBody[lang]}")
                NoteText(HuaweiText.facesFullPick[lang])
                // Scrolling, and bounded. The band holds as many faces as it holds — eighteen on
                // 白い熊's — and a plain Column asks the dialog for a height it cannot have: the
                // slot squeezes the overflow instead of scrolling it, so the last row renders as
                // two radio buttons stacked inside one line and everything past it is unreachable
                // (白い熊, 2026-08-28). The cap leaves the prose above and the buttons below room.
                LazyColumn(
                    // Weighted, not a fixed cap. Material already bounds this slot — it lays the
                    // text container out with `weight(1f, fill = false)` between the title and the
                    // buttons — so weighting inside it hands the list exactly the height the dialog
                    // has left on THIS screen, tall phone or short. A dp cap picked here would be
                    // wrong on both: wasted space on one, a squeezed last row on the other.
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        // Framed, because the pane scrolls. A bare scrolling list inside a dialog
                        // gives no sign of where it ends, so a row cut off at the bottom edge reads
                        // as a rendering fault rather than as "there is more below" — which is
                        // exactly how the unbounded version was first reported. The border makes it
                        // a pane: what is inside it moves, what is outside it does not.
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        // Clipped to the same shape so a row scrolling past cannot paint over the
                        // rounded corners it is meant to be contained by.
                        .clip(RoundedCornerShape(12.dp))
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Named and library faces LAST. A face this library holds can be reinstalled
                    // from the copy on disk; one 白い熊 has named is one he said he wants. The
                    // unnamed strangers — the ones nothing is known about and nothing is lost by —
                    // come first, so the safe choice is the one under the thumb.
                    val ordered = request.onBand.sortedBy { f ->
                        if (library.any { it.assetId == f.assetId } || names.containsKey(f.assetId)) 1
                        else 0
                    }
                    items(ordered, key = { it.assetId }) { face ->
                        val known = library.firstOrNull { it.assetId == face.assetId }
                        val named = names[face.assetId]
                        val label = faceLabel(
                            face.assetId, library, names, HuaweiText.facesUnknownFace[lang],
                        ) + when {
                            known != null -> ""
                            named != null -> " — ${HuaweiText.facesKeep[lang]}"
                            else -> ""
                        }
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = face },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = chosen?.assetId == face.assetId,
                                onClick = { chosen = face },
                            )
                            Text(
                                label + if (face.showing) " ●" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { chosen?.let(onResolve) }, enabled = chosen != null) {
                Text(HuaweiText.facesFullConfirm[lang])
            }
        },
        dismissButton = {
            OutlinedButton(onClick = { onResolve(null) }) { Text(HuaweiText.facesCancel[lang]) }
        },
    )
}

/**
 * What the band is holding, and how much room is left.
 *
 * Reading it is a button rather than something the window does on opening. The band serves one
 * connection: a read that fired automatically would seize it for several seconds every time this
 * window came up, including the times 白い熊 opened it only to install something.
 */
@Composable
private fun BandCard(
    state: HuaweiFacesState,
    onReadBand: () -> Unit,
    onIdentify: () -> Unit = {},
    onRemoveBandFace: (HuaweiUploadClient.InstalledFace) -> Unit = {},
) {
    val lang = LocalBandLanguage.current
    val band = state.band
    SectionCard(accent = ChartPalette.AXIS_TEXT) {
        SectionTitle(HuaweiText.facesBandTitle[lang], ChartPalette.AXIS_TEXT)
        if (band == null) {
            BodyText(HuaweiText.facesBandNever[lang])
        } else {
            // Split by what this library holds a copy of — a real distinction we can check — rather
            // than by any flag in the record claiming to say where a face came from.
            val mine = band.faces.count { f -> state.faces.any { it.assetId == f.assetId } }
            val other = band.faces.size - mine
            BodyText(
                "${band.faces.size} ${HuaweiText.facesCountUnit[lang]} · " +
                    "$mine ${HuaweiText.facesCountMine[lang]} · " +
                    "$other ${HuaweiText.facesCountOther[lang]}",
            )
            // The band's own free-space figure, in the band's own units. Reported rather than
            // converted: nothing here knows what one unit is, and inventing a megabyte would be
            // inventing a number.
            if (band.freeUnits >= 0) NoteText("${HuaweiText.facesFree[lang]} ${band.freeUnits}")
        }
        Button(
            onClick = onReadBand,
            enabled = !state.bandBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (state.reading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(HuaweiText.facesRead[lang])
            }
        }
        // Offered only once the band has been read: without a list there is nothing to name, and a
        // button that can only answer "read the band first" is worse than no button.
        if (band != null) {
            TextButton(
                onClick = onIdentify,
                enabled = !state.bandBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(HuaweiText.facesIdentify[lang]) }
        }
        // Faces on the wrist this library has no copy of — the factory ones, and anything installed
        // from elsewhere. One row each, with its own Remove.
        //
        // It used to be a single run-on line, which was fine for reading and useless for acting:
        // the only way to remove one was to start an install and wait for the band to refuse it
        // (白い熊, 2026-08-30 — "we need a way to delete them directly"). Named ones are listed too,
        // so a name given by mistake is not a face locked on the band forever.
        //
        // BELOW the buttons on purpose: there can be a dozen of these, and a list that long between
        // the counts and the actions pushes both of the things worth pressing off the card.
        val strangers = band?.faces.orEmpty()
            .filter { f -> state.faces.none { it.assetId == f.assetId } }
        if (strangers.isNotEmpty()) {
            NoteText(HuaweiText.facesUnknownOnBand[lang])
            NoteText(HuaweiText.facesStrangersNote[lang], warn = true)
            for (face in strangers) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BodyText(
                        (state.names[face.assetId] ?: face.assetId) +
                            if (face.showing) " ●" else "",
                        modifier = Modifier.weight(1f),
                    )
                    if (state.deleting == face.assetId) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(
                            onClick = { onRemoveBandFace(face) },
                            enabled = !state.bandBusy,
                            shape = RoundedCornerShape(10.dp),
                        ) { Text(HuaweiText.facesDelete[lang]) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FaceCell(
    face: HuaweiFaceLibrary.Entry,
    installing: Boolean,
    removing: Boolean,
    onBand: Boolean,
    showing: Boolean,
    bandBusy: Boolean,
    sent: Int,
    previewOf: ((HuaweiFaceLibrary.Entry) -> ByteArray?)?,
    activating: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onActivate: () -> Unit,
) {
    val lang = LocalBandLanguage.current
    val accent = if (installing) ChartPalette.HEART_RATE else ChartPalette.AXIS_TEXT
    SectionCard(accent = accent) {
        // The band's own aspect: 286 x 482. Showing the preview in the shape it will actually take
        // is the whole reason to keep a picture at all.
        Box(
            Modifier.fillMaxWidth().aspectRatio(286f / 482f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            fun decode(bytes: ByteArray?): ImageBitmap? =
                bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            val supplied = previewOf
            val image = if (supplied != null) {
                remember(face.zip.absolutePath) { decode(supplied(face)) }
            } else {
                val bmp by produceState<ImageBitmap?>(null, face.zip.absolutePath) {
                    value = withContext(Dispatchers.IO) { decode(HuaweiFaceLibrary.preview(face.zip)) }
                }
                bmp
            }
            if (image != null) {
                Image(image, face.name, Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)))
            } else {
                NoteText(HuaweiText.facesNoPreview[lang])
            }
        }
        Text(
            face.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onInstall,
            enabled = !bandBusy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            if (installing) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(HuaweiText.facesInstall[lang])
            }
        }
        // Only while THIS face is going: a byte count under an idle button would read as a stale
        // result rather than as progress.
        if (installing && sent > 0) NoteText("${sent / 1024} KB")

        // "On the band" is said in a word and a tick, never by colour alone. Removal appears only
        // for a face the band actually holds and did not ship with: offering it otherwise would be
        // offering a button that cannot work.
        if (onBand) {
            NoteText(
                if (showing) "${HuaweiText.facesOnBand[lang]} · ${HuaweiText.facesShowing[lang]}"
                else HuaweiText.facesOnBand[lang],
            )
            // On the band but not on screen: the one case where a face can be brought to the front
            // for a single command instead of another minute of transfer. Absent on the face that
            // is already showing, where it would do nothing.
            if (!showing) {
                OutlinedButton(
                    onClick = onActivate,
                    enabled = !bandBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    if (activating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(HuaweiText.facesActivate[lang])
                    }
                }
            }
            // Offered for every face the band holds. Predicting which ones it will refuse is what
            // went wrong before; the band decides, and the result is re-read from it.
            OutlinedButton(
                onClick = onRemove,
                enabled = !bandBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) {
                if (removing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(HuaweiText.facesDelete[lang])
                }
            }
        }
    }
}
