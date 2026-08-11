package com.opentasker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentasker.app.R
import com.opentasker.core.model.Scene
import com.opentasker.core.model.Task
import com.opentasker.core.scenes.SceneOverlayService
import com.opentasker.ui.theme.DesignSystem

@Composable
fun SceneLibraryScreen(
    scenes: List<Scene>,
    tasks: List<Task>,
    focusSceneId: Long? = null,
    onCreateScene: (String, Int, Int) -> Unit,
    onUpdateScene: (Scene, Int) -> Unit,
    onRemoveElement: (Scene, Int) -> Unit,
    onUndoSceneEdit: (Scene) -> Unit = {},
    onRedoSceneEdit: (Scene) -> Unit = {},
    onDeleteScene: (Scene) -> Unit,
    onDuplicateScene: (Scene) -> Unit = {},
    contentPadding: PaddingValues,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var elementEditorSceneId by rememberSaveable { mutableStateOf<Long?>(null) }
    var elementEditorIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val sortedScenes = remember(scenes) { scenes.sortedBy { it.name.lowercase() } }
    val listState = rememberLazyListState()
    val elementEditor = remember(scenes, elementEditorSceneId, elementEditorIndex) {
        sceneElementEditorState(scenes, elementEditorSceneId, elementEditorIndex, allowNew = true)
    }

    LaunchedEffect(focusSceneId, sortedScenes) {
        val focusedIndex = focusSceneId?.let { id -> sortedScenes.indexOfFirst { it.id == id } } ?: -1
        if (focusedIndex >= 0) listState.animateScrollToItem(focusedIndex + 1)
    }

    LaunchedEffect(elementEditorSceneId, elementEditor) {
        if (elementEditorSceneId != null && elementEditor == null) {
            elementEditorSceneId = null
            elementEditorIndex = null
        }
    }
    if (showCreateDialog) {
        SceneEditorDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { name, widthDp, heightDp ->
                onCreateScene(name, widthDp, heightDp)
                showCreateDialog = false
            },
        )
    }

    elementEditor?.let { state ->
        SceneElementEditorDialog(
            state = state,
            tasks = tasks,
            onDismiss = {
                elementEditorSceneId = null
                elementEditorIndex = null
            },
            onSave = { element ->
                val updatedScene = if (state.index == null) {
                    state.scene.copy(elements = state.scene.elements + element)
                } else {
                    state.scene.copy(
                        elements = state.scene.elements.mapIndexed { index, existing ->
                            if (index == state.index) element else existing
                        },
                    )
                }
                onUpdateScene(
                    updatedScene,
                    if (state.index == null) R.string.ui_message_element_added else R.string.ui_message_element_updated,
                )
                elementEditorSceneId = null
                elementEditorIndex = null
            },
        )
    }

    if (sortedScenes.isEmpty()) {
        SceneEmptyState(
            contentPadding = contentPadding,
            onCreateScene = { showCreateDialog = true },
        )
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.Spacing.md),
    ) {
        item {
            SceneOverviewCard(
                scenes = sortedScenes,
                tasks = tasks,
                onCreateScene = { showCreateDialog = true },
            )
        }
        items(sortedScenes, key = { it.id }) { scene ->
            val sceneContext = LocalContext.current
            SceneCard(
                scene = scene,
                tasks = tasks,
                onAddElement = {
                    elementEditorSceneId = scene.id
                    elementEditorIndex = null
                },
                onEditElement = { index, _ ->
                    elementEditorSceneId = scene.id
                    elementEditorIndex = index
                },
                onDeleteElement = { index, _ ->
                    onRemoveElement(scene, index)
                },
                onUpdateScene = onUpdateScene,
                onUndo = { onUndoSceneEdit(scene) },
                onRedo = { onRedoSceneEdit(scene) },
                onDelete = { onDeleteScene(scene) },
                onDuplicate = { onDuplicateScene(scene) },
                onShowOverlay = { SceneOverlayService.show(sceneContext, scene) },
            )
        }
    }
}
