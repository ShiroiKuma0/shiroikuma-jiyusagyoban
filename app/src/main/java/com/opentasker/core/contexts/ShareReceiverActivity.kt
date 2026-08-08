package com.opentasker.core.contexts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.ocr.OcrShareIntake
import com.opentasker.ui.ocr.OcrReviewActivity

/** Short-lived Sharesheet entry point; it queues the sanitized event and returns to the sender. */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent != null && routeImageToOcr(intent)) {
            finish()
            return
        }
        if (intent != null && ShareContextEvents.publishFromIntent(intent)) {
            ContextCompat.startForegroundService(this, Intent(this, AutomationService::class.java))
        }
        finish()
    }

    /**
     * A shared IMAGE opens 「文字認識」 rather than becoming a `share` engine event.
     *
     * This activity accepts every MIME type, and on EMUI — which collapses the share UI to one tile per
     * package — it may well be the tile 白い熊 is offered when sharing a screenshot, instead of the OCR
     * tile that is named for the job. Rather than gamble on which one the OS surfaces, both handle an
     * image the same way.
     *
     * Nothing is lost by not publishing the event: a `share` event only ever carried the URI as a
     * *string*, and the grant behind it dies with this Activity, so no task could read the image anyway.
     * A task that wants OCR has the `ocr.recognize` action, which takes a path it can actually open.
     *
     * @return true when the image was taken and the review window opened
     */
    private fun routeImageToOcr(intent: Intent): Boolean {
        val uri = OcrShareIntake.imageUri(intent) ?: return false
        if (!OcrShareIntake.isImage(this, intent, uri)) return false
        val cached = runCatching { OcrShareIntake.copyToCache(this, uri) }
            .onFailure { AppLogger.warn("ShareReceiver", "could not read the shared image", it) }
            .getOrNull() ?: return false
        OcrReviewActivity.open(this, cached)
        return true
    }
}
