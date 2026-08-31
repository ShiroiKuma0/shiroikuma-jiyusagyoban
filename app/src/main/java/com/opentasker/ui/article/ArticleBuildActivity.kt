package com.opentasker.ui.article

import android.content.Context
import android.content.Intent
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

/**
 * 「記事変換」 — the window that turns a stack of scrolling screenshots into one HTML article.
 *
 * A window rather than a bare action because the job needs two things a task cannot give it: pages
 * added one at a time in the order they are to be read, and somewhere to watch six minutes of work
 * happen with a way to stop it. The action `ocr.article` still does the whole thing headlessly for a
 * task that already knows its pages; this is the same [com.opentasker.core.ocr.article.ArticleReader]
 * with a front on it.
 *
 * Pages arrive as paths, not as copies: they are 白い熊's own screenshots, sitting where the camera
 * left them, and this window only reads. Anything picked through the document picker IS copied,
 * because a picker URI's grant does not outlive the window.
 */
class ArticleBuildActivity : ComponentActivity() {

    /** Copies this window made itself, and is therefore responsible for. */
    private val owned = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        render(intent)
    }

    /** `singleTask`: a second launch arrives here, and must replace what the window is showing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent?) {
        val initial = intent?.getStringArrayExtra(EXTRA_PAGES).orEmpty()
            .map(::File).filter { it.isFile }
        val directory = intent?.getStringExtra(EXTRA_OUT)?.takeIf { it.isNotBlank() }

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ArticleBuildScreen(
                        initialPages = initial,
                        outputDirectory = directory,
                        onOwnCopy = { owned += it },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only the picker copies go. A page 白い熊 pointed us at is theirs, and stays where it is.
        if (isFinishing) owned.forEach { runCatching { it.delete() } }
    }

    companion object {
        const val EXTRA_PAGES = "shiroikuma.jiyusagyoban.extra.ARTICLE_PAGES"
        const val EXTRA_OUT = "shiroikuma.jiyusagyoban.extra.ARTICLE_OUT"

        fun open(context: Context, pages: List<String>, outputDirectory: String?) {
            context.startActivity(
                Intent(context.applicationContext, ArticleBuildActivity::class.java).apply {
                    if (pages.isNotEmpty()) putExtra(EXTRA_PAGES, pages.toTypedArray())
                    outputDirectory?.let { putExtra(EXTRA_OUT, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }
}
