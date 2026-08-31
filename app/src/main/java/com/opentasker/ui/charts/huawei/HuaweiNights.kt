package com.opentasker.ui.charts.huawei

import com.opentasker.core.storage.AppDatabase
import com.opentasker.ui.charts.SleepRun
import com.opentasker.ui.charts.SleepSession

/**
 * Nights, from whichever band was on the wrist that night.
 *
 * The same rule the charts follow: the Huawei band owns every night from its first recorded one
 * onward, and the Hume band owns the era before that. **No night is ever assembled from both.** A
 * recovery baseline built by mixing two devices' staging would be a number about neither of them —
 * consumer sleep staging disagrees with itself across devices far more than it disagrees with a
 * clock, so blending is worse here than almost anywhere else on this screen.
 *
 * ## Why the stage codes have to be translated rather than reused
 *
 * The two bands number their stages differently, and the numbers overlap — so an untranslated code
 * does not fail, it silently means the wrong stage. The Huawei band uses 1 light, 2 REM, 3 deep,
 * 4 awake; the shared [SleepSession] uses the Hume band's '1' deep, '2' light, '3' REM, '5' awake.
 * Reading a Huawei '3' as deep when it means REM would move an hour a night into the wrong column
 * and every derived figure with it.
 */
object HuaweiNights {

    /** Huawei stage → the shared code. See the class note: these numbers do NOT correspond. */
    private fun codeOf(stage: Int): Char = when (stage) {
        1 -> '2'   // light
        2 -> '3'   // REM
        3 -> '1'   // deep
        4 -> '5'   // awake
        else -> '?'
    }

    /**
     * Every night on record, oldest first, each attributed to exactly one band.
     *
     * [cutoverMs] is where the Huawei band's own record begins. Nights ending before it come from
     * the Hume store; nights from it onward come from the Huawei store. A night that straddles the
     * cutover — the first night the new band was worn — is taken from the Huawei side, because that
     * is the band that recorded it through to morning.
     */
    suspend fun all(
        db: AppDatabase,
        cutoverMs: Long?,
        humeSessions: List<SleepSession>,
    ): List<SleepSession> {
        val huawei = huaweiSessions(db)
        val boundary = cutoverMs ?: Long.MAX_VALUE
        val older = humeSessions.filter { it.endMs < boundary }
        return (older + huawei).sortedBy { it.startMs }
    }

    /** The Huawei band's own nights, rebuilt from stored segments. */
    private suspend fun huaweiSessions(db: AppDatabase): List<SleepSession> {
        val dao = db.huaweiSleepDao()
        val starts = runCatching { dao.sessionStarts() }.getOrDefault(emptyList())
        return starts.mapNotNull { start ->
            val rows = runCatching { dao.session(start) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            SleepSession(
                startMs = start * 1000L,
                endMs = rows.first().sessionEnd * 1000L,
                runs = rows.map { r ->
                    SleepRun(
                        startMs = r.startSeconds * 1000L,
                        endMs = (r.startSeconds + r.durationSeconds) * 1000L,
                        code = codeOf(r.stage),
                    )
                },
            )
        }
    }
}
