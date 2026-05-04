package com.opentasker.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.opentasker.automation.model.AutomationRule
import com.opentasker.core.model.ActionSpec
import com.opentasker.core.model.Profile
import com.opentasker.core.model.Task
import com.opentasker.core.storage.toEntity
import com.opentasker.core.validation.InputValidation
import com.opentasker.ui.screens.ActionEditorScreen
import com.opentasker.ui.screens.ActionPickerScreen
import com.opentasker.ui.screens.AutomationExecutionLogScreen
import com.opentasker.ui.screens.AutomationRuleEditorScreen
import com.opentasker.ui.screens.AutomationRuleListScreen
import com.opentasker.ui.screens.BatchOperationsScreen
import com.opentasker.ui.screens.ContextPickerScreen
import com.opentasker.ui.screens.HomeScreen
import com.opentasker.ui.screens.ProfileEditorScreen
import com.opentasker.ui.screens.ProfileListScreen
import com.opentasker.ui.screens.RunLogScreen
import com.opentasker.ui.screens.TaskEditorScreen
import com.opentasker.ui.screens.TaskListScreen
import com.opentasker.ui.theme.OpenTaskerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        try {
            setContent {
                val db = OpenTaskerApp.db
                OpenTaskerTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                        var currentEditingTask by remember { mutableStateOf<Task?>(null) }
                        var currentEditingProfile by remember { mutableStateOf<Profile?>(null) }
                        var currentEditingRule by remember { mutableStateOf<AutomationRule?>(null) }
                        var taskEditorReturnTarget by remember { mutableStateOf<Screen>(Screen.TaskList) }
                        val scope = rememberCoroutineScope()

                        when (val screen = currentScreen) {
                    is Screen.Home -> {
                        HomeScreen(
                            onProfilesClick = { currentScreen = Screen.ProfileList },
                            onAutomationRulesClick = { currentScreen = Screen.AutomationRuleList },
                            onRunLogClick = { currentScreen = Screen.RunLog },
                            onTasksClick = { currentScreen = Screen.TaskList }
                        )
                    }
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
                            onManageTasks = { currentScreen = Screen.TaskList },
                        )
                    }
                    is Screen.TaskList -> {
                        TaskListScreen(
                            db = db,
                            onCreateTask = { 
                                taskEditorReturnTarget = Screen.TaskList
                                currentScreen = Screen.TaskEditor(null) 
                            },
                            onEditTask = { task: Task -> 
                                taskEditorReturnTarget = Screen.TaskList
                                currentScreen = Screen.TaskEditor(task) 
                            },
                            onDeleteTask = { task: Task ->
                                scope.launch {
                                    db.taskDao().delete(task.toEntity())
                                }
                            },
                            onBack = { currentScreen = Screen.Home },
                        )
                    }
                    is Screen.ProfileEditor -> {
                        if (currentEditingProfile == null) currentEditingProfile = screen.profile
                        ProfileEditorScreen(
                            profile = currentEditingProfile,
                            onSave = { profile ->
                                val validationErrors = InputValidation.validateProfile(profile)
                                if (validationErrors.isNotEmpty()) {
                                    val errorMessage = validationErrors.joinToString("\n") { "${it.field}: ${it.message}" }
                                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                                    return@ProfileEditorScreen
                                }
                                scope.launch {
                                    try {
                                        if (profile.id == 0L) {
                                            db.profileDao().insert(profile.toEntity())
                                        } else {
                                            db.profileDao().update(profile.toEntity())
                                        }
                                        currentEditingProfile = null
                                        currentScreen = Screen.ProfileList
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Failed to save profile: ${e.message}", e)
                                        Toast.makeText(this@MainActivity, "Failed to save profile: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onBack = {
                                currentEditingProfile = null
                                currentScreen = Screen.ProfileList
                            },
                            onAddContext = { currentScreen = Screen.ContextPicker },
                            db = db,
                        )
                    }
                    is Screen.TaskEditor -> {
                        if (currentEditingTask == null) currentEditingTask = screen.task
                        TaskEditorScreen(
                            task = currentEditingTask,
                            onSave = { task ->
                                val validationErrors = InputValidation.validateTask(task)
                                if (validationErrors.isNotEmpty()) {
                                    val errorMessage = validationErrors.joinToString("\n") { "${it.field}: ${it.message}" }
                                    Toast.makeText(this@MainActivity, errorMessage, Toast.LENGTH_LONG).show()
                                    return@TaskEditorScreen
                                }
                                scope.launch {
                                    try {
                                        if (task.id == 0L) {
                                            db.taskDao().insert(task.toEntity())
                                        } else {
                                            db.taskDao().update(task.toEntity())
                                        }
                                        currentEditingTask = null
                                        currentScreen = taskEditorReturnTarget
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Failed to save task: ${e.message}", e)
                                        Toast.makeText(this@MainActivity, "Failed to save task: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onBack = {
                                currentEditingTask = null
                                currentScreen = taskEditorReturnTarget
                            },
                            onTaskUpdated = { updatedTask ->
                                currentEditingTask = updatedTask
                            },
                            onAddAction = { currentScreen = Screen.ActionPicker },
                        )
                    }
                    is Screen.ActionPicker -> {
                        ActionPickerScreen(
                            onActionSelected = { action: ActionSpec ->
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
                    is Screen.AutomationRuleList -> {
                        AutomationRuleListScreen(
                            rules = emptyList(), // TODO: Load from repository
                            onCreateRule = { currentScreen = Screen.AutomationRuleEditor(null) },
                            onEditRule = { currentScreen = Screen.AutomationRuleEditor(it) },
                            onDeleteRule = { rule ->
                                scope.launch {
                                    // TODO: Delete from repository
                                }
                            },
                            onToggleRule = { rule ->
                                scope.launch {
                                    // TODO: Toggle in repository
                                }
                            },
                            onBack = { currentScreen = Screen.ProfileList }
                        )
                    }
                    is Screen.AutomationRuleEditor -> {
                        if (currentEditingRule == null) currentEditingRule = screen.rule
                        AutomationRuleEditorScreen(
                            rule = currentEditingRule,
                            onSave = { rule ->
                                scope.launch {
                                    // TODO: Save to repository
                                    currentEditingRule = null
                                    currentScreen = Screen.AutomationRuleList
                                }
                            },
                            onBack = {
                                currentEditingRule = null
                                currentScreen = Screen.AutomationRuleList
                            }
                        )
                    }
                    is Screen.AutomationExecutionLog -> {
                        AutomationExecutionLogScreen(
                            logs = emptyList(), // TODO: Load from repository
                            onBack = { currentScreen = Screen.AutomationRuleList }
                        )
                    }
                    }
                }
            }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to initialize UI: ${e.message}", e)
            // Show error screen if database fails
            setContent {
                OpenTaskerTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("Failed to initialize OpenTasker", style = MaterialTheme.typography.headlineSmall)
                            Text(e.message ?: "Unknown error", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

sealed class Screen {
    data object Home : Screen()
    data object ProfileList : Screen()
    data object TaskList : Screen()
    data object BatchOperations : Screen()
    data object AutomationRuleList : Screen()
    data class ProfileEditor(val profile: Profile?) : Screen()
    data class TaskEditor(val task: Task?) : Screen()
    data class AutomationRuleEditor(val rule: AutomationRule?) : Screen()
    data object ActionPicker : Screen()
    data class ActionEditor(val actionSpec: com.opentasker.core.model.ActionSpec) : Screen()
    data object ContextPicker : Screen()
    data object RunLog : Screen()
    data object AutomationExecutionLog : Screen()
}
