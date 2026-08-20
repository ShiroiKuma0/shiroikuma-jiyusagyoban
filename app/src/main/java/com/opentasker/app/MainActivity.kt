package com.opentasker.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedCallback
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.opentasker.core.logging.AppLogger
import androidx.activity.compose.setContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.core.content.ContextCompat
import com.opentasker.core.contexts.NfcContextEvents
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.engine.AutomationService
import com.opentasker.ui.screens.ActiveAutomationUi
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeMode
import com.opentasker.ui.theme.ThemePreference

/**
 * Shown while startup finishes preparing the database. Applying a staged restore copies up to
 * 100 MB and may run a cipher migration, so this is the honest state for that launch rather than a
 * frozen main thread.
 */
@Composable
private fun StartupPreparingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

class MainActivity : ComponentActivity() {
    private val rootBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    private val predictiveBackCallback = OnBackInvokedCallback {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, rootBackCallback)
        registerPredictiveBackCallback()
        enableEdgeToEdge()
        
        setContent {
            val themeMode by ThemePreference.observe(this).collectAsState(initial = ThemeMode.Amoled)
            OpenTaskerTheme(themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Never OpenTaskerApp_NoHilt.db here: that getter blocks up to 30 seconds,
                    // and the launch that pays it is the one right after staging a restore.
                    val database by produceState(OpenTaskerApp_NoHilt.readyDb) {
                        if (value == null) value = OpenTaskerApp_NoHilt.awaitDb()
                    }
                    database?.let { ActiveAutomationUi(db = it) } ?: StartupPreparingScreen()
                }
            }
        }
        startAutomationService()
        handleNfcIntent(intent)
    }

    override fun onDestroy() {
        unregisterPredictiveBackCallback()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        if (NfcTagWriteSession.isArmed()) {
            // Tag connect/write/format is blocking I/O; running it on the UI thread during
            // onCreate/onNewIntent risks jank or an ANR. Hop to a background thread and let
            // the result surface through NfcTagWriteSession.results.
            lifecycleScope.launch(Dispatchers.IO) {
                val writeResult = NfcTagWriteSession.writeFromIntent(intent)
                if (writeResult != null) {
                    AppLogger.debug("MainActivity", writeResult.message)
                }
            }
            return
        }
        if (NfcContextEvents.publishFromIntent(intent)) {
            AppLogger.debug("MainActivity", "NFC tag event accepted")
        }
    }

    private fun startAutomationService() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AutomationService::class.java)
                    .putExtra(AutomationService.EXTRA_STARTED_FROM_VISIBLE_UI, true),
            )
        }.onFailure { error ->
            AppLogger.error("MainActivity", "Failed to start OpenTasker automation service", error)
        }
    }

    private fun registerPredictiveBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            predictiveBackCallback,
        )
    }

    private fun unregisterPredictiveBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(predictiveBackCallback)
    }

}
