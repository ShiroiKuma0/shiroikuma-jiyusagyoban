package com.opentasker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.opentasker.core.model.Profile
import com.opentasker.ui.screens.ProfileEditorScreen
import com.opentasker.ui.screens.ProfileListScreen
import com.opentasker.ui.theme.OpenTaskerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenTaskerTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.ProfileList) }

                when (val screen = currentScreen) {
                    is Screen.ProfileList -> {
                        ProfileListScreen(
                            onCreateProfile = { currentScreen = Screen.ProfileEditor(null) },
                            onEditProfile = { currentScreen = Screen.ProfileEditor(it) },
                            onDeleteProfile = { /* TODO */ },
                        )
                    }
                    is Screen.ProfileEditor -> {
                        ProfileEditorScreen(
                            profile = screen.profile,
                            onSave = { /* TODO: save to DB */ currentScreen = Screen.ProfileList },
                            onBack = { currentScreen = Screen.ProfileList },
                        )
                    }
                }
            }
        }
    }
}

sealed class Screen {
    data object ProfileList : Screen()
    data class ProfileEditor(val profile: Profile?) : Screen()
}

