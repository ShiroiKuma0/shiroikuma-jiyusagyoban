package com.opentasker.ui.charts

import com.opentasker.ui.charts.huawei.BpGrade
import kotlin.math.roundToInt

/**
 * The ONE 1–5 grade of a reading, which every screen of 健康 draws.
 *
 * ## Why this exists
 *
 * Until 2026-10-25 the same night was graded three ways. The night table put 10 h 14 min asleep on
 * the published sleep ladder and coloured it a 4; the deviation strip and the 回復 card banded it
 * against 白い熊's own last 28 nights, whose "usual" had become 5 h 18 – 11 h 02 because those nights
 * are genuinely irregular, and so drew it as a blue 3 and called it "usual". 白い熊: *"We must have
 * the same grading bands everywhere — and if we list 10+ hours of sleep as red in the every-night
 * page — which it clearly is — bad — it must be graded so everywhere. And the same for every other
 * metric."*
 *
 * ## The rule
 *
 * **A quantity with a published or stated absolute ladder is graded on that ladder, everywhere:**
 *
 * | marker | ladder |
 * |---|---|
 * | time asleep | NSF / AASM — [RecoveryReference.sleepStep] |
 * | nocturnal, resting and lowest heart rate | Jensen's resting-rate decades — [RecoveryReference.nocturnalHrStep] |
 * | blood oxygen | the clinical pulse-oximetry ranges — [RecoveryReference.spo2Step] |
 * | blood pressure | ESH 2018 office categories — [BpGrade] |
 * | HRV (RMSSD), steps, arterial stiffness | the health index's own stated edges — [HealthIndex] |
 * | 体感 | the number 白い熊 tapped, which is already a 1–5 |
 *
 * **Everything else is graded against 白い熊's own preceding nights** ([MarkerReading.scaleStep]),
 * because no absolute ladder fits it: skin temperature (the sensor measures the bedroom), deep sleep
 * and the deep+REM share (a band's staging is not polysomnography), the going-to-bed heart rate, the
 * night's swing and breathing.
 *
 * HRV moved sides on the same day. It had been within-person in the table and absolute in the index,
 * so the two disagreed about the same number; and the within-person band, drawn over nights between
 * 20 and 60 ms, called anything from 20 to 40 "usual". On the absolute edges the 30 ms of the night
 * 白い熊 felt off grades a 4, against the 1s and 2s of the four nights before it.
 */
object Grade {

    /**
     * [value] on [marker]'s absolute ladder, or null when the marker has none — then it is graded
     * within-person and [of] falls back to the reading's own band.
     */
    fun ladder(marker: RecoveryMarker, value: Double): Int? = when (marker) {
        RecoveryMarker.SLEEP -> RecoveryReference.sleepStep(value)
        RecoveryMarker.NOCTURNAL_HR, RecoveryMarker.RESTING_HR -> RecoveryReference.nocturnalHrStep(value)
        RecoveryMarker.SPO2 -> RecoveryReference.spo2Step(value)
        RecoveryMarker.BP_SYSTOLIC -> BpGrade.ofSystolic(value)
        RecoveryMarker.BP_DIASTOLIC -> BpGrade.ofDiastolic(value)
        RecoveryMarker.HRV -> HealthIndex.step(HealthIndex.hrvScore(value))
        RecoveryMarker.STEPS -> HealthIndex.step(HealthIndex.stepsScore(value))
        RecoveryMarker.PWV -> HealthIndex.step(HealthIndex.pwvScore(value))
        RecoveryMarker.FELT -> value.roundToInt().coerceIn(1, 5)
        RecoveryMarker.TEMPERATURE, RecoveryMarker.DEEP, RecoveryMarker.DEEP_REM,
        RecoveryMarker.BEDTIME_HR, RecoveryMarker.HR_SWING, RecoveryMarker.RESPIRATION -> null
    }

    /** True when [marker] is graded on an absolute ladder rather than against 白い熊's own nights. */
    fun isAbsolute(marker: RecoveryMarker): Boolean = ladder(marker, 0.0) != null

    /** At or past this step a reading is graded bad, and counted as such. */
    const val BAD_FROM = 4

    /** The word for a step — one vocabulary, on every card, chip and component. */
    fun word(step: Int?): Loc = when (step) {
        null -> Loc("—", "—")
        1 -> Loc("Excellent", "とても良い")
        2 -> Loc("Good", "良い")
        3 -> Loc("Standard", "標準")
        4 -> Loc("Bad", "悪い")
        else -> Loc("Very bad", "とても悪い")
    }

    val WORDS: List<Loc> get() = (1..5).map { word(it) }
}
