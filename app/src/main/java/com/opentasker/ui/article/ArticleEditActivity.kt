package com.opentasker.ui.article

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.core.ocr.article.ArticleDocument
import com.opentasker.core.ocr.article.ArticleHtmlParser
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import java.io.File

/**
 * 「記事編集」 — where a read article is checked before it becomes a file.
 *
 * Reached two ways, and they are the same screen because they are the same job. 記事変換 hands it a
 * freshly-read article; opened empty, its menu opens one already written. The handover is a file in
 * the cache rather than an object in memory: it survives the window being recreated, and it means the
 * parser is exercised on every single run instead of only when reopening something old.
 */
class ArticleEditActivity : ComponentActivity() {

    private var owned = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent?) {
        val handover = intent?.getStringExtra(EXTRA_HTML)?.let(::File)?.takeIf { it.isFile }
        val outputDirectory = intent?.getStringExtra(EXTRA_OUT)

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            var document by remember(handover) { mutableStateOf(handover?.let(::load)) }
            var extraPages by remember(handover) { mutableStateOf<List<File>>(emptyList()) }

            val htmlPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                val copied = copyIn(this, uri) ?: return@rememberLauncherForActivityResult
                owned += copied
                document = load(copied)
            }

            OpenTaskerTheme(prefs) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val shown = document?.let { base ->
                        if (extraPages.isEmpty()) base
                        // Pages opened by hand are added to whatever the file already named, so an
                        // article whose screenshots have since moved can still be checked.
                        else base.copy(sources = base.sources + extraPages.map { it.absolutePath })
                    }
                    ArticleEditScreen(
                        initial = shown,
                        outputDirectory = outputDirectory,
                        onOpenHtml = { htmlPicker.launch(arrayOf("text/html", "*/*")) },
                        onAddPages = { added -> owned += added; extraPages = extraPages + added },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun load(file: File): ArticleDocument? = runCatching {
        ArticleHtmlParser.parse(file.readText()).document
    }.getOrNull()

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) owned.forEach { runCatching { it.delete() } }
    }

    companion object {
        const val EXTRA_HTML = "shiroikuma.jiyusagyoban.extra.ARTICLE_HTML"
        const val EXTRA_OUT = "shiroikuma.jiyusagyoban.extra.ARTICLE_OUT"

        fun open(context: Context, html: File?, outputDirectory: String?) {
            context.startActivity(
                Intent(context.applicationContext, ArticleEditActivity::class.java).apply {
                    html?.let { putExtra(EXTRA_HTML, it.absolutePath) }
                    outputDirectory?.let { putExtra(EXTRA_OUT, it) }
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }
    }
}
