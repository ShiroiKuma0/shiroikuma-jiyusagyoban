package com.opentasker.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.chrono.JapaneseDate
import java.time.chrono.JapaneseEra
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * The month a list of days has reached, as a heading.
 *
 * Every day-shaped surface on the 健康 screens — the register's calendar, its night table, the
 * day-by-day table — runs long enough that a reader scrolling back loses the month. A date on every
 * line does not fix that: `2026-08-18` on forty lines is forty dates, and the eye reads none of
 * them. A rule with the month written on it is one object per month, which is what orientation
 * actually needs. (白い熊, 2026-08-18: "it will help orientation when scrolling back the days".)
 *
 * Because the heading carries the year, the lines under it no longer have to — see the shortened
 * date columns on the narrow layouts.
 *
 * ## Japanese: the imperial year, in kanji numerals
 *
 * `令和八年 八月`, never `2026年 8月`. This is the same rendering the sister calendar fork
 * (`shiroikuma-yotehyo`, `DayBoxHeaderFormatter.kt`) produces for its week-view headers, so the two
 * apps name a month identically.
 *
 * It is reached by a different road, though, and deliberately. yotehyo goes through ICU
 * (`ULocale("ja_JP@calendar=japanese")` for the era, `numbers=jpan` for the numerals) because it is
 * formatting whole dates and wants ICU's algorithmic numbering. Here the whole problem is two small
 * integers — an era year and a month — so it is `java.time.chrono.JapaneseDate` for the era and a
 * dozen lines of kanji numerals for the digits. That keeps this file free of `android.icu`, which
 * means it runs unchanged in the JVM unit tests and in the offline Compose previews; an ICU call
 * would run in neither.
 */
object BandMonths {

    /** `一` … `九十九`. Enough for an era year and a month, which is all this file counts. */
    fun kanjiNumber(n: Int): String {
        if (n <= 0 || n >= 100) return n.toString()
        val digits = "〇一二三四五六七八九"
        val tens = n / 10
        val unit = n % 10
        return when {
            tens == 0 -> digits[unit].toString()
            tens == 1 -> "十" + if (unit == 0) "" else digits[unit].toString()
            else -> digits[tens].toString() + "十" + if (unit == 0) "" else digits[unit].toString()
        }
    }

    /**
     * `令和八年` / `2026`.
     *
     * The first year of an era is written 元年 and not 一年 — a convention, not an off-by-one, and
     * the only place a plain kanji numeral would be wrong.
     *
     * The era comes from [JapaneseDate], which knows the actual accession dates; the fallback is
     * reached only if a platform has no Japanese chronology at all, and prints the Gregorian year
     * rather than inventing an era name.
     */
    fun yearLabel(ym: YearMonth, lang: BandLanguage): String {
        if (lang == BandLanguage.EN) return ym.year.toString()
        return runCatching {
            val jd = JapaneseDate.from(ym.atDay(1))
            val era = (jd.era as JapaneseEra).getDisplayName(TextStyle.FULL, Locale.JAPAN)
            val year = jd.get(ChronoField.YEAR_OF_ERA)
            era + (if (year == 1) "元" else kanjiNumber(year)) + "年"
        }.getOrElse { "${ym.year}年" }
    }

    /** `八月` / `August`. */
    fun monthLabel(ym: YearMonth, lang: BandLanguage): String =
        if (lang == BandLanguage.EN) {
            ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        } else {
            kanjiNumber(ym.monthValue) + "月"
        }

    /** The month a `yyyyMMdd` key falls in, or null when the key is not a date. */
    fun ofDateKey(key: Long): YearMonth? = runCatching {
        YearMonth.of((key / 10_000L).toInt(), ((key / 100L) % 100L).toInt())
    }.getOrNull()

    fun ofEpochDay(epochDay: Long): YearMonth = YearMonth.from(LocalDate.ofEpochDay(epochDay))
}

/**
 * The rule that names a month: `──── 令和八年 八月 ──────────────────`.
 *
 * Yellow, because on this screen yellow is the ink that separates one stretch of days from another —
 * the register's week rules are already drawn in it, and a month is the bigger version of the same
 * idea. It is heavier than those (2.5 dp against 1–2.5 dp) and carries words, so the two cannot be
 * mistaken for each other where they meet.
 *
 * The year sits before the month in the note ink at label size and the month follows it bold in the
 * accent: scrolling, the month is the thing being looked for and the year is the thing that has to
 * be there when it changes. Both are on the rule rather than above it so the heading costs one line
 * rather than two — on a folded panel that is the difference between five week-rows visible and
 * four.
 */
@Composable
fun MonthDivider(
    ym: YearMonth,
    modifier: Modifier = Modifier,
    /** Padding above; zero at the top of a list, where there is nothing to separate from. */
    topPadding: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val lang = LocalBandLanguage.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Rule(Modifier.width(12.dp))
        Text(
            BandMonths.yearLabel(ym, lang),
            style = MaterialTheme.typography.labelMedium,
            color = sectionNote,
        )
        Text(
            BandMonths.monthLabel(ym, lang),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = sectionInk,
        )
        Rule(Modifier.weight(1f))
    }
}

@Composable
private fun Rule(modifier: Modifier) {
    Box(
        modifier
            .height(2.5.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(sectionInk),
    )
}
