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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.core.huawei.HuaweiFaceLibrary
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
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
 * 「文字盤（Huawei）」 — the watch-face library, in its own window.
 *
 * A window rather than a dialog because of what the buttons do. A dialog's contract here is launch,
 * settle once, finish, with `onDestroy` cancelling whatever is outstanding; an install runs for tens
 * of seconds over Bluetooth and the picker should still be there afterwards for the next one.
 *
 * The window owns nothing that matters. Installs run on `HuaweiSyncRunner.scope`, so closing it
 * mid-transfer does not abandon the band half-written — the same reason the sync does it.
 */
class HuaweiFacesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        renderFrom(intent)
    }

    /** `singleTask` delivers a second launch here rather than through [onCreate]. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        renderFrom(intent)
    }

    private fun renderFrom(intent: Intent?) {
        val dir = intent?.getStringExtra(EXTRA_DIR)?.takeIf { it.isNotBlank() } ?: DEFAULT_DIR
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
                        var state by remember { mutableStateOf(HuaweiFacesState(dir = dir)) }
                        val scope = androidx.compose.runtime.rememberCoroutineScope()

                        // One place where an install starts, because there are now two ways in:
                        // the button on a cell, and the same install resumed once 白い熊 has said
                        // which face may be given up. [evict] is removed inside the SAME session
                        // that then installs, so the band is never left a face lighter for nothing.
                        fun startInstall(
                            face: HuaweiFaceLibrary.Entry,
                            evict: Pair<String, String>?,
                        ) {
                            state = state.copy(installing = face.id, bytesSent = 0, message = null)
                            // On the runner's scope, not the composition's: the upload must survive
                            // this window being closed, exactly as a sync does.
                            HuaweiSyncRunner.scope.launch {
                                val work = File(cacheDir, "watchface")
                                val bin = HuaweiFaceLibrary.unpack(face, work)
                                val result = if (bin == null) {
                                    Result.failure(IllegalStateException("could not open ${face.zip.name}"))
                                } else {
                                    HuaweiSyncRunner.uploadWatchFace(
                                        applicationContext,
                                        HuaweiSettings.address(applicationContext),
                                        bin,
                                        evict,
                                    ) { sent -> scope.launch { state = state.copy(bytesSent = sent) } }
                                }
                                HuaweiFaceLibrary.clean(face, work)
                                val outcome = result.getOrNull()
                                val text = result.fold(
                                    onSuccess = { "${face.name} — ${it.message}" },
                                    onFailure = { "${face.name} — ${it.message ?: "failed"}" },
                                )
                                scope.launch {
                                    state = state.copy(
                                        installing = null,
                                        bytesSent = 0,
                                        message = text,
                                        // The band answered with its list on the way past, so the
                                        // grid is current without asking for it again.
                                        band = outcome?.store ?: state.band,
                                        // Only a full band raises the question, and only when the
                                        // band said what it is holding — asking "which one?" over
                                        // a list we do not have would be asking about nothing.
                                        roomNeeded = outcome?.takeIf { it.needsRoom }?.store
                                            ?.let { RoomRequest(face, it.faces) },
                                    )
                                }
                            }
                        }

                        androidx.compose.runtime.LaunchedEffect(dir) {
                            val faces = withContext(Dispatchers.IO) {
                                HuaweiFaceLibrary.list(File(dir))
                            }
                            state = state.copy(faces = faces, loading = false)
                        }

                        HuaweiFacesScreen(
                            state = state,
                            contentPadding = WindowInsets.systemBars.asPaddingValues(),
                            onReadBand = {
                                if (state.bandBusy) return@HuaweiFacesScreen
                                state = state.copy(reading = true, message = null)
                                HuaweiSyncRunner.scope.launch {
                                    val r = HuaweiSyncRunner.listWatchFaces(
                                        applicationContext,
                                        HuaweiSettings.address(applicationContext),
                                    )
                                    scope.launch {
                                        state = state.copy(
                                            reading = false,
                                            band = r.getOrNull(),
                                            message = r.exceptionOrNull()?.message,
                                        )
                                    }
                                }
                            },
                            onRemove = { face ->
                                if (state.bandBusy) return@HuaweiFacesScreen
                                state = state.copy(deleting = face.assetId, message = null)
                                HuaweiSyncRunner.scope.launch {
                                    val r = HuaweiSyncRunner.deleteWatchFace(
                                        applicationContext,
                                        HuaweiSettings.address(applicationContext),
                                        face.assetId,
                                        face.version,
                                    )
                                    // Re-read rather than editing the list we hold: the band is the
                                    // authority on what it kept, and a removal that silently failed
                                    // must not leave the grid claiming the face is gone.
                                    val fresh = HuaweiSyncRunner.listWatchFaces(
                                        applicationContext,
                                        HuaweiSettings.address(applicationContext),
                                    ).getOrNull()
                                    scope.launch {
                                        state = state.copy(
                                            deleting = null,
                                            band = fresh ?: state.band,
                                            message = when {
                                                r.getOrNull() == true -> "${face.name} — removed"
                                                else -> "${face.name} — ${r.exceptionOrNull()?.message ?: "still on the band"}"
                                            },
                                        )
                                    }
                                }
                            },
                            onActivate = { face ->
                                if (state.bandBusy) return@HuaweiFacesScreen
                                // The version the BAND holds, not the library's: they are the same
                                // face but not necessarily the same build, and the band answers to
                                // its own.
                                val version = state.bandVersion(face.assetId) ?: face.version
                                state = state.copy(activating = face.assetId, message = null)
                                HuaweiSyncRunner.scope.launch {
                                    val r = HuaweiSyncRunner.activateWatchFace(
                                        applicationContext,
                                        HuaweiSettings.address(applicationContext),
                                        face.assetId,
                                        version,
                                    )
                                    val fresh = HuaweiSyncRunner.listWatchFaces(
                                        applicationContext,
                                        HuaweiSettings.address(applicationContext),
                                    ).getOrNull()
                                    scope.launch {
                                        state = state.copy(
                                            activating = null,
                                            band = fresh ?: state.band,
                                            message = when {
                                                r.getOrNull() == true -> "${face.name} — showing"
                                                // Said plainly rather than dressed up: the protocol
                                                // has no select command and this is the install
                                                // command standing in for one, so it can decline.
                                                r.isSuccess -> "${face.name} — the band kept the face it had; install it again to change the screen"
                                                else -> "${face.name} — ${r.exceptionOrNull()?.message ?: "failed"}"
                                            },
                                        )
                                    }
                                }
                            },
                            onResolveRoom = { victim ->
                                val pending = state.roomNeeded?.incoming
                                state = state.copy(roomNeeded = null)
                                // Null is 白い熊 cancelling: the dialog closes and nothing on the
                                // band is touched, which is why the removal waits for this answer
                                // instead of happening when the install first hit a full band.
                                if (victim != null && pending != null) {
                                    startInstall(pending, victim.assetId to victim.version)
                                }
                            },
                        ) { face ->
                            if (state.bandBusy) return@HuaweiFacesScreen
                            startInstall(face, null)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_DIR = "shiroikuma.jiyusagyoban.extra.HUAWEI_FACE_DIR"

        /** Where the library lives unless a task says otherwise. */
        const val DEFAULT_DIR =
            "/sdcard/〇/[979] バックアップ/[979][60792][921] 白い熊 自由作業盤 Huawei Band 11 Pro"

        fun open(context: Context, dir: String?) {
            context.startActivity(
                Intent(context, HuaweiFacesActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    if (!dir.isNullOrBlank()) putExtra(EXTRA_DIR, dir)
                },
            )
        }
    }
}
