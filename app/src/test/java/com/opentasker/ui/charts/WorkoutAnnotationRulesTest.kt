package com.opentasker.ui.charts

import com.opentasker.ProductionSources
import com.opentasker.core.huawei.HuaweiWorkoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What 白い熊 writes on a workout, and where each of the three windows may show it.
 *
 * These are decisions, not mechanics: which kind owns a stop count, which corner of a calendar tile
 * carries which mark, and what one pull actually fetches. The mechanics need a device — every one of
 * them is a Composable over a Room-backed store — so what is checkable here is the rule, and the rule
 * is the half that drifted. 「重量挙げ」 and 機能訓練 are the walks window told to show something else,
 * so both arrived carrying the walks window's questions by inheritance rather than by decision.
 */
class WorkoutAnnotationRulesTest {

    /**
     * Stops are a walk's question and no other kind's.
     *
     * A lift and a rehab session have no route to stop on, so an empty stop count on their screens
     * was asking 白い熊 for an answer that does not exist (白い熊, 2026-09-11: *"remove the stops — it
     * should have only notes"*). The rule lives on the KIND rather than in each screen's `if`, so the
     * fourth window cannot inherit the question by default the way the second and third did.
     */
    @Test
    fun `only a walk is asked how many times it stopped`() {
        assertTrue("a walk stops", HuaweiWorkoutStore.Kind.WALK.countsStops)
        assertFalse("a lift has no route to stop on", HuaweiWorkoutStore.Kind.STRENGTH.countsStops)
        assertFalse("nor does a rehab session", HuaweiWorkoutStore.Kind.REHAB.countsStops)
    }

    /**
     * The walk's own screen decides by the kind's rule, not by "is this a lift".
     *
     * It read `isStrength` from the day the second window existed, which is exactly why the third one
     * came out asking about stops: a two-way branch cannot describe three kinds.
     */
    @Test
    fun `the detail card asks the kind, not the sport`() {
        val detail = ProductionSources.read("com/opentasker/ui/charts/huawei/HuaweiWalkDetail.kt")
        assertTrue(
            "the note-only card is chosen by countsStops",
            "if (!walk.kindOf.countsStops) {" in detail,
        )
        assertFalse(
            "and never again by a two-way sport test",
            "if (walk.isStrength) {" in detail,
        )
    }

    /**
     * The calendar counts SESSIONS, and says nothing when there is one.
     *
     * It carried the day's stop count for exactly one build — the stop count is the authored half of
     * a walk, so the corner the eye already goes to looked like the place for it — and 白い熊 reversed
     * it the same day (2026-09-11): a calendar answers which days and how many, and how many times a
     * walk stopped belongs on the walk. A badge reading "1" on every filled day would say nothing on
     * any of them, so one is not drawn.
     */
    @Test
    fun `the calendar badge counts sessions and only above one`() {
        val badge = ProductionSources.block(
            "com/opentasker/ui/charts/huawei/HuaweiWorkoutCalendar.kt",
            "private fun badgeFor(",
            "/**\n * Which of the day's sessions did you mean?",
        )
        assertTrue("the count is of the day's sessions", "onThatDay.size.takeIf { it > 1 }" in badge)
        assertFalse("stops belong on the walk, not on the calendar", "stops" in badge)
    }

    /**
     * Stops left, note right — in the calendar tile and in the grid cell alike.
     *
     * 白い熊 asked for that order in the calendar (2026-09-11). The grid cell follows it because the
     * two are views of the same walk: a row that answered the same two questions the other way round
     * would have to be read twice.
     */
    @Test
    fun `the two annotation marks keep one order everywhere`() {
        val grid = ProductionSources.read("com/opentasker/ui/charts/DayGrid.kt")
        assertTrue("the badge is the left mark", "TileBadge(cell.badge, skin.ink)" in grid)
        assertTrue("the note is the right mark", "TileNote(cell.hasNote, skin)" in grid)
        // Overlaid on the numeral's own line, never stacked above it: a marks strip of their own
        // costs the date the height that makes it readable (白い熊, 2026-09-11 — "make the date text
        // bigger, not on a separate line").
        assertTrue("the badge overlays the top-left corner", "Alignment.TopStart).padding(1.dp)" in grid)
        assertTrue("the note overlays the top-right corner", "Alignment.TopEnd).padding(1.dp)" in grid)
        // Inside the tile, never under it: the strip below carries the load bar and nothing else.
        val strip = ProductionSources.block(
            "com/opentasker/ui/charts/DayGrid.kt",
            "// The strip under the tile",
            "// The width is reserved either way",
        )
        assertFalse("the note no longer lives under the tile", "hasNote" in strip)
    }

    /**
     * The words above the button have to say what the button does.
     *
     * Every window has always fetched all three kinds — the band is asked for a stretch of time and
     * has no sport filter to send — and every window said only what it kept, which is what made
     * 白い熊 ask whether lifting was pulling rehab as well (2026-09-11). It was.
     */
    @Test
    fun `every window says that one pull fetches all three kinds`() {
        val screen = ProductionSources.read("com/opentasker/ui/charts/huawei/HuaweiWalksScreen.kt")
        assertTrue(
            "the sentence is on the card that carries the button, for every kind",
            "NoteText(HuaweiText.pullAllKinds[lang])" in screen,
        )
        val text = ProductionSources.read("com/opentasker/ui/charts/huawei/HuaweiText.kt")
        val english = ProductionSources.block(
            "com/opentasker/ui/charts/huawei/HuaweiText.kt",
            "val pullAllKinds = Loc(",
            "val pullAlso",
        )
        listOf("walks", "lifting", "機能訓練").forEach { kind ->
            assertTrue("the sentence has to name $kind", kind in english)
        }
        assertTrue("and the report names what the other windows got", "val pullAlso" in text)
    }

    /**
     * Every calendar can be written on, including on the days it has nothing to show.
     *
     * A day with no session is the day most worth a sentence — why there was none — and it was the
     * one day in the calendar that could hold nothing (白い熊, 2026-09-11). 機能訓練 opens its tick
     * first, because a day done without the band still has to be markable and its note hangs off
     * that dialog; the other two have nothing to tick, so the tap IS the note.
     */
    @Test
    fun `an empty day is writable on every kind`() {
        val activity = ProductionSources.read("com/opentasker/ui/charts/huawei/HuaweiWalksActivity.kt")
        assertTrue(
            "a non-rehab empty day opens the note editor",
            "if (kind == HuaweiWorkoutStore.Kind.REHAB) tickDay = key" in activity &&
                "else notingDay = key" in activity,
        )
        assertTrue("and the editor is actually shown", "notingDay?.let { key ->" in activity)
        // The rehab tick dialog's note pill was wired to {} from the day it was written, so it
        // looked like a control and answered nothing.
        assertFalse("the rehab note pill must not be a no-op again", "onEditNote = {}," in activity)
    }

    /**
     * One note file per calendar, and all of them in the backup.
     *
     * All four stores are keyed `yyyyMMdd` and describe the same dates, so a shared file would have
     * "why I did not walk" overwrite "why I did not lift". And the backup names its preference files
     * one by one on purpose — a store nobody adds to that list is a store that silently does not
     * survive a restore, which is what happened to `shutdown_settings`.
     */
    @Test
    fun `each kind writes its own day-note file, and every file travels`() {
        val map = ProductionSources.block(
            "com/opentasker/ui/charts/huawei/HuaweiWorkoutCalendar.kt",
            "internal fun dayNotesFor(",
            "/**\n * The mark in a tile's corner",
        )
        listOf("DayNotes.WALK", "DayNotes.LIFT", "DayNotes.REHAB").forEach { bucket ->
            assertTrue("$bucket has to be reachable from its kind", bucket in map)
        }
        val notes = ProductionSources.read("com/opentasker/core/band/DayNotes.kt")
        val files = Regex("""DayNotes\("([a-z_]+)"\)""").findAll(notes).map { it.groupValues[1] }.toList()
        assertEquals("one file per note kind, none shared", files.size, files.distinct().size)
        val backup = ProductionSources.read("com/opentasker/core/transfer/SettingsBackup.kt")
        files.forEach { file ->
            assertTrue("$file must travel in a backup", "\"$file\"" in backup)
        }
    }

    /** Three kinds, so a rule written as a two-way branch is a rule with a hole in it. */
    @Test
    fun `there are exactly three kinds`() {
        assertEquals(3, HuaweiWorkoutStore.Kind.entries.size)
    }
}
