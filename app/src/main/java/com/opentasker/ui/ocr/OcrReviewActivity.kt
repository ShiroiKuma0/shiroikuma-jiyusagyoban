package com.opentasker.ui.ocr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import java.io.File
import kotlin.math.max

/**
 * 「文字認識」 — the shared screenshot, and the text read out of it, in one window.
 *
 * A real window rather than a tab, for the same reason 「健康」 is one (see
 * [com.opentasker.ui.charts.BandChartsActivity]): this is a thing you open, look at, take from and
 * close. It is reached from the Sharesheet by [com.opentasker.core.ocr.OcrShareActivity].
 *
 * The window owns the cached screenshot and deletes it on the way out — a screenshot is private and
 * nothing should outlive looking at it.
 */
class OcrReviewActivity : ComponentActivity() {

    private var cached: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        renderFrom(intent)
    }

    /**
     * `singleTask` delivers a second launch here rather than through [onCreate].
     *
     * Without this, opening 文字認識 from its task while the window already existed brought the old
     * one forward still showing the previous screenshot — the launch looked like it had done nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFrom(intent)
    }

    private fun renderFrom(intent: Intent?) {

        // No image is a legitimate way in now: a task can open the window empty so 白い熊 picks one
        // from here, which is the same loop as sharing without having to go and find a share sheet.
        val path = intent?.getStringExtra(EXTRA_IMAGE_PATH)
        val file = path?.let(::File)?.takeIf { it.isFile }
        cached = file

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    OcrReviewScreen(
                        initialImage = file,
                        decode = ::decode,
                        onImagePicked = { picked ->
                            // The window owns whatever it is showing, so the old one goes when replaced.
                            if (picked != cached) cached?.delete()
                            cached = picked
                        },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // isFinishing: a rotation or a process-death recreate must NOT take the screenshot with it.
        if (isFinishing) cached?.delete()
    }

    /**
     * Decodes with a subsample so a very large cut-out cannot blow the heap. The cap is generous —
     * detection already caps its own input at 1600 px, but the on-screen image is what 白い熊 zooms
     * into to check a character against, so it keeps meaningfully more detail than the recogniser sees.
     */
    fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    companion object {
        const val EXTRA_IMAGE_PATH = "shiroikuma.jiyusagyoban.extra.OCR_IMAGE_PATH"

        private const val MAX_DIMENSION = 4096

        /** Opened from the share tile, which is finishing as it calls this — hence NEW_TASK. */
        fun open(context: Context, image: File?) {
            context.startActivity(
                Intent(context.applicationContext, OcrReviewActivity::class.java).apply {
                    image?.let { putExtra(EXTRA_IMAGE_PATH, it.absolutePath) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }
}
