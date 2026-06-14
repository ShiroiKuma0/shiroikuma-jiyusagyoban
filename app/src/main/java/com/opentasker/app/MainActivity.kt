package com.opentasker.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import com.opentasker.core.contexts.NfcContextEvents
import com.opentasker.core.contexts.NfcTagWriteSession
import com.opentasker.core.engine.AutomationService
import com.opentasker.ui.screens.ActiveAutomationUi
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("MainActivity", "Initializing OpenTasker")
        
        setContent {
            val themePrefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs = themePrefs) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ActiveAutomationUi(db = OpenTaskerApp_NoHilt.db)
                }
            }
        }
        startAutomationService()
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        val writeResult = NfcTagWriteSession.writeFromIntent(intent)
        if (writeResult != null) {
            Log.d("MainActivity", writeResult.message)
            return
        }
        if (NfcContextEvents.publishFromIntent(intent)) {
            Log.d("MainActivity", "NFC tag event accepted")
        }
    }

    private fun startAutomationService() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, AutomationService::class.java))
        }.onFailure { error ->
            Log.e("MainActivity", "Failed to start OpenTasker automation service", error)
        }
    }
}
