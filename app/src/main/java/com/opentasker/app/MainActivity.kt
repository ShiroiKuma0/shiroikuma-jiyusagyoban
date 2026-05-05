package com.opentasker.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import com.opentasker.core.contexts.NfcContextEvents
import com.opentasker.ui.screens.ActiveAutomationUi
import com.opentasker.ui.theme.OpenTaskerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Log.d("MainActivity", "Initializing OpenTasker")
        
        setContent {
            OpenTaskerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ActiveAutomationUi(db = OpenTaskerApp_NoHilt.db)
                }
            }
        }
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return
        if (NfcContextEvents.publishFromIntent(intent)) {
            Log.d("MainActivity", "NFC tag event accepted")
        }
    }
}
