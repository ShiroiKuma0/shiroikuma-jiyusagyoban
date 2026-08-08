package com.opentasker.core.ocr

/**
 * The detection knobs, in one place, so a caller passes a value rather than the engine reading a
 * preference store it should know nothing about.
 *
 * Every default here is measured on the Phase 0 corpus rather than inherited, and every one of them
 * can make recognition worse as easily as better — which is the reason they are settings with printed
 * defaults instead of numbers buried in the source, and the reason each carries what it actually does.
 *
 * Stored as integers on the settings side (percent, tenths) because the settings sliders are integer
 * sliders; the conversion lives here so there is one definition of what "30" means.
 */
data class OcrTuning(
    /**
     * The detector's longest side, in pixels.
     *
     * NOT PaddleOCR's 960 default. Measured on a 2048 px full-width screenshot, 1600 px halved the
     * character error against 960 (1.46 % vs 2.91 %) and the errors it removed were exactly the small
     * text, which on a phone screenshot is most of it. Higher costs time roughly with the area.
     */
    val longSide: Int = DEFAULT_LONG_SIDE,

    /**
     * Where the probability map becomes text.
     *
     * Lower finds fainter text and starts joining neighbouring lines into one box; higher splits words
     * off the ends of lines.
     */
    val binaryThreshold: Float = DEFAULT_BINARY_PERCENT / 100f,

    /**
     * The mean confidence a detected region needs to survive.
     *
     * Raising it drops marginal boxes — useful on a noisy photograph, and a good way to lose real
     * faint lines on a clean screenshot.
     */
    val boxScoreThreshold: Float = DEFAULT_BOX_SCORE_PERCENT / 100f,

    /**
     * How far each detected box is dilated before the crop is taken.
     *
     * The most dangerous knob here. DB predicts a SHRUNK region, so this is what puts the ascenders
     * and diacritics back inside the crop; too low silently decapitates every line and 'ä' starts
     * reading as 'a'. Too high swallows the line above. 1.5 is PaddleOCR's own value and was measured
     * correct here — move it only with a test image in front of you.
     */
    val unclipRatio: Float = DEFAULT_UNCLIP_TENTHS / 10f,
) {
    companion object {
        val DEFAULT = OcrTuning()

        const val DEFAULT_LONG_SIDE = 1600
        const val LONG_SIDE_MIN = 640
        const val LONG_SIDE_MAX = 2560

        const val DEFAULT_BINARY_PERCENT = 30
        const val BINARY_PERCENT_MIN = 10
        const val BINARY_PERCENT_MAX = 70

        const val DEFAULT_BOX_SCORE_PERCENT = 60
        const val BOX_SCORE_PERCENT_MIN = 20
        const val BOX_SCORE_PERCENT_MAX = 90

        const val DEFAULT_UNCLIP_TENTHS = 15
        const val UNCLIP_TENTHS_MIN = 10
        const val UNCLIP_TENTHS_MAX = 30

        /** Builds the tuning from the integers the settings sliders store, clamped to their own ranges. */
        fun from(
            longSide: Int,
            binaryPercent: Int,
            boxScorePercent: Int,
            unclipTenths: Int,
        ) = OcrTuning(
            longSide = longSide.coerceIn(LONG_SIDE_MIN, LONG_SIDE_MAX),
            binaryThreshold = binaryPercent.coerceIn(BINARY_PERCENT_MIN, BINARY_PERCENT_MAX) / 100f,
            boxScoreThreshold = boxScorePercent.coerceIn(BOX_SCORE_PERCENT_MIN, BOX_SCORE_PERCENT_MAX) / 100f,
            unclipRatio = unclipTenths.coerceIn(UNCLIP_TENTHS_MIN, UNCLIP_TENTHS_MAX) / 10f,
        )
    }
}
