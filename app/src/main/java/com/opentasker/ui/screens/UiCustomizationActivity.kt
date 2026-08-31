package com.opentasker.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.opentasker.ui.theme.OpenTaskerTheme
import com.opentasker.ui.theme.ThemeStore

/**
 * The appearance settings as a window of their own, so another screen can send 白い熊 straight to one
 * section and get them back afterwards.
 *
 * 「文字認識」 is the reason it exists. Its knobs are only useful in a loop — change one, look at the
 * same screenshot again — and that loop needs two things the in-app settings page could not give it:
 * landing on the right rows rather than nine hundred rows above them, and a Back that returns to the
 * screenshot you were looking at. An Activity gets the second for free: the system stack remembers
 * where this was launched from, so there is no "where did I come from" state to keep in sync.
 */
class UiCustomizationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val focus = intent.getStringExtra(EXTRA_FOCUS)

        setContent {
            val prefs by ThemeStore.state.collectAsState()
            OpenTaskerTheme(prefs) {
                UiCustomizationScreen(onBack = { finish() }, focusSection = focus)
            }
        }
    }

    companion object {
        const val EXTRA_FOCUS = "shiroikuma.jiyusagyoban.extra.UI_FOCUS"

        /** Opens the settings with [focus]'s rows hoisted to the top. */
        fun open(context: Context, focus: String? = null) {
            context.startActivity(
                Intent(context, UiCustomizationActivity::class.java).apply {
                    focus?.let { putExtra(EXTRA_FOCUS, it) }
                }
            )
        }
    }
}
