package com.opentasker.ui.charts.huawei

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.core.huawei.HuaweiWalkLibrary
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.ChartStyle
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.LocalChartStyle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「運動（Huawei）」 — the walks, in their own window.
 *
 * A window rather than a dialog for the same reason the watch-face picker is one: asking the band
 * for walks runs for tens of seconds over Bluetooth, and the grid has to still be there afterwards.
 *
 * Work runs on `HuaweiSyncRunner.scope` rather than the composition's, so closing the window
 * mid-download does not abandon a half-written walk.
 */
class HuaweiWalksActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        renderFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFrom(intent)
    }

    private fun renderFrom(intent: Intent?) {
        val dir = intent?.getStringExtra(EXTRA_DIR)?.takeIf { it.isNotBlank() }
            ?: HuaweiWalkLibrary.DEFAULT_DIR
        val days = intent?.getIntExtra(EXTRA_DAYS, 0)?.takeIf { it > 0 } ?: 7
        val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))

        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            val chartStyle = remember(themePrefs) { ChartStyle.from(themePrefs) }
            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(
                    LocalBandLanguage provides lang,
                    LocalChartStyle provides chartStyle,
                ) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        var state by remember { mutableStateOf(HuaweiWalksState(dir = dir)) }
                        var open by remember { mutableStateOf<String?>(null) }
                        val scope = rememberCoroutineScope()
                        val root = remember(dir) { File(dir) }

                        suspend fun reload() {
                            val walks = withContext(Dispatchers.IO) { HuaweiWalkLibrary.list(root) }
                            state = state.copy(walks = walks, loading = false)
                        }

                        /**
                         * Run one 地図 round trip and fold the answer back into the screen.
                         *
                         * On [HuaweiSyncRunner.scope] rather than the composition's: the render can
                         * take a minute or two, and closing the window must not abandon a walk
                         * halfway into another app's library.
                         */
                        fun run(walk: HuaweiWalkLibrary.Walk, op: suspend (HuaweiWalkLibrary.Walk) -> HuaweiChizu.Outcome) {
                            HuaweiSyncRunner.scope.launch {
                                val outcome = op(walk)
                                scope.launch {
                                    state = state.copy(sharing = null, message = outcome.message)
                                    reload()
                                }
                            }
                        }

                        LaunchedEffect(dir) { reload() }

                        val insets = WindowInsets.systemBars.asPaddingValues()
                        val opened = state.walks.firstOrNull { it.id == open }

                        if (opened != null) {
                            HuaweiWalkDetailScreen(
                                walk = opened,
                                sharing = state.sharing == opened.id,
                                busy = state.busy,
                                message = state.message,
                                contentPadding = insets,
                                onShare = {
                                    state = state.copy(sharing = opened.id, message = null)
                                    run(opened) { HuaweiChizu.share(applicationContext, it) }
                                },
                                onOpenInChizu = {
                                    state = state.copy(sharing = opened.id, message = null)
                                    run(opened) { HuaweiChizu.show(applicationContext, it) }
                                },
                                onBack = { open = null },
                            )
                        } else {
                            HuaweiWalksScreen(
                                state = state,
                                contentPadding = insets,
                                onDownload = {
                                    if (state.busy) return@HuaweiWalksScreen
                                    state = state.copy(downloading = true, message = null)
                                    HuaweiSyncRunner.scope.launch {
                                        val now = System.currentTimeMillis() / 1000
                                        val result = HuaweiSyncRunner.fetchWorkouts(
                                            applicationContext,
                                            HuaweiSettings.address(applicationContext),
                                            now - days * 86_400L,
                                            now,
                                            root,
                                        )
                                        val note = result.fold(
                                            onSuccess = { walks ->
                                                if (walks.isEmpty()) HuaweiText.walksNoneFound[lang]
                                                else walks.joinToString(" · ") { it.summary.kind }
                                            },
                                            onFailure = { it.message ?: "failed" },
                                        )
                                        scope.launch {
                                            state = state.copy(downloading = false, message = note)
                                            reload()
                                        }
                                    }
                                },
                                onShare = { walk ->
                                    if (state.busy) return@HuaweiWalksScreen
                                    state = state.copy(sharing = walk.id, message = null)
                                    run(walk) { HuaweiChizu.share(applicationContext, it) }
                                },
                                onOpenInChizu = { walk ->
                                    if (state.busy) return@HuaweiWalksScreen
                                    state = state.copy(sharing = walk.id, message = null)
                                    run(walk) { HuaweiChizu.show(applicationContext, it) }
                                },
                                onOpen = { open = it.id },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DIR = "shiroikuma.jiyusagyoban.extra.HUAWEI_WALK_DIR"
        const val EXTRA_DAYS = "shiroikuma.jiyusagyoban.extra.HUAWEI_WALK_DAYS"

        fun open(context: Context, dir: String?, days: Int?) {
            context.startActivity(
                Intent(context, HuaweiWalksActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    if (!dir.isNullOrBlank()) putExtra(EXTRA_DIR, dir)
                    days?.takeIf { it > 0 }?.let { putExtra(EXTRA_DAYS, it) }
                },
            )
        }
    }
}
