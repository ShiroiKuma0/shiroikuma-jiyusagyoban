package com.opentasker.ui.charts.huawei

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.core.huawei.HuaweiSettings
import com.opentasker.core.huawei.HuaweiSyncRunner
import com.opentasker.ui.charts.BandLanguage
import com.opentasker.ui.charts.BodyText
import com.opentasker.ui.charts.LocalBandLanguage
import com.opentasker.ui.charts.NoteText
import com.opentasker.ui.charts.SectionTitle
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 「バンドの時計」 — stand the band's clock wherever a face needs looking at.
 *
 * A watch face can only be judged at the time it renders, and the interesting times come round
 * slowly: 相撲字時計 draws 丁度 for sixty seconds an hour and 半 for sixty more. The `huawei.time`
 * action can be told a time, but a task with `at=15:00` written into it is a task that answers one
 * question once. This is the same action with the answer left open — pick a date, drag the dial,
 * press once, look at the band.
 *
 * **Nothing here is destructive**, and it could hardly be: every session announces the phone's time
 * to the band, so the next connection puts the real time back by itself. The switch at the top does
 * that deliberately, which is why setting and resetting are one window rather than two tasks.
 *
 * **A dialog, not a page.** It was a page first, and one switch, one dial and a button left the dial
 * stranded at the top of a field of black, reading as a stray widget rather than as a thing being
 * set (白い熊, 2026-09-05). Floating and centred, it asks a question. It stays put after a set —
 * the point is to set, walk to the band, look, and set again — so closing it is the only thing that
 * ends it.
 *
 * The border is not decoration. The card is black on a page dimmed to black, and without a drawn
 * edge there is no edge; the calendar it opens carries the same one for the same reason.
 *
 * The seconds are always sent as zero. A face is checked at the top of a minute, and a dial that
 * offered seconds would be a third control for something nobody wants to choose.
 */
class HuaweiBandClockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = BandLanguage.parse(HuaweiSettings.language(applicationContext))
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                CompositionLocalProvider(LocalBandLanguage provides lang) {
                    // The card, drawn rather than assumed: the window behind it is transparent, so
                    // without a border a black dialog on a dimmed black page has no edge at all.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = CARD_SHAPE,
                        color = MaterialTheme.colorScheme.background,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        BandClockScreen(
                            address = HuaweiSettings.address(applicationContext),
                            context = applicationContext,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** The card's corner, shared with the border so the two cannot disagree. */
        private val CARD_SHAPE = RoundedCornerShape(24.dp)

        fun open(context: Context) {
            context.startActivity(
                Intent(context, HuaweiBandClockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }
}

/** Local midnight of the day the picker is showing, expressed the way the picker wants it: UTC. */
private fun utcMidnightOf(millis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = millis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/**
 * The picked day and time as an epoch second, in the phone's own zone.
 *
 * The date picker hands back UTC midnight of the chosen day, so the day is read out of a UTC
 * calendar and put back into a local one. Reading it locally instead moves the date by one either
 * side of midnight, depending on which way the offset goes.
 */
private fun epochOf(utcMidnight: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMidnight }
    return Calendar.getInstance().apply {
        clear()
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
    }.timeInMillis / 1000
}

private fun stamp(epochSeconds: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .format(java.util.Date(epochSeconds * 1000))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BandClockScreen(address: String, context: Context) {
    val lang = LocalBandLanguage.current
    val scope = rememberCoroutineScope()

    var useNow by remember { mutableStateOf(false) }
    var day by remember { mutableLongStateOf(utcMidnightOf(System.currentTimeMillis())) }
    val now = remember { Calendar.getInstance() }
    var hour by remember { mutableIntStateOf(now.get(Calendar.HOUR_OF_DAY)) }
    var minute by remember { mutableIntStateOf(now.get(Calendar.MINUTE)) }
    // Bumped when a shortcut moves the hands. The dial's own state cannot be written to, so the
    // picker is rebuilt around the new values instead — which is what `key` is for.
    var dialGeneration by remember { mutableIntStateOf(0) }
    var showDate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    val timeState = key(dialGeneration) {
        rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    }
    LaunchedEffect(timeState.hour, timeState.minute) {
        hour = timeState.hour
        minute = timeState.minute
    }

    val chosen = epochOf(day, hour, minute)

    Column(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SectionTitle(HuaweiText.clockTitle[lang])
        NoteText(HuaweiText.clockWhy[lang])

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useNow, onCheckedChange = { useNow = it })
            Spacer(Modifier.width(12.dp))
            BodyText(HuaweiText.clockUseNow[lang])
        }

        if (!useNow) {
            // The date is the pill that opens the calendar — one control rather than a label, a
            // value and a button, and the same shape as the two shortcuts under the dial.
            OutlinedButton(onClick = { showDate = true }) {
                Text("${HuaweiText.clockDate[lang]}   ${stamp(chosen).substringBefore(' ')}")
            }
            TimePicker(state = timeState)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { minute = 0; dialGeneration++ }) {
                    Text(HuaweiText.clockOnTheHour[lang])
                }
                OutlinedButton(onClick = { minute = 30; dialGeneration++ }) {
                    Text(HuaweiText.clockHalfPast[lang])
                }
            }
            NoteText(HuaweiText.clockSeconds[lang])
        }

        Button(
            onClick = {
                val epoch = if (useNow) System.currentTimeMillis() / 1000 else chosen
                busy = true
                result = null
                scope.launch {
                    val answer = HuaweiSyncRunner.setBandTime(context, address, epoch)
                    failed = answer.isFailure
                    result = answer.getOrNull()
                        ?: answer.exceptionOrNull()?.message
                        ?: HuaweiText.clockFailed[lang]
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (busy) HuaweiText.clockSetting[lang]
                else "${HuaweiText.clockSet[lang]}  —  " +
                    if (useNow) HuaweiText.clockUseNow[lang] else stamp(chosen),
            )
        }

        result?.let { NoteText(it, warn = failed) }
        NoteText(HuaweiText.clockMidnight[lang])
    }

    if (showDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = day)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            // Black on a dimmed black page again: the calendar needs an edge as much as the card
            // that opened it (白い熊, 2026-09-05).
            modifier = Modifier.border(2.dp, MaterialTheme.colorScheme.primary, DatePickerDefaults.shape),
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let { day = it }
                    showDate = false
                }) { Text(HuaweiText.clockPick[lang]) }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) { Text(HuaweiText.clockCancel[lang]) }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}
