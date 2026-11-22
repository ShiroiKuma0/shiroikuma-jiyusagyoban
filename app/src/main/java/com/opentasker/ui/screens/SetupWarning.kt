package com.opentasker.ui.screens

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.opentasker.app.R
import com.opentasker.core.accessibility.ShiroiKumaAccessibilityService
import com.opentasker.core.logging.AppLogger
import com.opentasker.ui.components.OutlinedButton
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore
import com.opentasker.ui.theme.UiRole
import com.opentasker.ui.theme.UiRoles
import com.opentasker.ui.theme.role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The check for setup items that have been switched off behind the app's back.
 *
 * A reboot, a force-stop or an EMUI clean-up can take the accessibility service or a permission
 * away without a word; the first sign used to be a task failing with a ✕ (画面切, 2026-11-16).
 * So the Setup page's own list is walked after every boot and every time the app is opened, and
 * anything off raises [SetupWarningActivity] — a near page-size list with the same shortcuts as
 * Setup. Dismissing it leaves an ongoing notification, and a tap on that brings the list back.
 */
object SetupWarning {
    private const val TAG = "OpenTasker"
    private const val CHANNEL_ID = "opentasker.setup_warning"
    private const val CHANNEL_NAME = "設定の警告 · Setup warning"
    private const val NOTIFICATION_ID = 63_361

    /** Long enough for the system to bind the accessibility service and for Shizuku to come up. */
    private const val BOOT_SETTLE_MS = 45_000L

    /** After 「後で」 an app open stays quiet this long — the way to Setup leads through the app. */
    private const val SNOOZE_MS = 5 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var dismissedAt = 0L

    /** After a reboot: wait for the system to settle, then check and always raise the list. */
    fun scheduleAfterBoot(app: Context) {
        scope.launch {
            delay(BOOT_SETTLE_MS)
            check(app.applicationContext, fromAppOpen = false)
        }
    }

    /** The app was opened. Quiet for [SNOOZE_MS] after a dismissal, so Setup can be reached. */
    fun onAppOpened(app: Context) {
        scope.launch { check(app.applicationContext, fromAppOpen = true) }
    }

    /** The list was dismissed: the notification stays, the next app opens are left alone a while. */
    fun dismissed() {
        dismissedAt = SystemClock.elapsedRealtime()
    }

    /**
     * What is switched off right now. The accessibility service is bound by the system a moment
     * after the process starts, so an enabled-but-not-yet-bound service is waited for rather than
     * reported.
     */
    suspend fun switchedOff(app: Context): List<String> {
        if (ShiroiKumaAccessibilityService.isEnabledButNotRunning(app)) {
            ShiroiKumaAccessibilityService.awaitConnected(app, timeoutMs = 5_000L)
        }
        // Which items count as required depends on what the tasks use.
        com.opentasker.core.capabilities.LocaleSwitchUse.refresh()
        return withContext(Dispatchers.Main) { switchedOffSetupItems(app).map { it.title } }
    }

    private suspend fun check(app: Context, fromAppOpen: Boolean) {
        val off = runCatching { switchedOff(app) }
            .onFailure { AppLogger.warn(TAG, "Setup check failed: ${it.message}") }
            .getOrNull() ?: return
        if (off.isEmpty()) {
            clear(app)
            return
        }
        AppLogger.warn(TAG, "Setup items switched off: ${off.joinToString()}")
        notify(app, off)
        val snoozed = fromAppOpen && dismissedAt != 0L &&
            SystemClock.elapsedRealtime() - dismissedAt < SNOOZE_MS
        if (snoozed) return
        // From the background this needs the overlay permission; without it the notification stands.
        runCatching { app.startActivity(SetupWarningActivity.intent(app)) }
            .onFailure { AppLogger.warn(TAG, "Setup warning could not be opened: ${it.message}") }
    }

    fun notify(app: Context, off: List<String>) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            val open = PendingIntent.getActivity(
                app,
                NOTIFICATION_ID,
                SetupWarningActivity.intent(app),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = off.joinToString(" · ")
            val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(UiRoles.argb(UiRole.NOTIFICATION_ACCENT))
                .setContentTitle("設定が外れています · ${off.size} setup item${if (off.size == 1) "" else "s"} switched off")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setContentIntent(open)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }.onFailure { AppLogger.warn(TAG, "Setup warning notification could not be shown: ${it.message}") }
    }

    fun clear(app: Context) {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }
}

/** The near page-size list of switched-off setup items. Closes itself once everything is back on. */
class SetupWarningActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                SetupWarningPanel(
                    onLater = {
                        SetupWarning.dismissed()
                        finish()
                    },
                    onAllReady = {
                        SetupWarning.clear(applicationContext)
                        Toast.makeText(this, "設定はすべて有効です · All setup items are on", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, SetupWarningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun SetupWarningPanel(onLater: () -> Unit, onAllReady: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    var items by remember { mutableStateOf(switchedOffSetupItems(app)) }
    val refresh = {
        val now = switchedOffSetupItems(app)
        if (now.isEmpty()) {
            onAllReady()
        } else {
            if (now.map { it.title } != items.map { it.title }) SetupWarning.notify(app, now.map { it.title })
            items = now
        }
    }
    val runAction = rememberPermissionActionRunner(
        onMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
        onChanged = refresh,
    )
    // Coming back from a settings page resumes this window; the accessibility service and Shizuku
    // connect a moment later still, so the list is also re-read while it is on screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                refresh()
                delay(1_500L)
            }
        }
    }
    BackHandler(onBack = onLater)

    // No Surface stands behind this window, so nothing else sets the colour of plain text — it came
    // out black on black (the card titles, the sentence under the heading).
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(role(UiRole.SCHEME_SCRIM).copy(alpha = 0.72f))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .background(role(UiRole.DIALOG_GROUND), RoundedCornerShape(24.dp))
                .border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "設定が外れています",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "${items.size} setup item${if (items.size == 1) " is" else "s are"} switched off. " +
                    "Tasks that depend on ${if (items.size == 1) "it" else "them"} fail until " +
                    "${if (items.size == 1) "it is" else "they are"} switched back on.",
                style = MaterialTheme.typography.bodyLarge,
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.title }) { item ->
                    PermissionSetupCard(item = item, onRunAction = { runAction(item) })
                }
            }
            OutlinedButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text("後で — 通知に残す · Later — keep as a notification")
            }
        }
    }
    }
}
