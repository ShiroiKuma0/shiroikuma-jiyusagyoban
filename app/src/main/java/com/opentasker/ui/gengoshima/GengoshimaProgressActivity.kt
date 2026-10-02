package com.opentasker.ui.gengoshima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opentasker.core.gengoshima.GenerationRunner
import com.opentasker.core.gengoshima.GenerationRunner.StepState
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.delay

/**
 * 言語島's progress window — the run as it happens, step by step, over whatever was on screen.
 *
 * Modelled on the satellite panel in 健康 (白い熊, 2026-09-30): the three steps with what each is doing
 * right now, a bar, the running log, and at the end the outcome with a Close button. Closing a
 * FINISHED run dismisses it and its notification together; closing a RUNNING one only hides the
 * window — the run goes on, and the notification brings the window back from any app.
 */
class GengoshimaProgressActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A run that finished in a process EMUI has since reaped is still there to read.
        GenerationRunner.restore(applicationContext)
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                ProgressPanel(
                    onHide = { finish() },
                    onClose = {
                        GenerationRunner.dismiss(applicationContext)
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, GengoshimaProgressActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun ProgressPanel(onHide: () -> Unit, onClose: () -> Unit) {
    val progress by GenerationRunner.progress.collectAsState()
    val running = progress?.running == true
    // Back only HIDES, finished or not (白い熊, 2026-10-02: in the car a finished run's outcome and its
    // notification vanished — Back on a finished run used to mean 閉じる). Only the button forgets it.
    BackHandler { onHide() }

    // The clock ticks on its own; the runner only speaks when something happens.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    ProgressPanelContent(progress, now, onHide, onClose)
}

/** The panel itself, stateless, so a screenshot preview can draw every stage of a run. */
@Composable
internal fun ProgressPanelContent(
    p: GenerationRunner.Progress?,
    now: Long,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    val running = p?.running == true
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(16.dp))
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "言語島 — 訳と音声 / Translate & voice",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (p == null) {
                Text("いま動いているものはありません。\nNothing is running.", fontSize = 17.sp)
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("閉じる / Close", fontSize = 18.sp) }
                return@Column
            }

            p.steps.forEachIndexed { i, step -> StepRow(i + 1, step) }

            if (running) {
                if (p.percent in 0..100) {
                    LinearProgressIndicator(progress = { p.percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            val end = p.finishedAt ?: now
            Text(
                "経過 / elapsed ${elapsed(end - p.startedAt)}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (p.log.isNotEmpty()) {
                val list = rememberLazyListState()
                LaunchedEffect(p.log.size) { list.animateScrollToItem(p.log.size - 1) }
                LazyColumn(
                    state = list,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    items(p.log) { line ->
                        Text(
                            line,
                            fontSize = 14.sp,
                            color = if (line.startsWith("✕")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            p.result?.let { result ->
                val good = p.ok == true
                Text(
                    (if (good) "✓ " else "✕ ") + result,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            if (running) {
                OutlinedButton(onClick = onHide, modifier = Modifier.fillMaxWidth()) {
                    Text("隠す — 裏で続きます / Hide — it keeps running", fontSize = 16.sp)
                }
                Text(
                    "通知を押すとこの画面に戻ります。\nTap the notification to come back here.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("閉じる / Close", fontSize = 18.sp)
                }
            }
        }
    }
}

/** One step: a glyph AND a colour for its state, never the colour alone. */
@Composable
private fun StepRow(n: Int, step: GenerationRunner.Step) {
    val scheme = MaterialTheme.colorScheme
    val (glyph, color) = when (step.state) {
        StepState.WAIT -> "○" to scheme.onSurfaceVariant.copy(alpha = 0.5f)
        StepState.RUN -> "" to scheme.primary
        StepState.DONE -> "✓" to scheme.primary
        StepState.FAIL -> "✕" to scheme.error
        StepState.SKIP -> "–" to scheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            if (step.state == StepState.RUN) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 3.dp)
            } else {
                Text(glyph, fontSize = 20.sp, color = color, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$n. ${step.title}",
                fontSize = 18.sp,
                fontWeight = if (step.state == StepState.RUN) FontWeight.Bold else FontWeight.Normal,
                color = if (step.state == StepState.WAIT) scheme.onSurfaceVariant.copy(alpha = 0.6f) else scheme.onSurface,
            )
            if (step.detail.isNotBlank()) {
                Text(step.detail, fontSize = 14.sp, color = if (step.state == StepState.FAIL) scheme.error else scheme.onSurfaceVariant)
            }
        }
    }
}

private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 60) "${s / 60} 分 ${s % 60} 秒" else "$s 秒"
}
