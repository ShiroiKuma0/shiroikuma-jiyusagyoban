package com.opentasker.core.huawei

import com.opentasker.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The forecast deadline, and the fact that nobody was ever told it.
 *
 * 2026-09-21: 白い熊 walked five hours after the last block of the set on their band, on a phone
 * that held every record needed to know better — the window in a variable, the reason the rebuild
 * had failed in another, and `built-log.txt` beside them. The band's own screen said eighteen hours
 * left. What was missing was not knowledge; it was anyone saying it out loud, before the walk.
 */
class GnssForecastReminderTest {

    /** 2026-09-21 11:59 UTC — the real window end, from `%HUAWEI_PgnssWindow` that evening. */
    private val until = 1789991940000L

    @Test
    fun `the warning comes half a day before the forecast ends`() {
        assertEquals(12 * 60 * 60 * 1000L, GnssForecastReminder.WARN_BEFORE_MS)
        val warnAt = until - GnssForecastReminder.WARN_BEFORE_MS
        assertTrue("the warning must precede the end", warnAt < until)
        assertEquals("2026-09-20 23:59", GnssForecastReminder.utc(warnAt))
        assertEquals("2026-09-21 11:59", GnssForecastReminder.utc(until))
    }

    /**
     * THE BAND COUNTS 72 HOURS FROM RECEIPT — not from our window, and not our last block + 24 h.
     *
     * Measured exactly on 2026-09-21: the band took a set at **19:52 UTC** and its screen read
     * **2 d 23 h** at 20:11, which is 19:52 + 72 h to the minute. The same evening's earlier
     * readings — 18 h and 16 h against a set whose last block was 11:59 — looked like "our last
     * block plus a day" and are the same rule: that set had been built on the 18th and reached the
     * band on the 19th, so counting from receipt put its expiry a day past the data's.
     *
     * **The band over-claims by exactly the delay between building a set and handing it over.**
     */
    @Test
    fun `the band counts its own 72 hours from the moment it took the set`() {
        assertEquals(72, GnssForecastReminder.BAND_WINDOW_HOURS)
        // 2026-09-21 19:52 UTC, the transfer that produced the reading above.
        val received = 1790020320000L
        val at2011 = 1790021460000L
        val claims = received + GnssForecastReminder.BAND_WINDOW_HOURS * 3_600_000L
        assertEquals("残り 2 日 23 時間 / 2 d 23 h left", GnssForecastReminder.leftLabel(claims, at2011))
        // …against what the data itself covers, which is the number our own panel must show.
        assertEquals("残り 2 日 19 時間 / 2 d 19 h left", GnssForecastReminder.leftLabel(1790265540000L, at2011))
    }

    /**
     * Both reminders have to be DECLARED or the alarm is delivered to nothing.
     *
     * The same failure mode as `RUN_TASK`'s missing declaration: no compile error, no warning, and
     * a silence indistinguishable from an app that chose not to care.
     */
    @Test
    fun `both reminder actions are declared in the manifest`() {
        val manifest = java.io.File(ProductionSources.repoRoot.toFile(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "the receiver itself must be declared",
            "com.opentasker.core.huawei.GnssForecastReminderReceiver" in manifest,
        )
        for (action in listOf("com.opentasker.huawei.GNSS_FORECAST_WARN", "com.opentasker.huawei.GNSS_FORECAST_END")) {
            assertTrue("$action is scheduled but not declared", "android:name=\"$action\"" in manifest)
        }
    }

    /**
     * A FAILED BUILD MUST NOT BECOME A SUCCESSFUL TRANSFER.
     *
     * The chain that produced 白い熊's day-3 walk, in four steps that each looked reasonable: the
     * build died on DNS and wrote `THE SET WAS NOT REBUILT …` into `PgnssAlert`; 「衛星 生成」 carries
     * `continueOnError` on that step, so the task walked on to the serve; the serve overwrote the
     * alert with `""` because nothing was yet recorded about the band; the transfer of the PREVIOUS
     * set succeeded and rewrote the panel to `done,done,done,done`; and the closing notification,
     * gated on exactly that string, announced 「予測暦 済」. Every link is checked here.
     */
    @Test
    fun `the serve refuses while a build failure is standing, and never erases it`() {
        val serve = ProductionSources.read("com/opentasker/core/actions/HuaweiGnssAction.kt")
        assertTrue(
            "the marker the build writes is the one the serve reads",
            "val buildFailureStanding = standingAlert.startsWith(NOT_REBUILT)" in serve,
        )
        assertTrue(
            "and a standing failure stops the hand-over outright",
            "if (buildFailureStanding && !serveStale && predictedHeld > 0) {" in serve,
        )
        assertTrue(
            "the alert is carried, never overwritten with silence",
            "buildFailureStanding -> standingAlert" in serve,
        )
        assertTrue("with a deliberate override for 衛星 再送", "args[\"serve_stale\"]" in serve)
        // The build has to keep writing the words the serve matches on.
        val build = ProductionSources.read("com/opentasker/core/actions/HuaweiPgnssAction.kt")
        assertTrue(
            "both failure paths write the shared marker",
            build.split("HuaweiGnssAction.NOT_REBUILT").size - 1 >= 2,
        )
    }

    /** A freshness bar the caller asserts, so a stale store cannot pass for a fresh build. */
    @Test
    fun `min_hours refuses a set that is too old to be the one just built`() {
        val serve = ProductionSources.read("com/opentasker/core/actions/HuaweiGnssAction.kt")
        assertTrue("the bar is read from the action's own args", "args[\"min_hours\"]" in serve)
        assertTrue(
            "and it refuses rather than warns",
            "NOT HANDED OVER — the set on the phone has \$leftHours h left, below the " in serve,
        )
        val metadata = ProductionSources.read("com/opentasker/core/actions/ActionMetadata.kt")
        for (field in listOf("min_hours", "serve_stale")) {
            assertTrue("$field has to be reachable in the editor", "ActionField(\"$field\"" in metadata)
        }
    }

    /**
     * DAYS AND HOURS, because that is the unit the band's own screen uses.
     *
     * 白い熊, 2026-09-21: *"it must be x days x hours — so it can be compared with what the band
     * itself is saying."* The first delivery said "67 hours", which cannot be held up against the
     * band's "2 days 19 hours" without arithmetic nobody does on the way out of the door.
     */
    @Test
    fun `the window is said in days and hours, both ways round`() {
        val now = until - 67 * 3_600_000L
        assertEquals("残り 2 日 19 時間 / 2 d 19 h left", GnssForecastReminder.leftLabel(until, now))
        // Under a day it stays in hours; a whole number of days drops the hours.
        assertEquals("残り 5 時間 / 5 h left", GnssForecastReminder.leftLabel(until, until - 5 * 3_600_000L))
        assertEquals("残り 3 日 / 3 d left", GnssForecastReminder.leftLabel(until, until - 72 * 3_600_000L))
        // Past the end it counts the other way, which is the fact that matters then.
        assertEquals("切れて 5 時間 / OUT for 5 h", GnssForecastReminder.leftLabel(until, until + 5 * 3_600_000L))
    }

    /** Every surface is built from one text, so the notification and the dialog cannot disagree. */
    @Test
    fun `the status names our window and what to do`() {
        val now = until - 67 * 3_600_000L
        val text = GnssForecastReminder.statusText(until, now)
        assertTrue("our own end, in UTC", "2026-09-21 11:59" in text)
        assertTrue("what is left, in days and hours", "2 日 19 時間" in text)
        assertTrue("with the remedy named", "衛星 生成" in text)

        val markup = GnssForecastReminder.statusMarkup(until, now)
        assertTrue("a heading, so the dialog is not one grey block", markup.startsWith("# "))
        assertTrue("the number carries bold", "## **残り 2 日 19 時間 / 2 d 19 h left**" in markup)
    }

    /**
     * Our own two hours back.
     *
     * A plain minimum over the six files always picked GLONASS, whose blocks are stamped an hour
     * early by the format's convention rather than because they expire sooner; and we reported the
     * stamp of the last block instead of the hour it covers. Two hours of pessimism, which is what
     * made our panel read 2 d 18 h against the band's 2 d 23 h (白い熊, 2026-09-21).
     */
    @Test
    fun `the window we report counts the hour each block covers, GLONASS included`() {
        val serve = ProductionSources.read("com/opentasker/core/actions/HuaweiGnssAction.kt")
        assertTrue(
            "the minimum is taken over comparable numbers",
            "val last = coveredUntil(name, lastBlockSeconds(files[name]!!) ?: continue)" in serve,
        )
        assertTrue("GLONASS's early stamp is added back", "GLONASS_STAMP_OFFSET_SEC else 0L" in serve)
        assertTrue("and a block covers the hour after it", "BLOCK_COVERS_SEC" in serve)
        assertTrue(
            "every satellite time is printed in UTC, through the one helper that sets the zone",
            "ctx.variables.set(\"\${prefix}GnssPredUntil\", if (windowEnd == 0L) \"\" else stamp(unixOf(windowEnd)))" in serve,
        )
    }

    /** A notification nobody can open is a dead end at the moment someone wants to know more. */
    @Test
    fun `tapping the notification opens the full dialog`() {
        val src = ProductionSources.read("com/opentasker/core/huawei/GnssForecastReminder.kt")
        assertTrue("there is a content intent at all", "builder.setContentIntent(" in src)
        assertTrue("and it opens a text dialog", "DialogActivity.TYPE_TEXT" in src)
        for (loud in listOf("EXTRA_MARKUP", "EXTRA_SIZE", "EXTRA_TEXT_SCALE")) {
            assertTrue("$loud — big and in colour, not a grey paragraph", loud in src)
        }
        assertTrue("the collapsed line is the punchline", "text.lineSequence().first()" in src)
    }

    /** The transfer that hands the band a forecast is the moment its deadline becomes knowable. */
    @Test
    fun `accepting a forecast arms the reminder, and a failed build says so`() {
        val serve = ProductionSources.read("com/opentasker/core/actions/HuaweiGnssAction.kt")
        assertTrue(
            "arming belongs with writeBandHolds — the one place that learns the window",
            "GnssForecastReminder.arm(ctx.app, unixOf(windowEnd), receivedAt)" in serve,
        )
        val build = ProductionSources.read("com/opentasker/core/actions/HuaweiPgnssAction.kt")
        assertTrue(
            "a build that produced nothing has to be loud, not only banner-deep",
            "GnssForecastReminder.buildFailed(ctx.app, why)" in build,
        )
    }
}
