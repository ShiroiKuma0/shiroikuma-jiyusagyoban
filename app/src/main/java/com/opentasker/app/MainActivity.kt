package com.opentasker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.opentasker.core.model.Profile
import com.opentasker.core.storage.toEntity
import com.opentasker.ui.screens.ProfileEditorScreen
import com.opentasker.ui.screens.ProfileListScreen
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val db = OpenTaskerApp.db
            OpenTaskerTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.ProfileList) }
                val scope = rememberCoroutineScope()

                when (val screen = currentScreen) {
                    is Screen.ProfileList -> {
                        ProfileListScreen(
                            db = db,
                            onCreateProfile = { currentScreen = Screen.ProfileEditor(null) },
                            onEditProfile = { currentScreen = Screen.ProfileEditor(it) },
                            onDeleteProfile = { profile ->
                                scope.launch {
                                    db.profileDao().delete(profile.toEntity())
                                }
                            },
                        )
                    }
                    is Screen.ProfileEditor -> {
                        ProfileEditorScreen(
                            profile = screen.profile,
                            onSave = { profile ->
                                scope.launch {
                                    if (profile.id == 0L) {
                                        db.profileDao().insert(profile.toEntity())
                                    } else {
                                        db.profileDao().update(profile.toEntity())
                                    }
                                    currentScreen = Screen.ProfileList
                                }
                            },
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
