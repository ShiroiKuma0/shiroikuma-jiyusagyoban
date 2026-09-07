package com.opentasker.ui.charts.huawei

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.opentasker.core.huawei.maps.WalkPlot
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.core.huawei.maps.WalkTrack

/**
 * One walk's map, full screen and pinchable.
 *
 * ## Why a viewer at all
 *
 * The cell is 4:3 and about a thousand pixels wide, which is enough to see THAT a walk went along
 * the river and not enough to see which side of it. 白い熊 asked for the map to be click-zoomable
 * (2026-09-06); this is that click.
 *
 * ## What it can and cannot show
 *
 * The picture underneath is a fixed number of pixels, so zooming magnifies rather than reveals.
 * Opening the viewer therefore asks 地図 for a **sharper cutout of the same frame**
 * ([com.opentasker.core.huawei.maps.MapCutouts.detailed]) — one zoom level finer, which is what a
 * 3072 px budget buys — and the route redraws on it when it lands. Past roughly 2× the map softens,
 * and the pinch still runs to [MAX_ZOOM] because the ROUTE is drawn as vector and stays crisp all
 * the way in: 白い熊 chose to keep going rather than have the gesture stop dead, since a walk that
 * doubles back on itself is only legible close up.
 *
 * The gesture is transient by design. Nothing about the zoom is remembered — closing and reopening
 * returns to the walk's own framing — because the framing is the answer to "where did I go" and a
 * remembered corner of it is not.
 */
object WalkMapViewer {

    /** As far in as the pinch goes. Beyond this the map is mush and even the route stops helping. */
    const val MAX_ZOOM = 4f

    /**
     * The zoomable map, as a full-screen dialog.
     *
     * [base] is whatever picture is available right now — the cell's cutout at first, and the
     * sharper one once it has been fetched and the caller has decoded it. Swapping it mid-gesture is
     * safe: both are the same geography under the same transform, so only the sharpness changes.
     */
    @Composable
    fun Show(
        plot: WalkPlot,
        base: ImageBitmap?,
        /** True while 地図 is drawing the sharper picture, so the wait is visible rather than mysterious. */
        fetching: Boolean,
        onClose: () -> Unit,
    ) {
        val lang = LocalBandLanguage.current
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            var zoom by remember { mutableFloatStateOf(1f) }
            var pan by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier
                    .fillMaxSize()
                    // Opaque, and black rather than the surface colour: a map is the only thing
                    // worth looking at here, and every pixel of chrome around it is one the map
                    // does not get.
                    .background(Color.Black),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, panChange, zoomChange, _ ->
                                val next = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                // Pan in the zoomed frame, then clamp so the picture cannot be
                                // thrown off the screen entirely — at 1× there is nowhere to go,
                                // and the walk always ends up back in view.
                                val slackX = size.width * (next - 1f) / 2f
                                val slackY = size.height * (next - 1f) / 2f
                                val moved = pan + panChange
                                zoom = next
                                pan = Offset(
                                    moved.x.coerceIn(-slackX, slackX),
                                    moved.y.coerceIn(-slackY, slackY),
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                // Double tap toggles all the way in and all the way out. Pinching
                                // to a precise scale on a phone held in one hand is a chore, and
                                // "back to the whole walk" is the thing wanted most often.
                                onDoubleTap = { at ->
                                    if (zoom > 1f) {
                                        zoom = 1f
                                        pan = Offset.Zero
                                    } else {
                                        zoom = MAX_ZOOM
                                        val slackX = size.width * (MAX_ZOOM - 1f) / 2f
                                        val slackY = size.height * (MAX_ZOOM - 1f) / 2f
                                        // Put what was tapped under the finger, as far as the
                                        // clamp allows.
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        pan = Offset(
                                            ((cx - at.x) * MAX_ZOOM).coerceIn(-slackX, slackX),
                                            ((cy - at.y) * MAX_ZOOM).coerceIn(-slackY, slackY),
                                        )
                                    }
                                },
                            )
                        },
                ) {
                    plot.cutout?.let { cutout ->
                        WalkMap.Route(
                            cutout = cutout,
                            // Far more of the track than a cell needs: this is the surface where
                            // the difference between a corner turned and a corner cut is visible.
                            points = remember(plot) { WalkTrack.thin(plot.points, 4000) },
                            base = base,
                            userZoom = zoom,
                            userPan = pan,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    TextButton(onClick = onClose) { Text(HuaweiText.walksZoomClose[lang]) }
                    if (fetching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                Text(
                    if (fetching) {
                        HuaweiText.walksZoomFetching[lang]
                    } else {
                        "${HuaweiText.walksZoomHint[lang]}  ·  ${"%.1f".format(zoom)}×"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }
    }
}
