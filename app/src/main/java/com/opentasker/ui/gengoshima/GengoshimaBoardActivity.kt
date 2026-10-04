package com.opentasker.ui.gengoshima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.engine.executeAndLogTask
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「言語島」 — the board: every 言語島 task as a picture tile (see [GengoshimaBoardScreen]).
 *
 * A tap runs the tile's TASK by name, the way the 健康 board does, so a task 白い熊 edits in the
 * workspace is what the tile does — the board holds no logic of its own. It closes once the task has
 * been handed over: nearly every tile opens a window of its own, and two stacked windows is one too many.
 */
class GengoshimaBoardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            var busy by remember { mutableStateOf<String?>(null) }
            val dao = remember { OpenTaskerApp_NoHilt.db.gengoshimaDao() }
            val inbox by dao.observeInboxCount().collectAsState(initial = 0)
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val bars = WindowInsets.systemBars.asPaddingValues()
                    GengoshimaBoardScreen(
                        contentPadding = PaddingValues(
                            start = 10.dp, end = 10.dp,
                            top = bars.calculateTopPadding() + 6.dp, bottom = bars.calculateBottomPadding() + 10.dp,
                        ),
                        busy = busy,
                        inboxCount = inbox,
                        onRun = { tile ->
                            if (busy != null) return@GengoshimaBoardScreen
                            busy = tile.task
                            runTask(tile.task) { finish() }
                        },
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    private fun runTask(name: String, onHandedOver: () -> Unit) {
        scope.launch {
            val db = OpenTaskerApp_NoHilt.db
            val decoded = db.taskDao().getByName(name)?.toDomainDecodeResult()
            if (decoded != null && decoded.issue == null) {
                runCatching {
                    executeAndLogTask(
                        appContext = applicationContext, db = db, task = decoded.value,
                        source = "言語島 board", logTag = "GengoshimaBoard",
                    )
                }
            }
            runOnUiThread { onHandedOver() }
        }
    }

    companion object {
        /** Outlives the window: a task started from a tile must not die with the board. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun open(context: Context) {
            context.startActivity(
                Intent(context, GengoshimaBoardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }
}
