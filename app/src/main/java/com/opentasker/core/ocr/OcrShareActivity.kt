package com.opentasker.core.ocr

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.ocr.OcrReviewActivity
import com.opentasker.ui.ocr.flash
import com.opentasker.ui.theme.ThemeStore

/**
 * 「文字認識」 — the Sharesheet tile for OCR, images only.
 *
 * Declared as its own target so the app offers a tile named for what it does, wherever the share UI
 * lists targets per activity. On EMUI, which collapses to one tile per package, the generic receiver
 * may be the tile offered instead — so that one routes images here too. Either path ends up in the same
 * window; see [OcrShareIntake] for why both exist.
 *
 * The bytes are copied before this Activity finishes, which is not an optimisation: a Sharesheet URI
 * grant is scoped to the receiving Activity, so handing the `content://` URI onward to be opened later
 * is broken by construction and fails as a permission error far from here.
 */
class OcrShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.let(OcrShareIntake::imageUri)
        if (uri == null) {
            notify("共有された画像がありません")
            finish()
            return
        }

        val cached = runCatching { OcrShareIntake.copyToCache(this, uri) }
            .onFailure { AppLogger.warn(TAG, "could not read the shared image", it) }
            .getOrNull()

        if (cached == null) notify("画像を読み込めませんでした") else OcrReviewActivity.open(this, cached)
        finish()
    }

    /** The app's own flash, so a failure here looks like the rest of 白い熊 自由作業盤, not like Android. */
    private fun notify(message: String) {
        flash(applicationContext, ThemeStore.state.value, message)
    }

    private companion object {
        const val TAG = "OcrShare"
    }
}
