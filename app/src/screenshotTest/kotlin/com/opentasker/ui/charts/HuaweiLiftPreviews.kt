package com.opentasker.ui.charts

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.opentasker.core.huawei.HuaweiWorkoutStore
import com.opentasker.core.huawei.HuaweiWorkout
import com.opentasker.ui.charts.huawei.HuaweiWalkDetailScreen
import com.opentasker.ui.charts.huawei.HuaweiWalksScreen
import com.opentasker.ui.charts.huawei.HuaweiWorkoutCalendarScreen
import com.opentasker.ui.charts.huawei.HuaweiWalksState
import com.opentasker.ui.theme.OpenTaskerTheme
import java.io.File

/**
 * 「重量挙げ」 and the effort card, rendered — with the band's own numbers, not invented ones.
 *
 * The series below are the real thing: 白い熊's strength session of 2026-09-03 (workout 20, 43
 * minutes, 217 kcal, 516 readings at five seconds) and the walk of 2026-08-23 (workout 8), both
 * read off the band by the probe and decoded by `HuaweiWorkout.parseSamples`. A preview drawn from
 * a sine wave would show a heart-rate trace that behaves; these show one that spikes to 134 during
 * a set and falls to 86 between, which is the shape the card actually has to hold.
 *
 * The lifting grid has no map frame — a lift has no route — so the 4:3 box every cell reserves
 * holds the heart-rate trace instead. That is what these are for: checking that a cell built for a
 * picture still reads as a cell when what fills it is a line.
 *
 * **`@PreviewTest` is not optional** — the engine discovers that annotation, not `@Preview`.
 */
private object BandSeries {
    const val LIFT_HR =
        "0,105,105,106,109,114,124,129,99,103,104,111,115,121,127,123,112,107,115,113,112,112," +
        "109,110,110,111,116,112,114,117,117,117,116,114,112,113,108,107,109,108,110,108,104,105," +
        "108,108,106,108,90,86,88,96,106,109,112,107,107,107,108,108,107,114,113,111,108,111,111," +
        "108,106,108,106,107,110,111,113,109,111,117,118,118,118,117,116,113,110,110,107,105,106," +
        "104,104,103,102,102,104,104,103,98,95,96,93,98,100,98,98,96,96,97,92,90,89,91,91,92,91," +
        "93,93,92,93,95,97,96,96,97,98,99,97,102,101,104,102,91,95,95,101,101,103,103,101,104," +
        "111,107,101,105,106,107,105,106,106,106,109,113,116,111,115,117,115,112,109,107,104,104," +
        "105,102,99,101,100,101,100,99,99,97,97,100,100,100,97,96,95,94,91,92,94,94,95,101,99," +
        "100,101,102,100,100,98,94,98,98,98,97,102,102,106,109,107,100,95,103,112,108,115,108," +
        "110,109,107,110,112,106,104,107,109,113,115,116,115,115,105,103,105,108,105,106,113,115," +
        "116,115,113,110,112,116,113,110,115,116,114,116,118,116,118,119,119,116,116,117,115,117," +
        "119,114,107,113,117,115,116,116,113,116,115,112,109,110,112,114,112,114,116,115,108,100," +
        "99,100,100,105,113,115,111,109,110,109,112,112,113,113,110,104,108,111,116,117,116,115," +
        "114,112,104,104,106,106,109,106,104,105,105,102,105,109,109,107,109,109,107,103,105,104," +
        "105,104,106,106,108,113,110,109,114,113,114,113,112,115,112,114,112,112,104,107,109,107," +
        "112,110,101,101,104,107,105,101,105,107,105,109,108,107,103,107,102,101,98,108,110,110," +
        "110,112,112,108,111,109,112,109,108,111,116,115,119,119,116,114,113,123,122,122,122,128," +
        "131,131,134,129,130,130,125,127,128,126,125,122,132,129,127,127,128,126,125,125,125,122," +
        "123,125,128,125,125,124,120,119,119,123,120,119,121,124,123,120,117,119,122,123,124,124," +
        "125,124,120,119,118,117,115,114,107,109,109,109,113,116,115,114,112,113,113,108,110,113," +
        "112,116,115,115,120,114,130,129,130,128,129,126,125,121,118,115,118,117,114,113,113,110," +
        "109,106,106,106,109,108,106,104,105,104,102,102,103,101,102,103,104,105,104,106,106,107," +
        "107,108,108,107,109,109,109,114,117,118,120,120,116,116,118,119,120,118,116,116"

    const val LIFT_RECOVERY =
        "116,116,114,115,114,112,110,108,107,106,105,105,104,104,102,100,101,99,99,98,100,99,98," +
        "98,96"

    const val WALK_HR =
        "0,91,92,92,94,95,93,93,92,93,93,94,95,93,93,93,94,95,96,95,95,95,95,97,99,100,102,100," +
        "100,100,99,98,98,98,99,100,100,100,102,101,101,103,100,99,96,98,98,99,101,103,104,105," +
        "105,106,105,103,103,103,104,105,105,105,104,104,103,102,99,98,98,97,100,102,104,105,107," +
        "107,105,105,106,105,107,108,107,106,108,106,106,102,102,102,102,102,103,101,102,102,104," +
        "105,106,105,106,107,111,112,111,109,107,107,107,108,108,108,107,109,109,109,110,113,114," +
        "110,109,109,109,108,108,109,109,109,109,109,108,109,108,108,109,111,111,111,111,112,110," +
        "109,110,110,110,108,109,110,108,107,108,107,107,106,106,106,107,108,109,108,107,106,106," +
        "107,107,107,107,109,112,109,109,109,111,111,110,108,109,109,109,110,108,108,107,111,110," +
        "108,107,107,107,107,106,108,110,110,110,112,113,113,112,113,110,118,121,120,123,122,119," +
        "119,117,115,114,113,115,113,112,112,113,111,112,112,113,110,110,111,110,110,111,111,111," +
        "113,111,111,112,112,113,112,113,113,113,113,113,113,113,112,112,113,113,112,111,112,112," +
        "114,114,113,112,112,113,111,113,111,111,110,111,110,108,109,107,107,109,109,108,111,112," +
        "112,110,111,112,113,110,110,110,110,112,112,110,109,109,110,110,110,109,110,111,114,113," +
        "112,111,111,110,109,106,104,103,102,102,104,105,109,109,109,110,110,110,111,110,111,110," +
        "110,108,109,110,109,108,108,108,110,111,111,112,112,112,110,112,113,111,110,109,114,119," +
        "121,121,122,122,122,123,123,123,123,125,123,122,123,120"

    const val WALK_SPEED =
        "0,0,0,0,0,0,0,0,0,0,0,0,7,8,8,8,8,9,9,10,10,10,8,9,10,13,13,13,12,12,13,13,13,12,12,13," +
        "14,14,14,14,14,13,12,0,0,13,11,11,11,12,12,12,12,12,12,12,12,12,13,13,13,13,12,11,0,0,0," +
        "0,10,16,18,18,19,19,18,17,16,16,15,15,15,15,15,15,15,15,15,14,0,8,16,16,16,15,15,14,14," +
        "15,15,15,15,16,15,16,16,16,15,15,15,15,15,14,15,15,15,15,14,14,14,14,14,14,14,14,14,14," +
        "14,14,14,14,14,14,13,14,14,14,14,14,14,14,14,13,14,13,14,14,15,15,14,14,14,13,13,13,14," +
        "14,15,14,15,15,14,14,14,14,13,13,12,12,13,14,14,14,13,12,13,14,13,13,14,14,14,14,14,14," +
        "14,14,15,15,15,14,14,14,14,14,14,14,14,14,14,14,14,15,15,15,15,15,15,15,16,16,16,15,15," +
        "15,16,15,15,15,14,14,14,14,15,16,16,15,15,16,15,15,14,15,15,15,15,16,16,16,16,15,15,15," +
        "15,15,15,15,15,15,15,14,15,15,14,15,15,14,14,14,14,15,14,14,14,14,15,14,14,14,14,14,14," +
        "15,14,15,14,14,14,14,14,14,14,14,14,14,14,15,16,15,14,15,14,14,14,14,15,15,15,15,13,0,0," +
        "8,11,16,16,15,16,15,15,14,15,15,15,14,15,14,14,13,0,0,13,13,16,15,14,15,15,15,15,15,14," +
        "14,14,14,14,14,0,10,11,11,11,12,12,12,12,12,12,13,13,0,6,0,0"

    const val WALK_STEPS =
        "0,0,0,0,9,8,0,0,0,0,0,0,0,8,8,8,8,8,8,8,8,8,4,6,8,9,9,9,9,8,9,9,9,8,8,8,9,9,9,9,9,9,8,8," +
        "0,8,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,0,0,0,0,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9," +
        "0,8,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,10,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9," +
        "9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,8,8,8,8,8,9,9,9,9,8,8," +
        "9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9," +
        "9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9," +
        "9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,8,8,8,9,9,10,9,9,9,9,9,9,9,9,9,9,9,8,0,0,7,9,9,9,9," +
        "9,9,9,9,9,9,9,9,9,9,8,9,0,0,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,9,0,8,9,9,9,9,9,9,9,9,9,9,9,0," +
        "7,0,0"

    const val WALK_RECOVERY =
        "119,119,118,117,118,116,117,117,115,114,115,115,115,115,115,116,117,117,115,119,120,119," +
        "117,115,112"

    fun ints(s: String) = s.split(',').map { it.toInt() }
}

/** The strength session as the band recorded it, straight from the capture. */
private val LIFT = HuaweiWorkoutStore.Workout(
    number = 20,
    startSeconds = 1_788_451_380L,
    endSeconds = 1_788_453_965L,
    distanceMetres = 0,
    steps = 0,
    calories = 217,
    kind = "strength",
    sportType = HuaweiWorkout.STRENGTH,
    intervalSeconds = 5,
    sampleCount = 516,
    recovery = BandSeries.ints(BandSeries.LIFT_RECOVERY),
    trackPoints = 0,
)

/**
 * The heart rate travels BESIDE the workout, exactly as it does on the real screen.
 *
 * A row holds the scalars and the sample blocks are blobs; the window decodes them once when the
 * list loads and hands each screen its own. A preview that reached for `walk.effort` would be
 * previewing a shape the app does not have.
 */
private val LIFT_EFFORT = HuaweiWorkoutStore.Effort(
    intervalSeconds = 5,
    heart = BandSeries.ints(BandSeries.LIFT_HR),
    recovery = BandSeries.ints(BandSeries.LIFT_RECOVERY),
)

/**
 * A second and a third, so the grid is a grid.
 *
 * Only one lifting session exists so far, so these are that one moved and re-costed rather than
 * invented — the trace is the same because it is the only real one there is, and what the preview
 * has to show is three cells of equal height, not three different workouts.
 */
private val LIFTS = listOf(
    LIFT.copy(note = "legs — the last set went badly", stops = null),
    LIFT.copy(
        number = 17,
        startSeconds = 1_788_190_000L,
        endSeconds = 1_788_192_100L,
        calories = 168,
    ),
    LIFT.copy(
        number = 15,
        startSeconds = 1_787_980_000L,
        endSeconds = 1_787_982_800L,
        calories = 191,
        recovery = emptyList(),
    ),
)

private val LIFT_EFFORTS = LIFTS.associate { it.id to LIFT_EFFORT }

/** The walk of 2026-08-23, now carrying the stream and the splits it always had on the band. */
private val WALK_WITH_EFFORT = HuaweiWorkoutStore.Workout(
    number = 8,
    startSeconds = 1_787_502_496L,
    endSeconds = 1_787_504_261L,
    distanceMetres = 2_270,
    steps = 2_892,
    calories = 135,
    kind = "walk",
    sportType = 2,
    intervalSeconds = 5,
    sampleCount = 353,
    recovery = BandSeries.ints(BandSeries.WALK_RECOVERY),
    splits = listOf(
        HuaweiWorkout.Split(1, mile = false, seconds = 821, cumulativeSeconds = 820),
        HuaweiWorkout.Split(2, mile = false, seconds = 717, cumulativeSeconds = 1_536),
        HuaweiWorkout.Split(
            3, mile = false, seconds = 840, cumulativeSeconds = 1_764,
            partialDecimetres = 2_700,
        ),
    ),
    trackPoints = 1_763,
    note = "stopped at the bakery, then again on the bridge to watch the boats",
    stops = 2,
)

private val WALK_EFFORT = HuaweiWorkoutStore.Effort(
    intervalSeconds = 5,
    heart = BandSeries.ints(BandSeries.WALK_HR),
    speedDmS = BandSeries.ints(BandSeries.WALK_SPEED),
    stepsPerInterval = BandSeries.ints(BandSeries.WALK_STEPS),
    recovery = BandSeries.ints(BandSeries.WALK_RECOVERY),
    splits = WALK_WITH_EFFORT.splits,
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    OpenTaskerTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/** 「重量挙げ」 itself: the walks grid told it is showing lifts, so no map frame anywhere. */
@PreviewTest
@Preview(name = "Lifting — grid", widthDp = 413, heightDp = 900, fontScale = 1.3f, showBackground = true)
@Composable
fun HuaweiLiftGridPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame {
            HuaweiWalksScreen(
                state = HuaweiWalksState(
                    walks = LIFTS, efforts = LIFT_EFFORTS, loading = false, kind = HuaweiWorkoutStore.Kind.STRENGTH,
                ),
                contentPadding = PaddingValues(10.dp),
                onDownload = {},
                onShare = {},
                onOpenInChizu = {},
                onOpen = {},
            )
        }
    }
}

/**
 * 「機能訓練」 — the band's Free exercise, which is what 白い熊 records rehab under.
 *
 * Built from the same window as the walks and the lifts, told which third of the library it shows.
 * The session is real: workout 23 of 2026-09-04, twenty minutes, 64 kcal, a heart rate between 82
 * and 113. What this checks is that the third mode reads as its own screen rather than as a lifting
 * screen with the title changed — and that the calendar pill, which is now the only way to the
 * record of which days were done, is on it.
 */
private val REHAB = HuaweiWorkoutStore.Workout(
    number = 23,
    startSeconds = 1_788_505_560L,
    endSeconds = 1_788_506_768L,
    distanceMetres = 0,
    steps = 0,
    calories = 64,
    kind = "rehab",
    sportType = HuaweiWorkout.FREE_EXERCISE,
    intervalSeconds = 5,
    sampleCount = 241,
    recovery = BandSeries.ints(BandSeries.LIFT_RECOVERY),
    trackPoints = 0,
)

@PreviewTest
@Preview(name = "Rehab — grid", widthDp = 413, heightDp = 900, fontScale = 1.3f, showBackground = true)
@Composable
fun HuaweiRehabGridPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame {
            HuaweiWalksScreen(
                state = HuaweiWalksState(
                    walks = listOf(
                        REHAB,
                        // A second session, and one with no heart rate — the state the empty frame
                        // exists for, which must not read as a missing MAP on a kind that has none.
                        REHAB.copy(
                            number = 19,
                            startSeconds = 1_788_330_000L,
                            endSeconds = 1_788_331_400L,
                            calories = 71,
                        ),
                    ),
                    efforts = mapOf(REHAB.id to LIFT_EFFORT),
                    loading = false,
                    kind = HuaweiWorkoutStore.Kind.REHAB,
                ),
                contentPadding = PaddingValues(10.dp),
                onDownload = {},
                onShare = {},
                onOpenInChizu = {},
                onOpen = {},
            )
        }
    }
}

/**
 * The calendar, on the kind whose colour is not the one it was built for.
 *
 * 機能訓練 had this page first and it was yellow throughout. Walking gets the same page in blue, and
 * what this checks is that the tiles still read — a filled day against `REST_FILL` — when the bright
 * colour is not the annotation ink the grid was drawn against.
 */
@PreviewTest
@Preview(name = "Calendar — walks", widthDp = 413, heightDp = 900, showBackground = true)
@Composable
fun HuaweiWalkCalendarPreview() {
    val zone = java.time.ZoneId.of("Europe/Prague")
    val day = 24L * 3600
    val base = 1_788_000_000L
    val walks = (0..9).map { i ->
        WALK_WITH_EFFORT.copy(
            number = 30 - i,
            startSeconds = base - i * day * (if (i % 3 == 0) 2 else 1),
        )
        // Two on one day, which is 白い熊's ordinary Saturday: the tile has to offer both rather
        // than quietly keep whichever the map happened to hold.
    } + WALK_WITH_EFFORT.copy(number = 31, startSeconds = base + 11 * 3600)
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame {
            HuaweiWorkoutCalendarScreen(
                kind = HuaweiWorkoutStore.Kind.WALK,
                workouts = walks,
                ticked = emptySet(),
                notes = emptyMap(),
                zone = zone,
                contentPadding = PaddingValues(0.dp),
                onOpenSession = {},
                onTapEmptyDay = {},
                onBack = {},
            )
        }
    }
}

/** Nothing recorded yet — and the copy has to say where a lift comes from, since it is not here. */
@PreviewTest
@Preview(name = "Lifting — empty", widthDp = 413, heightDp = 560, showBackground = true)
@Composable
fun HuaweiLiftEmptyPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame {
            HuaweiWalksScreen(
                state = HuaweiWalksState(walks = emptyList(), loading = false, kind = HuaweiWorkoutStore.Kind.STRENGTH),
                contentPadding = PaddingValues(10.dp),
                onDownload = {},
                onShare = {},
                onOpenInChizu = {},
                onOpen = {},
            )
        }
    }
}

/**
 * One session opened: the whole point of the feature.
 *
 * A lift's detail has no map, no 地図 button and no track files, and what is left has to still read
 * as a full screen rather than as a walk with holes in it.
 */
@PreviewTest
@Preview(name = "Lifting — one session", widthDp = 413, heightDp = 1000, fontScale = 1.3f, showBackground = true)
@Composable
fun HuaweiLiftDetailPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.JA) {
        Frame {
            HuaweiWalkDetailScreen(
                walk = LIFT.copy(note = "脚。最後の一組がだめだった"),
                effort = LIFT_EFFORT,
                sharing = false,
                busy = false,
                message = null,
                contentPadding = PaddingValues(10.dp),
                onShare = {},
                onOpenInChizu = {},
                onBack = {},
            )
        }
    }
}

/**
 * The same session with nothing written on it — the state every lift starts in.
 *
 * What is being checked is that the empty note reads as an invitation to type rather than as a
 * control that has failed to load: the pill keeps its border at the "add" alpha and the placeholder
 * sits inside it, so tapping anywhere in it puts the caret where the words will go.
 */
@PreviewTest
@Preview(name = "Lifting — no note yet", widthDp = 413, heightDp = 1000, showBackground = true)
@Composable
fun HuaweiLiftEmptyNotePreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame {
            HuaweiWalkDetailScreen(
                walk = LIFT,
                effort = LIFT_EFFORT,
                sharing = false,
                busy = false,
                message = null,
                contentPadding = PaddingValues(10.dp),
                onShare = {},
                onOpenInChizu = {},
                onBack = {},
            )
        }
    }
}

/** And the walk, which now carries the same card plus the splits the band had all along. */
@PreviewTest
@Preview(name = "Walk — effort card", widthDp = 413, heightDp = 1100, showBackground = true)
@Composable
fun HuaweiWalkEffortPreview() {
    CompositionLocalProvider(LocalBandLanguage provides BandLanguage.EN) {
        Frame {
            HuaweiWalkDetailScreen(
                walk = WALK_WITH_EFFORT,
                effort = WALK_EFFORT,
                sharing = false,
                busy = false,
                message = null,
                contentPadding = PaddingValues(10.dp),
                onShare = {},
                onOpenInChizu = {},
                onBack = {},
            )
        }
    }
}
