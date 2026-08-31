package com.opentasker.core.contexts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.opentasker.app.R
import com.opentasker.core.engine.AutomationService
import com.opentasker.core.logging.AppLogger
import com.opentasker.core.ocr.OcrShareIntake
import com.opentasker.ui.ocr.OcrReviewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Short-lived Sharesheet entry point; it queues the sanitized event and returns to the sender. */
class ShareReceiverActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handled = false

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

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
        if (handled) return
        handled = true
        if (intent == null) {
            finish()
            return
        }
        // A shared IMAGE is taken by 「文字認識」 before anything is published as a share event; see
        // routeImageToOcr below for why the event would be worthless for an image anyway.
        if (routeImageToOcr(intent)) {
            finish()
            return
        }
        scope.launch {
            when (withContext(Dispatchers.IO) {
                ShareContextEvents.publishFromIntent(this@ShareReceiverActivity, intent)
            }) {
                SharePublishResult.ACCEPTED -> {
                    ContextCompat.startForegroundService(
                        this@ShareReceiverActivity,
                        Intent(this@ShareReceiverActivity, AutomationService::class.java),
                    )
                }
                SharePublishResult.URI_NOT_READABLE -> {
                    showShareError(R.string.share_uri_unreadable)
                }
                SharePublishResult.INVALID_INPUT -> {
                    showShareError(R.string.share_invalid)
                }
            }
            finish()
        }
    }

    private fun showShareError(messageRes: Int) {
        android.widget.Toast.makeText(this, messageRes, android.widget.Toast.LENGTH_LONG).show()
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
