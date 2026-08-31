package com.opentasker.core.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.opentasker.ui.screens.ShareAppsScreen
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore

/**
 * Hosts the "Share apps" screen — the per-target relay generator, opened by the `share.relays` action
 * (or any task running it). Self-contained (its own `setContent` + theme), like the other standalone
 * activities in the fork.
 */
class ShareAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                ShareAppsScreen(onBack = { finish() })
            }
        }
    }
}
