package com.opentasker.ui.gengoshima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.withTransaction
import com.opentasker.app.OpenTaskerApp_NoHilt
import com.opentasker.core.gengoshima.GenerationRunner
import com.opentasker.core.gengoshima.GengoshimaSettings
import com.opentasker.core.gengoshima.InboxFiling
import com.opentasker.core.gengoshima.IslandProposer
import com.opentasker.core.storage.GengoshimaInboxEntity
import com.opentasker.core.storage.GengoshimaIslandEntity
import com.opentasker.ui.components.AlertDialog
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.launch

/**
 * 「未分類」 — sentences spoken on a walk, reviewed in 白い熊 kxkb, waiting for an island.
 *
 * A scrollable list: each sentence with the island Claude proposed beside it (asked once, when rows
 * arrive without a proposal). Tap the island to change it for that sentence; long-press sentences to
 * select several and change them together; 「すべて確定」 at the bottom files everything — new islands
 * created, sentences appended in the order they were spoken — and then the usual 訳して音声を作る.
 *
 * Every change is written straight to the inbox, so leaving and coming back keeps it.
 */
class GengoshimaInboxActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    InboxScreen(
                        onClose = { finish() },
                        onGenerate = {
                            GenerationRunner.start(applicationContext, GengoshimaSettings.last(applicationContext))
                            startActivity(GengoshimaProgressActivity.intent(applicationContext))
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, GengoshimaInboxActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Where the picker's choice goes: one row, or every selected row. */
private sealed interface PickFor {
    data class One(val uuid: String) : PickFor
    data object Selected : PickFor
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InboxScreen(onClose: () -> Unit, onGenerate: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = OpenTaskerApp_NoHilt.db
    val dao = remember { db.gengoshimaDao() }
    val scope = rememberCoroutineScope()
    val islands by dao.observeIslands().collectAsState(initial = emptyList())
    val rows by dao.observeInbox().collectAsState(initial = null)
    var selected by remember { mutableStateOf(setOf<String>()) }
    var picker by remember { mutableStateOf<PickFor?>(null) }
    var proposing by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var filed by remember { mutableStateOf<Int?>(null) }
    var retry by remember { mutableStateOf(0) }

    // Ask Claude once for the rows that arrived without a proposal.
    LaunchedEffect(rows?.count { it.proposed == 0 }, retry) {
        val todo = rows?.filter { it.proposed == 0 }.orEmpty()
        if (todo.isEmpty()) return@LaunchedEffect
        // A new arrival mid-call relaunches this effect and cancels the old call; the finally keeps
        // the spinner honest either way.
        proposing = true
        problem = null
        try { runCatching {
            val all = dao.islands()
            val samples = all.associate { it.id to dao.sentences(it.id) }
            val picks = IslandProposer.propose(all, samples, todo, GengoshimaSettings.last(context))
            dao.updateInbox(
                todo.map { r ->
                    val p = picks[r.uuid]
                    if (p == null) r.copy(proposed = 1)
                    else if (p.island_id != 0L) r.copy(islandId = p.island_id, newIslandName = "", newIslandRegister = "", proposed = 1)
                    else r.copy(islandId = null, newIslandName = p.new_name.trim(), newIslandRegister = p.new_register.trim(), proposed = 1)
                },
            )
        }.onFailure { if (it !is kotlinx.coroutines.CancellationException) problem = "島の提案ができませんでした / no proposal: ${it.message}" }
        } finally { proposing = false }
    }

    fun apply(target: InboxFiling.Target, to: PickFor) {
        val list = rows.orEmpty()
        val affected = when (to) {
            is PickFor.One -> list.filter { it.uuid == to.uuid }
            PickFor.Selected -> list.filter { it.uuid in selected }
        }
        scope.launch {
            dao.updateInbox(
                affected.map {
                    it.copy(islandId = target.islandId, newIslandName = target.newName, newIslandRegister = target.newRegister, proposed = 1)
                },
            )
        }
        if (to == PickFor.Selected) selected = emptySet()
    }

    val list = rows.orEmpty()
    // The new islands proposed in this batch, so the picker can put more sentences into them.
    val pendingNew = list.filter { it.islandId == null && it.newIslandName.isNotBlank() }
        .map { it.newIslandName to it.newIslandRegister }.distinct()

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("言語島 — 未分類 (${list.size})", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        proposing -> "Claude が島を考えています… / Claude is choosing islands…"
                        selected.isNotEmpty() -> "${selected.size} 文を選択中 / selected"
                        else -> "島を押すと変更、長押しで複数選択 / tap an island to change it, long-press to select"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) { Text("閉じる", fontSize = 18.sp) }
        }

        if (selected.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker = PickFor.Selected }) { Text("島を変える / Move") }
                OutlinedButton(onClick = { selected = list.map { it.uuid }.toSet() }) { Text("全選択") }
                OutlinedButton(onClick = { selected = emptySet() }) { Text("解除") }
            }
        }

        problem?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { retry++ }) { Text("再試行") }
            }
        }

        filed?.let { n ->
            Text("$n 文を島に入れました / filed.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("訳して音声を作る / Translate & voice", fontSize = 18.sp)
            }
        }

        if (rows != null && list.isEmpty() && filed == null) {
            Text(
                "未分類の文はありません。kxkb で確認した文がここに届きます。\nNothing waiting — sentences reviewed in kxkb arrive here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.uuid }) { row ->
                val isSel = row.uuid in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { if (selected.isNotEmpty()) selected = if (isSel) selected - row.uuid else selected + row.uuid },
                            onLongClick = { selected = if (isSel) selected - row.uuid else selected + row.uuid },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected.isNotEmpty()) {
                        // A mark, not a colour: selection must read without hue (白い熊 is red-green colour-blind).
                        Text(if (isSel) "☑" else "☐", fontSize = 22.sp, modifier = Modifier.width(32.dp))
                    }
                    Text(
                        row.en,
                        fontSize = 17.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IslandChip(
                        label = targetLabel(row, islands),
                        onClick = { picker = PickFor.One(row.uuid) },
                    )
                }
                HorizontalDivider()
            }
        }

        val unfiled = InboxFiling.unfiled(list).size
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        val current = dao.inbox()
                        db.withTransaction { InboxFiling.file(dao, current) }
                    }.onSuccess { filed = it; selected = emptySet() }
                        .onFailure { problem = "確定できませんでした / could not file: ${it.message}" }
                }
            },
            enabled = list.isNotEmpty() && unfiled == 0 && !proposing,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(
                if (unfiled > 0) "島のない文が $unfiled あります / $unfiled without an island" else "すべて確定 / File all",
                fontSize = 18.sp,
            )
        }
    }

    picker?.let { target ->
        IslandPicker(
            islands = islands,
            pendingNew = pendingNew,
            onDismiss = { picker = null },
            onPick = { picked -> picker = null; apply(picked, target) },
        )
    }
}

private fun targetLabel(row: GengoshimaInboxEntity, islands: List<GengoshimaIslandEntity>): String =
    when {
        row.islandId != null -> islands.firstOrNull { it.id == row.islandId }?.let { islandLabel(it) } ?: "?"
        row.newIslandName.isNotBlank() -> "＋ ${row.newIslandName}"
        row.proposed == 0 -> "…"
        else -> "島を選ぶ"
    }

@Composable
private fun IslandChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        maxLines = 2,
        modifier = Modifier
            .width(140.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
private fun IslandPicker(
    islands: List<GengoshimaIslandEntity>,
    pendingNew: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (InboxFiling.Target) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NewIslandDialog(onDismiss = { creating = false }) { en, register ->
            creating = false
            onPick(InboxFiling.Target(null, en, register))
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("島を選ぶ / Choose an island") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(pendingNew, key = { "n${it.first}|${it.second}" }) { (name, register) ->
                    TextButton(onClick = { onPick(InboxFiling.Target(null, name, register)) }, modifier = Modifier.fillMaxWidth()) {
                        Text("＋ $name（新）", fontSize = 17.sp, modifier = Modifier.fillMaxWidth())
                    }
                }
                items(islands, key = { it.id }) { isl ->
                    TextButton(onClick = { onPick(InboxFiling.Target(isl.id, "", "")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(islandLabel(isl), fontSize = 17.sp, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text("＋ 新しい島") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
