package com.opentasker.core.ocr

/**
 * The ONNX weight files 「文字認識」 needs, which 白い熊 supplies rather than the APK.
 *
 * They are ~100 MB together and no build is ever deleted from the phone, so shipping them cost a
 * gigabyte every few builds for files that never change (白い熊, 2026-08-08). The dictionaries DO
 * ship — 95 KB, and each one has to match its model exactly, so bundling them removes a whole class
 * of mismatch.
 *
 * Each slot names the file to fetch and where it came from, because a year from now "which of the
 * eleven PP-OCRv5 recognisers was this" is not a guessable question.
 */
enum class ModelSlot(
    val id: String,
    /** What the settings row calls it. */
    val label: String,
    /** The upstream file name, so a download can be matched to a slot by eye. */
    val fileName: String,
    val about: String,
) {
    DETECTION(
        "det", "Detector", "ppocrv5-mobile-det.onnx",
        "PP-OCRv5 mobile detector, ~4.7 MB. Shared by every script — without it nothing is found at all.",
    ),
    JPN_SERVER(
        "jpn_server", "Japanese + English (accurate)", "ppocrv5-server-rec.onnx",
        "PP-OCRv5 server recogniser, ~81 MB. Reads Japanese, English and Chinese in one model. " +
            "Used when the high-accuracy switch is on.",
    ),
    JPN_MOBILE(
        "jpn_mobile", "Japanese + English (fast)", "ppocrv5-mobile-rec.onnx",
        "PP-OCRv5 mobile recogniser, ~16 MB. Measured equal to the server model on clean screenshot " +
            "text and about 2.5x faster. Used when the high-accuracy switch is off.",
    ),
    LATIN(
        "latin", "Latin", "rec.onnx (languages/latin)", 
        "latin_PP-OCRv5_mobile_rec, ~7.5 MB. German, Czech, Polish and 41 other Latin-script languages.",
    ),
    ESLAV(
        "eslav", "Cyrillic", "rec.onnx (languages/eslav)",
        "eslav_PP-OCRv5_mobile_rec, ~7.5 MB. Russian, Ukrainian, Belarusian, Bulgarian.",
    ),
    ;

    /** File names this slot will adopt from a conventional folder, newest convention first. */
    val discoveryNames: List<String>
        get() = when (this) {
            DETECTION -> listOf("ppocrv5-mobile-det.onnx", "det.onnx")
            JPN_SERVER -> listOf("ppocrv5-server-rec.onnx", "rec_jpn.onnx")
            JPN_MOBILE -> listOf("ppocrv5-mobile-rec.onnx", "rec_jpn_mobile.onnx")
            LATIN -> listOf("latin_rec.onnx", "rec_latin.onnx")
            ESLAV -> listOf("eslav_rec.onnx", "rec_eslav.onnx")
        }

    companion object {
        /**
         * Where the weights are looked for when a slot has not been set by hand.
         *
         * 白い熊 keeps them with the other dictionaries, under 日本語 → 辞書 — so the ordinary case is
         * that the files are already in the right place and 「文字認識」 should simply work rather than
         * demand five pickings first. /sdcard/tmp is second because that is where a fresh adb push lands.
         */
        val SEARCH_DIRECTORIES = listOf(
            "/storage/emulated/0/〇/[227] 日本語/[227][66] 辞書/[227][66][362] 文字認識モデル",
            "/storage/emulated/0/tmp",
        )

        /** Where all five come from. Opened by the button in 「文字認識」 settings. */
        const val DOWNLOAD_PAGE = "https://huggingface.co/bukuroo/PPOCRv5-ONNX/tree/main"

        /** The Latin and Cyrillic recognisers live in a second repository. */
        const val DOWNLOAD_PAGE_MULTILINGUAL = "https://huggingface.co/monkt/paddleocr-onnx/tree/main/languages"
    }
}

/** Reads a slot's configured location out of the appearance settings. */
fun com.opentasker.ui.theme.ThemePrefs.ocrModelPath(slot: ModelSlot): String = when (slot) {
    ModelSlot.DETECTION -> ocrModelDet
    ModelSlot.JPN_SERVER -> ocrModelJpnServer
    ModelSlot.JPN_MOBILE -> ocrModelJpnMobile
    ModelSlot.LATIN -> ocrModelLatin
    ModelSlot.ESLAV -> ocrModelEslav
}

/** Returns a copy of the settings with [slot]'s location set to [value]. */
fun com.opentasker.ui.theme.ThemePrefs.withOcrModelPath(
    slot: ModelSlot,
    value: String,
): com.opentasker.ui.theme.ThemePrefs = when (slot) {
    ModelSlot.DETECTION -> copy(ocrModelDet = value)
    ModelSlot.JPN_SERVER -> copy(ocrModelJpnServer = value)
    ModelSlot.JPN_MOBILE -> copy(ocrModelJpnMobile = value)
    ModelSlot.LATIN -> copy(ocrModelLatin = value)
    ModelSlot.ESLAV -> copy(ocrModelEslav = value)
}
