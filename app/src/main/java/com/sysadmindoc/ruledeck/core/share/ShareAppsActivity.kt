package com.sysadmindoc.ruledeck.core.share

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sysadmindoc.ruledeck.ui.screens.ShareAppsScreen
import com.sysadmindoc.ruledeck.ui.theme.RuleDeckTheme
import com.sysadmindoc.ruledeck.ui.theme.ThemeStore

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
            RuleDeckTheme(prefs) {
                ShareAppsScreen(onBack = { finish() })
            }
        }
    }
}
