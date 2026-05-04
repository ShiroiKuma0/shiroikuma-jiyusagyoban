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
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import com.opentasker.ui.screens.ActionEditorScreen
import com.opentasker.ui.screens.ActionPickerScreen
import com.opentasker.ui.screens.BatchOperationsScreen
import com.opentasker.ui.screens.ContextPickerScreen
import com.opentasker.ui.screens.ProfileEditorScreen
import com.opentasker.ui.screens.ProfileListScreen
import com.opentasker.ui.screens.RunLogScreen
import com.opentasker.ui.screens.TaskEditorScreen
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
                var currentEditingTask by remember { mutableStateOf<Task?>(null) }
                var currentEditingProfile by remember { mutableStateOf<Profile?>(null) }
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
                            onViewRunLog = { currentScreen = Screen.RunLog },
                            onBatchOperations = { currentScreen = Screen.BatchOperations },
                        )
                    }
                    is Screen.ProfileEditor -> {
                        if (currentEditingProfile == null) currentEditingProfile = screen.profile
                        ProfileEditorScreen(
                            profile = currentEditingProfile,
                            onSave = { profile ->
                                scope.launch {
                                    if (profile.id == 0L) {
                                        db.profileDao().insert(profile.toEntity())
                                    } else {
                                        db.profileDao().update(profile.toEntity())
                                    }
                                    currentEditingProfile = null
                                    currentScreen = Screen.ProfileList
                                }
                            },
                            onBack = {
                                currentEditingProfile = null
                                currentScreen = Screen.ProfileList
                            },
                            onAddContext = { currentScreen = Screen.ContextPicker },
                        )
                    }
                    is Screen.TaskEditor -> {
                        if (currentEditingTask == null) currentEditingTask = screen.task
                        TaskEditorScreen(
                            task = currentEditingTask,
                            onSave = { task ->
                                scope.launch {
                                    if (task.id == 0L) {
                                        db.taskDao().insert(task.toEntity())
                                    } else {
                                        db.taskDao().update(task.toEntity())
                                    }
                                    currentEditingTask = null
                                    currentScreen = Screen.ProfileList
                                }
                            },
                            onBack = {
                                currentEditingTask = null
                                currentScreen = Screen.ProfileList
                            },
                            onTaskUpdated = { updatedTask ->
                                currentEditingTask = updatedTask
                            },
                            onAddAction = { currentScreen = Screen.ActionPicker },
                        )
                    }
                    is Screen.ActionPicker -> {
                        ActionPickerScreen(
                            onActionSelected = { action ->
                                currentScreen = Screen.ActionEditor(action)
                            },
                            onCancel = { currentScreen = Screen.TaskEditor(currentEditingTask) },
                        )
                    }
                    is Screen.ActionEditor -> {
                        ActionEditorScreen(
                            actionSpec = screen.actionSpec,
                            onSave = { action ->
                                if (currentEditingTask != null) {
                                    currentEditingTask = currentEditingTask!!.copy(
                                        actions = currentEditingTask!!.actions + action
                                    )
                                }
                                currentScreen = Screen.TaskEditor(currentEditingTask)
                            },
                            onCancel = { currentScreen = Screen.ActionPicker },
                        )
                    }
                    is Screen.ContextPicker -> {
                        ContextPickerScreen(
                            onContextSelected = { context ->
                                if (currentEditingProfile != null) {
                                    currentEditingProfile = currentEditingProfile!!.copy(
                                        contexts = currentEditingProfile!!.contexts + context
                                    )
                                }
                                currentScreen = Screen.ProfileEditor(currentEditingProfile)
                            },
                            onCancel = { currentScreen = Screen.ProfileEditor(currentEditingProfile) },
                        )
                    }
                    is Screen.RunLog -> {
                        RunLogScreen(
                            db = db,
                            onBack = { currentScreen = Screen.ProfileList },
                        )
                    }
                    is Screen.BatchOperations -> {
                        BatchOperationsScreen(
                            db = db,
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
    data object BatchOperations : Screen()
    data class ProfileEditor(val profile: Profile?) : Screen()
    data class TaskEditor(val task: Task?) : Screen()
    data object ActionPicker : Screen()
    data class ActionEditor(val actionSpec: com.opentasker.core.model.ActionSpec) : Screen()
    data object ContextPicker : Screen()
    data object RunLog : Screen()
}
